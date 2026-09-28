package com.taptap.game.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.taptap.game.core.audio.Sfx

/**
 * Low-latency sound effects via [SoundPool] (spec §49): all effects are decoded once at start
 * from uncompressed WAV assets, so playing one is just a mixer command.
 */
class SfxPlayer(context: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = IntArray(SFX.size)
    private val loaded = BooleanArray(SFX.size)

    @Volatile var enabled = true
    var volume = 1f

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                val idx = ids.indexOf(sampleId)
                if (idx >= 0) loaded[idx] = true
            }
        }
        for (sfx in SFX) {
            try {
                context.assets.openFd("sfx/${sfx.file}.wav").use { fd -> ids[sfx.ordinal] = pool.load(fd, 1) }
            } catch (t: Throwable) {
                Log.e("TapTapSfx", "Missing sound ${sfx.file}", t)
            }
        }
    }

    /** Plays [sfx]; [rate] 0.5–2 shifts pitch (used for subtle variations), [pan] -1..1. */
    fun play(sfx: Sfx, vol: Float = 1f, rate: Float = 1f, pan: Float = 0f, priority: Int = 1) {
        if (!enabled) return
        val i = sfx.ordinal
        if (!loaded[i]) return
        val v = (vol * volume).coerceIn(0f, 1f)
        val left = v * (if (pan > 0f) 1f - pan else 1f)
        val right = v * (if (pan < 0f) 1f + pan else 1f)
        pool.play(ids[i], left, right, priority, 0, rate.coerceIn(0.5f, 2f))
    }

    fun autoPause() = pool.autoPause()
    fun autoResume() = pool.autoResume()

    fun release() = pool.release()

    companion object {
        private const val MAX_STREAMS = 14
        private val SFX = Sfx.values()
    }
}
