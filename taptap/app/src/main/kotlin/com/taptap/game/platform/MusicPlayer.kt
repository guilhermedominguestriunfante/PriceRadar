package com.taptap.game.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.taptap.game.core.audio.MusicEngine

/**
 * Streams the procedural [MusicEngine] through an [AudioTrack] on a dedicated audio-priority
 * thread. The blocking write paces rendering; stopping the thread when the app is in the
 * background or music is disabled means zero CPU cost.
 */
class MusicPlayer(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val sampleRate: Int = (audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000)
        .coerceIn(22_050, 96_000)
    val engine = MusicEngine(sampleRate)

    @Volatile private var running = false
    private var thread: Thread? = null
    private var hasFocus = false
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine.volume = 0f
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine.volume = 0.3f
                AudioManager.AUDIOFOCUS_GAIN -> engine.volume = baseVolume
            }
        }
        .build()

    var baseVolume = 0.6f
        set(value) {
            field = value
            engine.volume = value
        }

    init {
        engine.volume = baseVolume
    }

    fun start() {
        if (running) return
        hasFocus = audioManager?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        engine.volume = baseVolume
        running = true
        thread = Thread({ loop() }, "taptap-music").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let {
            try {
                it.join(500)
            } catch (ignored: InterruptedException) {
            }
        }
        thread = null
        if (hasFocus) audioManager?.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val frames = FRAMES_PER_WRITE
        val bufferBytes = maxOf(minBytes, frames * 4 * 4)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack unavailable", t)
            running = false
            return
        }
        val pcm = ShortArray(frames * 2)
        try {
            track.play()
            while (running) {
                engine.render(pcm, frames)
                var off = 0
                while (off < pcm.size && running) {
                    val w = track.write(pcm, off, pcm.size - off)
                    if (w < 0) {
                        running = false
                        break
                    }
                    off += w
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Music stream stopped", t)
        } finally {
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    companion object {
        private const val TAG = "TapTapMusic"
        private const val FRAMES_PER_WRITE = 512
    }
}
