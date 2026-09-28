package com.dedonervoso.core.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Objective guards for procedurally generated audio: no clipping, no silence, no NaN/DC,
 * sensible loudness, short click-free edges, and the music reacting to gameplay controls.
 */
class AudioTest {
    private fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i] * x[i].toDouble()
        return 20 * log10(sqrt(sum / (to - from)) + 1e-12)
    }

    @Test
    fun everySoundEffectIsCleanAndNormalised() {
        for (sfx in Sfx.values()) {
            val s = SfxBank.render(sfx)
            assertTrue(s.isNotEmpty(), "$sfx empty")
            assertTrue(s.none { it.isNaN() || it.isInfinite() }, "$sfx has NaN")
            val peak = s.maxOf { abs(it) }
            assertTrue(peak in 0.1f..0.95f, "$sfx peak $peak")
            assertTrue(abs(s.first()) < 0.02f && abs(s.last()) < 0.02f, "$sfx edges must be click free")
            val mean = s.average()
            assertTrue(abs(mean) < 0.02, "$sfx DC offset $mean")
            assertTrue(s.size <= SfxBank.SAMPLE_RATE * 2, "$sfx too long")
        }
    }

    @Test
    fun tapSoundsAreShortAndVaried() {
        val taps = listOf(Sfx.TAP_1, Sfx.TAP_2, Sfx.TAP_3, Sfx.TAP_4).map { SfxBank.render(it) }
        assertTrue(taps.all { it.size <= SfxBank.SAMPLE_RATE / 10 }, "tap sounds must be very short")
        assertTrue(taps.map { it.toList().hashCode() }.toSet().size == 4, "four distinct variations")
    }

    private fun renderMusic(e: MusicEngine, seconds: Float): Pair<FloatArray, FloatArray> {
        val n = (e.sampleRate * seconds).toInt()
        val l = FloatArray(n)
        val r = FloatArray(n)
        e.renderFloat(l, r, n)
        return l to r
    }

    @Test
    fun musicIsCleanInEveryModeAndIntensity() {
        for (mode in MusicMode.values()) {
            for (level in 0..4) {
                val e = MusicEngine(48_000)
                e.mode = mode
                e.intensity = level
                val (l, r) = renderMusic(e, 6f)
                assertTrue(l.none { it.isNaN() } && r.none { it.isNaN() }, "$mode/$level NaN")
                val peak = maxOf(l.maxOf { abs(it) }, r.maxOf { abs(it) })
                assertTrue(peak < 1f, "$mode/$level peak $peak")
                val db = rmsDb(l, 48_000, l.size)
                assertTrue(db in -32.0..-8.0, "$mode/$level loudness $db dBFS")
            }
        }
    }

    @Test
    fun stopMufflesMusicAndFrenzyLiftsIt() {
        val e = MusicEngine(48_000)
        e.mode = MusicMode.GAME
        e.intensity = 3
        val (normal, _) = renderMusic(e, 4f)
        e.stopped = true
        val (muffled, _) = renderMusic(e, 2f)
        val loud = rmsDb(normal, 48_000, normal.size)
        val quiet = rmsDb(muffled, 24_000, muffled.size)
        assertTrue(quiet < loud - 6, "STOP must duck the music ($loud → $quiet)")
        e.stopped = false
        e.frenzy = true
        val (frenzy, _) = renderMusic(e, 4f)
        assertTrue(rmsDb(frenzy, 96_000, frenzy.size) > quiet + 6)
    }

    @Test
    fun renderIntoPcmBuffer() {
        val e = MusicEngine(44_100)
        e.mode = MusicMode.GAME
        val buf = ShortArray(512 * 2)
        repeat(200) { e.render(buf, 512) }
        assertTrue(buf.any { it.toInt() != 0 })
    }
}
