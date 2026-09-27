package com.taptap.game.core.util

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

fun clamp(v: Float, min: Float, max: Float): Float = if (v < min) min else if (v > max) max else v
fun clamp(v: Int, min: Int, max: Int): Int = if (v < min) min else if (v > max) max else v
fun clamp(v: Long, min: Long, max: Long): Long = if (v < min) min else if (v > max) max else v
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Maps `v` from `[a, b]` to `[0, 1]`, clamped. */
fun progress(v: Float, a: Float, b: Float): Float = if (b == a) 1f else clamp((v - a) / (b - a), 0f, 1f)

/**
 * Position of a point that moves linearly from `lo` and bounces between `lo` and `hi`
 * (triangle wave). Pure function of `v`, used for analytic zone motion.
 */
fun reflect(v: Float, lo: Float, hi: Float): Float {
    val span = hi - lo
    if (span <= 0f) return lo
    var m = (v - lo) % (2f * span)
    if (m < 0f) m += 2f * span
    return if (m <= span) lo + m else hi - (m - span)
}

/** Easing curves (t in [0, 1]). */
object Ease {
    fun outCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    fun inCubic(t: Float): Float = t * t * t

    fun inOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).toDouble().pow(3.0).toFloat() / 2f

    fun outBack(t: Float, overshoot: Float = 1.70158f): Float {
        val c3 = overshoot + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + overshoot * u * u
    }

    fun outElastic(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = (2.0 * PI / 3.0)
        return (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0).toFloat()
    }

    fun outQuad(t: Float): Float = 1f - (1f - t) * (1f - t)

    /** 0 → 1 → 0 bump. */
    fun pulse(t: Float): Float = sin(clamp(t, 0f, 1f) * PI).toFloat()
}
