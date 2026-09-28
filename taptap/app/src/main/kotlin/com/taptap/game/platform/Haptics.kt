package com.taptap.game.platform

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback (spec §34): feather-light ticks for taps (rate limited), a firmer click for
 * PERFECT, a distinct double impact for STOP, a rough buzz for errors. Vibrator calls cross
 * process boundaries, so they run on a background thread and never delay input handling.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val available = vibrator?.hasVibrator() == true
    private val amplitude = vibrator?.hasAmplitudeControl() == true
    private val thread = HandlerThread("taptap-haptics").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile var enabled = true
    /** Taps are skipped during frenzy so the phone doesn't buzz continuously. */
    @Volatile var suppressTaps = false
    private var lastTapMs = 0L

    private val tickEffect = predefinedOr(VibrationEffect.EFFECT_TICK) { oneShot(6, 45) }
    private val clickEffect = predefinedOr(VibrationEffect.EFFECT_CLICK) { oneShot(10, 90) }
    private val heavyEffect = predefinedOr(VibrationEffect.EFFECT_HEAVY_CLICK) { oneShot(18, 180) }
    private val doubleEffect = predefinedOr(VibrationEffect.EFFECT_DOUBLE_CLICK) {
        waveform(longArrayOf(0, 20, 50, 20), intArrayOf(0, 180, 0, 180))
    }
    private val stopEffect = waveform(longArrayOf(0, 40, 50, 70), intArrayOf(0, 255, 0, 200))
    private val frenzyEffect = oneShot(45, 160)
    private val celebrateEffect = waveform(longArrayOf(0, 25, 60, 25, 60, 50), intArrayOf(0, 140, 0, 180, 0, 255))

    fun tap() {
        if (!enabled || suppressTaps) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTapMs < TAP_INTERVAL_MS) return
        lastTapMs = now
        play(tickEffect)
    }

    fun click() = playIfEnabled(clickEffect)
    fun perfect() = playIfEnabled(heavyEffect)
    fun stop() = playIfEnabled(stopEffect)
    fun error() = playIfEnabled(doubleEffect)
    fun frenzy() = playIfEnabled(frenzyEffect)
    fun celebrate() = playIfEnabled(celebrateEffect)

    private fun playIfEnabled(effect: VibrationEffect?) {
        if (enabled) play(effect)
    }

    private fun play(effect: VibrationEffect?) {
        if (!available || effect == null) return
        handler.post {
            try {
                vibrator?.vibrate(effect)
            } catch (ignored: Throwable) {
                // Some OEM builds throw on odd effects; haptics are never worth a crash.
            }
        }
    }

    private fun predefinedOr(id: Int, fallback: () -> VibrationEffect?): VibrationEffect? =
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(id) else fallback()

    private fun oneShot(ms: Long, amp: Int): VibrationEffect =
        VibrationEffect.createOneShot(ms, if (amplitude) amp else VibrationEffect.DEFAULT_AMPLITUDE)

    private fun waveform(timings: LongArray, amps: IntArray): VibrationEffect =
        if (amplitude) VibrationEffect.createWaveform(timings, amps, -1) else VibrationEffect.createWaveform(timings, -1)

    fun release() {
        thread.quitSafely()
    }

    companion object {
        private const val TAP_INTERVAL_MS = 70L
    }
}
