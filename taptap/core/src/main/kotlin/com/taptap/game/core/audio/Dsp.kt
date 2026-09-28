package com.taptap.game.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tiny DSP toolkit shared by the build-time sound effect generator and the on-device music
 * engine. Everything is allocation-free per sample.
 */
object Dsp {
    const val TWO_PI = (2.0 * PI).toFloat()

    fun midiToHz(note: Float): Float = 440f * 2f.pow((note - 69f) / 12f)

    fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

    /** Smooth saturation that keeps peaks under 1 (≈ tanh). */
    fun softClip(x: Float): Float {
        if (x > 3f) return 1f
        if (x < -3f) return -1f
        val x2 = x * x
        return x * (27f + x2) / (27f + 9f * x2)
    }

    /** PolyBLEP residual for band-limited saw/square (t = phase 0..1, dt = phase increment). */
    fun polyBlep(t: Float, dt: Float): Float {
        return if (t < dt) {
            val x = t / dt
            x + x - x * x - 1f
        } else if (t > 1f - dt) {
            val x = (t - 1f) / dt
            x * x + x + x + 1f
        } else {
            0f
        }
    }

    fun sine(phase: Float): Float = sin(phase * TWO_PI)

    fun triangle(phase: Float): Float {
        val p = phase - kotlin.math.floor(phase)
        return 4f * abs(p - 0.5f) - 1f
    }

    fun saw(phase: Float, dt: Float): Float {
        val p = phase - kotlin.math.floor(phase)
        return 2f * p - 1f - polyBlep(p, dt)
    }

    fun square(phase: Float, dt: Float, width: Float = 0.5f): Float {
        val p = phase - kotlin.math.floor(phase)
        var v = if (p < width) 1f else -1f
        v += polyBlep(p, dt)
        var q = p + (1f - width)
        q -= kotlin.math.floor(q)
        v -= polyBlep(q, dt)
        return v
    }

    /** Coefficient for a one-pole smoother reaching ~63% in [ms]. */
    fun smoothCoef(ms: Float, sampleRate: Int): Float = 1f - exp(-1f / (ms * 0.001f * sampleRate))
}

/** Fast xorshift noise in [-1, 1]. */
class Noise(seed: Int = 0x1234567) {
    private var s = if (seed == 0) 1 else seed
    fun next(): Float {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return (x and 0xFFFFFF) / 8388608f - 1f
    }
}

/** Topology-preserving state variable filter (Zavalishin). Low/band/high outputs. */
class Svf(private val sampleRate: Int) {
    private var ic1 = 0f
    private var ic2 = 0f
    private var g = 0f
    private var k = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    var low = 0f
        private set
    var band = 0f
        private set
    var high = 0f
        private set
    private var lastCutoff = -1f
    private var lastQ = -1f

    fun set(cutoffHz: Float, q: Float) {
        if (cutoffHz == lastCutoff && q == lastQ) return
        lastCutoff = cutoffHz
        lastQ = q
        val fc = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
        g = tan(PI * fc / sampleRate).toFloat()
        k = 1f / q.coerceAtLeast(0.3f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Float): Float {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        low = v2
        band = v1
        high = x - k * v1 - v2
        return v2
    }

    fun reset() {
        ic1 = 0f
        ic2 = 0f
    }
}

/** One-pole low-pass (also used as a DC blocker via [highPass]). */
class OnePole(private val sampleRate: Int) {
    private var a = 1f
    private var z = 0f

    fun setCutoff(hz: Float) {
        a = 1f - exp(-2f * PI.toFloat() * hz / sampleRate)
    }

    fun lowPass(x: Float): Float {
        z += a * (x - z)
        return z
    }

    fun highPass(x: Float): Float = x - lowPass(x)
}

/** Simple stereo-agnostic feedback delay line. */
class Delay(maxSamples: Int) {
    private val buf = FloatArray(maxSamples)
    private var idx = 0

    fun process(x: Float, delaySamples: Int, feedback: Float, mix: Float): Float {
        val d = delaySamples.coerceIn(1, buf.size - 1)
        var read = idx - d
        if (read < 0) read += buf.size
        val delayed = buf[read]
        buf[idx] = x + delayed * feedback
        idx++
        if (idx >= buf.size) idx = 0
        return x + delayed * mix
    }

    fun clear() = buf.fill(0f)
}
