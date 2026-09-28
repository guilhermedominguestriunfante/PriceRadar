package com.taptap.game.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import com.taptap.game.core.save.SaveCodec
import com.taptap.game.core.save.SaveData
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Persists [SaveData] as JSON with [AtomicFile] (crash-safe: a write either fully happens or
 * the previous file stays intact). Encoding happens on the main thread — where the data is
 * mutated — and the disk write on a background thread, so saving never stalls gameplay.
 * Saves are debounced; [flush] forces one (used when the app goes to background).
 */
class SaveStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val dir = context.filesDir
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "taptap-save").apply { priority = Thread.MIN_PRIORITY }
    }
    private var pendingData: SaveData? = null
    private var lastWrite: Future<*>? = null
    private val debounced = Runnable { pendingData?.let { writeAsync(it) } }

    /** Loads the save; a missing file starts fresh and a corrupt one is kept aside, never fatal. */
    fun load(): SaveData {
        if (!file.baseFile.exists()) return SaveData()
        return try {
            SaveCodec.decode(String(file.readFully(), Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.e(TAG, "Corrupt save, starting fresh", t)
            try {
                file.baseFile.copyTo(File(dir, "taptap_save.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            } catch (ignored: Throwable) {
            }
            SaveData()
        }
    }

    /** Schedules a save shortly (coalescing bursts of changes). Main thread only. */
    fun scheduleSave(data: SaveData) {
        pendingData = data
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    /** Writes immediately (async) and optionally waits for it — used on pause/stop. */
    fun flush(data: SaveData, wait: Boolean) {
        main.removeCallbacks(debounced)
        writeAsync(data)
        if (wait) {
            try {
                lastWrite?.get(WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.w(TAG, "Save did not finish in time", t)
            }
        }
    }

    private fun writeAsync(data: SaveData) {
        pendingData = null
        val json = try {
            SaveCodec.encode(data)
        } catch (t: Throwable) {
            Log.e(TAG, "Encode failed", t)
            return
        }
        lastWrite = io.submit { write(json) }
    }

    private fun write(json: String) {
        val out = try {
            file.startWrite()
        } catch (t: Throwable) {
            Log.e(TAG, "Cannot open save for writing", t)
            return
        }
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (t: Throwable) {
            Log.e(TAG, "Save failed", t)
            file.failWrite(out)
        }
    }

    fun close() {
        io.shutdown()
    }

    companion object {
        const val FILE_NAME = "taptap_save.json"
        private const val TAG = "TapTapSave"
        private const val DEBOUNCE_MS = 400L
        private const val WAIT_MS = 1_500L
    }
}
