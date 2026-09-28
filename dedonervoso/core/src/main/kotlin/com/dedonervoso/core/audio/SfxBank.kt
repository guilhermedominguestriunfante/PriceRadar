package com.dedonervoso.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Every sound effect of the game. [file] is the asset name (sfx/<file>.wav). */
enum class Sfx(val file: String) {
    TAP_1("tap1"), TAP_2("tap2"), TAP_3("tap3"), TAP_4("tap4"),
    TAP_ZONE("tap_zone"), PERFECT("perfect"), CRIT("crit"), COIN("coin"),
    COMBO_1("combo1"), COMBO_2("combo2"), COMBO_3("combo3"), COMBO_4("combo4"), COMBO_5("combo5"), COMBO_6("combo6"),
    STOP("stop"), STOP_WARN("stop_warn"), GO("go"), FAULT("fault"), MISS("miss"),
    FRENZY_START("frenzy_start"), FRENZY_END("frenzy_end"), MEGA("mega"),
    RECORD("record"), WIN("win"), LOSE("lose"), BUY("buy"),
    UI_CLICK("ui_click"), UI_BACK("ui_back"), COUNT_BEEP("count_beep"),
    LEVEL_UP("level_up"), ACHIEVEMENT("achievement"), STAR_1("star1"), STAR_2("star2"), STAR_3("star3"),
    TIME_BONUS("time_bonus"), SHIELD("shield"), ZONE_SPAWN("zone_spawn"), ZONE_POP("zone_pop"),
    GOLDEN("golden"), REFLEX_READY("reflex_ready"),
}

/**
 * Procedural sound design for every [Sfx]. Rendered to WAV at build time by :tools (so the APK
 * ships ready-to-play PCM for low-latency SoundPool playback). Sounds are short, soft-attacked
 * and level-normalised; tap sounds have four variations to avoid fatigue (spec §32).
 */
object SfxBank {
    const val SAMPLE_RATE = 44_100

    enum class Wave { SINE, TRI, SAW, SQUARE }

    /** Offline mixing buffer with layer helpers. */
    class Mix(seconds: Float) {
        val sr = SAMPLE_RATE
        val buf = FloatArray((seconds * SAMPLE_RATE).toInt())
        private val noise = Noise(0x5EED)

        /**
         * Adds an oscillator note. Pitch glides exponentially from [f0] to [f1] over [glide] s.
         * Envelope: linear attack, then exponential decay with time constant [decay] s.
         */
        fun tone(
            start: Float, dur: Float, f0: Float, f1: Float = f0, wave: Wave = Wave.SINE, amp: Float = 0.5f,
            attack: Float = 0.002f, decay: Float = 1e9f, glide: Float = dur, vibratoHz: Float = 0f,
            vibratoDepth: Float = 0f, width: Float = 0.5f,
        ) {
            val s0 = (start * sr).toInt()
            val n = (dur * sr).toInt()
            var phase = 0f
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= buf.size) break
                val t = i.toFloat() / sr
                val g = min(1f, t / glide)
                var f = f0 * (f1 / f0).pow(g)
                if (vibratoHz > 0f) f *= 1f + vibratoDepth * sin(Dsp.TWO_PI * vibratoHz * t)
                val dt = f / sr
                val v = when (wave) {
                    Wave.SINE -> Dsp.sine(phase)
                    Wave.TRI -> Dsp.triangle(phase)
                    Wave.SAW -> Dsp.saw(phase, dt)
                    Wave.SQUARE -> Dsp.square(phase, dt, width)
                }
                phase += dt
                if (phase >= 1f) phase -= 1f
                buf[idx] += v * amp * env(t, dur, attack, decay)
            }
        }

        /** Adds filtered noise; [lp]/[hp] in Hz (0 = off); band-pass sweep with [bp0]→[bp1]. */
        fun noise(
            start: Float, dur: Float, amp: Float, attack: Float = 0.001f, decay: Float = 1e9f,
            lp: Float = 0f, hp: Float = 0f, bp0: Float = 0f, bp1: Float = bp0, q: Float = 1.2f,
        ) {
            val s0 = (start * sr).toInt()
            val n = (dur * sr).toInt()
            val low = if (lp > 0f) OnePole(sr).also { it.setCutoff(lp) } else null
            val high = if (hp > 0f) OnePole(sr).also { it.setCutoff(hp) } else null
            val band = if (bp0 > 0f) Svf(sr) else null
            for (i in 0 until n) {
                val idx = s0 + i
                if (idx >= buf.size) break
                val t = i.toFloat() / sr
                var v = noise.next()
                if (band != null) {
                    band.set(bp0 * (bp1 / bp0).pow(t / dur), q)
                    band.process(v)
                    v = band.band
                }
                if (high != null) v = high.highPass(v)
                if (low != null) v = low.lowPass(v)
                buf[idx] += v * amp * env(t, dur, attack, decay)
            }
        }

        /** Resonant low-pass over the whole buffer with a cutoff sweep. */
        fun lowPass(from: Float, to: Float = from, q: Float = 0.8f, sweep: Float = 0.2f) {
            val f = Svf(sr)
            for (i in buf.indices) {
                val t = i.toFloat() / sr
                f.set(from * (to / from).pow(min(1f, t / sweep)), q)
                buf[i] = f.process(buf[i])
            }
        }

        fun echo(delayS: Float, feedback: Float, mix: Float) {
            val d = Delay((delayS * sr).toInt() + 2)
            for (i in buf.indices) buf[i] = d.process(buf[i], (delayS * sr).toInt(), feedback, mix)
        }

        fun drive(amount: Float) {
            for (i in buf.indices) buf[i] = Dsp.softClip(buf[i] * amount)
        }

        /** Normalises to [peakDb] dBFS and applies a short fade-in/out to avoid clicks. */
        fun finish(peakDb: Float): FloatArray {
            var peak = 1e-9f
            for (v in buf) peak = max(peak, abs(v))
            val gain = Dsp.dbToGain(peakDb) / peak
            val fadeIn = (0.0008f * sr).toInt()
            val fadeOut = min(buf.size / 4, (0.006f * sr).toInt())
            for (i in buf.indices) {
                var g = gain
                if (i < fadeIn) g *= i.toFloat() / fadeIn
                val fromEnd = buf.size - 1 - i
                if (fromEnd < fadeOut) g *= fromEnd.toFloat() / fadeOut
                buf[i] *= g
            }
            return buf
        }

        private fun env(t: Float, dur: Float, attack: Float, decay: Float): Float {
            val a = if (t < attack) t / attack else exp(-(t - attack) / decay)
            val tail = dur - t
            return if (tail < 0.004f) a * max(0f, tail / 0.004f) else a
        }
    }

    private fun hz(midi: Int) = Dsp.midiToHz(midi.toFloat())

    fun render(sfx: Sfx): FloatArray = when (sfx) {
        Sfx.TAP_1 -> tap(1250f, Wave.SINE)
        Sfx.TAP_2 -> tap(1400f, Wave.SINE)
        Sfx.TAP_3 -> tap(1180f, Wave.TRI)
        Sfx.TAP_4 -> tap(1320f, Wave.TRI)
        Sfx.TAP_ZONE -> Mix(0.16f).apply {
            tapLayers(this, 1500f)
            tone(0f, 0.16f, 2637f, amp = 0.2f, attack = 0.002f, decay = 0.07f)
            tone(0f, 0.12f, 3951f, amp = 0.06f, decay = 0.04f)
        }.finish(-6f)
        Sfx.PERFECT -> Mix(0.55f).apply {
            tone(0f, 0.55f, 1760f, amp = 0.35f, attack = 0.003f, decay = 0.3f)
            tone(0f, 0.45f, 2637f, amp = 0.2f, attack = 0.003f, decay = 0.22f)
            tone(0f, 0.35f, 3520f, amp = 0.1f, attack = 0.003f, decay = 0.15f)
            tone(0.045f, 0.45f, 2093f, amp = 0.2f, attack = 0.003f, decay = 0.22f)
            tone(0.09f, 0.4f, 3136f, amp = 0.14f, attack = 0.003f, decay = 0.18f)
            noise(0f, 0.05f, 0.08f, hp = 6000f, decay = 0.02f)
        }.finish(-3f)
        Sfx.CRIT -> Mix(0.35f).apply {
            tone(0f, 0.08f, 2093f, wave = Wave.SQUARE, amp = 0.18f, decay = 0.04f)
            tone(0f, 0.35f, 4186f, amp = 0.2f, decay = 0.12f)
            tone(0.03f, 0.3f, 3136f, amp = 0.15f, decay = 0.1f)
            noise(0f, 0.02f, 0.25f, hp = 3000f, decay = 0.006f)
            lowPass(9000f)
        }.finish(-4f)
        Sfx.COIN -> Mix(0.38f).apply {
            tone(0f, 0.075f, 988f, wave = Wave.SQUARE, amp = 0.25f, width = 0.5f)
            tone(0.07f, 0.31f, 1319f, wave = Wave.SQUARE, amp = 0.25f, decay = 0.11f)
            tone(0.07f, 0.31f, 2637f, amp = 0.06f, decay = 0.08f)
            lowPass(4800f)
        }.finish(-6f)
        Sfx.COMBO_1 -> combo(0)
        Sfx.COMBO_2 -> combo(1)
        Sfx.COMBO_3 -> combo(2)
        Sfx.COMBO_4 -> combo(3)
        Sfx.COMBO_5 -> combo(4)
        Sfx.COMBO_6 -> combo(5)
        Sfx.STOP -> Mix(0.62f).apply {
            tone(0f, 0.6f, 160f, 72f, wave = Wave.SAW, amp = 0.45f, attack = 0.003f, decay = 0.35f, glide = 0.35f)
            tone(0f, 0.6f, 168f, 76f, wave = Wave.SAW, amp = 0.35f, attack = 0.003f, decay = 0.35f, glide = 0.35f)
            tone(0f, 0.4f, 55f, amp = 0.55f, attack = 0.004f, decay = 0.2f)
            noise(0f, 0.12f, 0.3f, lp = 1500f, decay = 0.05f)
            lowPass(2200f, 900f, q = 1.1f, sweep = 0.4f)
            drive(1.6f)
        }.finish(-2f)
        Sfx.STOP_WARN -> Mix(0.26f).apply {
            for (start in floatArrayOf(0f, 0.12f)) {
                tone(start, 0.075f, 880f, wave = Wave.SQUARE, amp = 0.25f, attack = 0.003f)
                tone(start, 0.075f, 1760f, wave = Wave.TRI, amp = 0.08f, attack = 0.003f)
            }
            lowPass(3200f)
        }.finish(-8f)
        Sfx.GO -> Mix(0.42f).apply {
            for (note in intArrayOf(72, 76, 79, 84)) {
                tone(0f, 0.42f, hz(note), wave = Wave.SAW, amp = 0.18f, attack = 0.004f, decay = 0.16f)
            }
            noise(0f, 0.08f, 0.15f, hp = 4000f, decay = 0.03f)
            lowPass(7000f, 1400f, q = 1.4f, sweep = 0.22f)
        }.finish(-4f)
        Sfx.FAULT -> Mix(0.24f).apply {
            for (start in floatArrayOf(0f, 0.11f)) {
                tone(start, 0.09f, 180f, wave = Wave.SQUARE, amp = 0.3f, attack = 0.002f)
                tone(start, 0.09f, 191f, wave = Wave.SQUARE, amp = 0.3f, attack = 0.002f)
            }
            lowPass(1500f)
        }.finish(-6f)
        Sfx.MISS -> Mix(0.14f).apply {
            tone(0f, 0.14f, 170f, 90f, amp = 0.5f, decay = 0.06f, glide = 0.08f)
            noise(0f, 0.05f, 0.2f, lp = 700f, decay = 0.02f)
        }.finish(-9f)
        Sfx.FRENZY_START -> Mix(1.25f).apply {
            tone(0f, 0.58f, 220f, 880f, wave = Wave.SAW, amp = 0.22f, attack = 0.35f, glide = 0.55f)
            noise(0f, 0.58f, 0.22f, attack = 0.4f, bp0 = 400f, bp1 = 6000f, q = 2f)
            tone(0.55f, 0.3f, 130f, 40f, amp = 0.9f, attack = 0.002f, decay = 0.16f, glide = 0.06f)
            noise(0.55f, 0.7f, 0.3f, hp = 3000f, decay = 0.3f)
            for (note in intArrayOf(69, 73, 76, 81)) {
                tone(0.55f, 0.7f, hz(note), wave = Wave.SAW, amp = 0.12f, attack = 0.005f, decay = 0.4f)
            }
            lowPass(9000f)
        }.finish(-2f)
        Sfx.FRENZY_END -> Mix(0.45f).apply {
            noise(0f, 0.45f, 0.3f, decay = 0.25f, bp0 = 3000f, bp1 = 300f, q = 1.5f)
            tone(0f, 0.4f, 600f, 200f, wave = Wave.TRI, amp = 0.2f, decay = 0.2f)
        }.finish(-8f)
        Sfx.MEGA -> Mix(1.6f).apply {
            tone(0f, 0.6f, 180f, 1080f, wave = Wave.SAW, amp = 0.22f, attack = 0.4f, glide = 0.58f)
            noise(0f, 0.6f, 0.22f, attack = 0.45f, bp0 = 300f, bp1 = 8000f, q = 2f)
            tone(0.58f, 0.35f, 120f, 35f, amp = 1f, attack = 0.002f, decay = 0.2f, glide = 0.07f)
            noise(0.58f, 0.9f, 0.32f, hp = 2500f, decay = 0.4f)
            for (note in intArrayOf(64, 68, 71, 76, 80)) {
                tone(0.58f, 1f, hz(note), wave = Wave.SAW, amp = 0.1f, attack = 0.005f, decay = 0.55f)
            }
            sparkle(this, 0.7f, 12, 0.035f)
            lowPass(10_000f)
        }.finish(-1f)
        Sfx.RECORD -> Mix(1.3f).apply {
            val notes = intArrayOf(72, 76, 79)
            for ((i, n) in notes.withIndex()) {
                tone(i * 0.09f, 0.2f, hz(n), wave = Wave.TRI, amp = 0.3f, decay = 0.1f)
                tone(i * 0.09f, 0.2f, hz(n), wave = Wave.SQUARE, amp = 0.08f, decay = 0.08f)
            }
            for (n in intArrayOf(72, 76, 79, 84)) {
                tone(0.27f, 1.0f, hz(n), wave = Wave.TRI, amp = 0.22f, attack = 0.01f, decay = 0.55f, vibratoHz = 5.5f, vibratoDepth = 0.004f)
                tone(0.27f, 1.0f, hz(n), wave = Wave.SAW, amp = 0.05f, attack = 0.01f, decay = 0.4f)
            }
            sparkle(this, 0.3f, 10, 0.05f)
            lowPass(7000f)
        }.finish(-2f)
        Sfx.WIN -> Mix(1.0f).apply {
            val notes = intArrayOf(79, 84, 88, 91)
            for ((i, n) in notes.withIndex()) tone(i * 0.07f, 0.25f, hz(n), wave = Wave.TRI, amp = 0.28f, decay = 0.12f)
            for (n in intArrayOf(72, 76, 79, 84)) tone(0.28f, 0.72f, hz(n), wave = Wave.TRI, amp = 0.18f, attack = 0.01f, decay = 0.4f)
            lowPass(8000f)
        }.finish(-3f)
        Sfx.LOSE -> Mix(0.95f).apply {
            val notes = intArrayOf(76, 72, 69)
            for ((i, n) in notes.withIndex()) tone(i * 0.18f, 0.35f, hz(n), wave = Wave.TRI, amp = 0.28f, attack = 0.005f, decay = 0.2f)
            tone(0.36f, 0.59f, hz(45), amp = 0.3f, attack = 0.02f, decay = 0.35f)
            lowPass(3500f)
        }.finish(-8f)
        Sfx.BUY -> Mix(0.6f).apply {
            tone(0f, 0.07f, 988f, wave = Wave.SQUARE, amp = 0.2f)
            tone(0.06f, 0.2f, 1319f, wave = Wave.SQUARE, amp = 0.2f, decay = 0.1f)
            tone(0.05f, 0.3f, 400f, 1600f, wave = Wave.TRI, amp = 0.22f, attack = 0.02f, decay = 0.25f, glide = 0.25f)
            sparkle(this, 0.28f, 6, 0.04f)
            lowPass(6000f)
        }.finish(-5f)
        Sfx.UI_CLICK -> Mix(0.04f).apply {
            tone(0f, 0.04f, 1800f, 1400f, amp = 0.45f, decay = 0.012f, glide = 0.012f)
            noise(0f, 0.004f, 0.1f, hp = 5000f)
        }.finish(-11f)
        Sfx.UI_BACK -> Mix(0.05f).apply {
            tone(0f, 0.05f, 1100f, 750f, amp = 0.45f, decay = 0.016f, glide = 0.02f)
        }.finish(-11f)
        Sfx.COUNT_BEEP -> Mix(0.18f).apply {
            tone(0f, 0.18f, 880f, amp = 0.4f, attack = 0.003f, decay = 0.09f)
            tone(0f, 0.18f, 1760f, wave = Wave.TRI, amp = 0.08f, attack = 0.003f, decay = 0.05f)
        }.finish(-7f)
        Sfx.LEVEL_UP -> Mix(1.0f).apply {
            val notes = intArrayOf(72, 76, 79, 84, 88)
            for ((i, n) in notes.withIndex()) tone(i * 0.06f, 0.35f, hz(n), wave = Wave.TRI, amp = 0.25f, decay = 0.18f)
            tone(0.3f, 0.7f, 4186f, amp = 0.05f, attack = 0.05f, decay = 0.3f, vibratoHz = 9f, vibratoDepth = 0.01f)
            sparkle(this, 0.3f, 8, 0.05f)
            lowPass(8000f)
        }.finish(-3f)
        Sfx.ACHIEVEMENT -> Mix(1.0f).apply {
            for ((i, n) in intArrayOf(88, 92, 95).withIndex()) tone(i * 0.03f, 0.9f, hz(n), amp = 0.22f, attack = 0.004f, decay = 0.45f)
            tone(0f, 0.9f, hz(76), wave = Wave.TRI, amp = 0.15f, attack = 0.01f, decay = 0.5f)
            sparkle(this, 0.1f, 8, 0.06f)
        }.finish(-4f)
        Sfx.STAR_1 -> star(1568f)
        Sfx.STAR_2 -> star(1976f)
        Sfx.STAR_3 -> star(2349f)
        Sfx.TIME_BONUS -> Mix(0.22f).apply {
            tone(0f, 0.08f, 1200f, 1500f, amp = 0.35f, decay = 0.05f)
            tone(0.07f, 0.15f, 1500f, 1900f, amp = 0.35f, decay = 0.08f)
        }.finish(-7f)
        Sfx.SHIELD -> Mix(0.5f).apply {
            tone(0f, 0.5f, 520f, amp = 0.3f, decay = 0.35f)
            tone(0f, 0.4f, 1330f, amp = 0.25f, decay = 0.22f)
            tone(0f, 0.3f, 2100f, amp = 0.2f, decay = 0.15f)
            tone(0f, 0.2f, 3350f, amp = 0.1f, decay = 0.1f)
            noise(0f, 0.01f, 0.2f, hp = 4000f)
        }.finish(-5f)
        Sfx.ZONE_SPAWN -> Mix(0.14f).apply {
            tone(0f, 0.14f, 500f, 900f, amp = 0.3f, attack = 0.01f, decay = 0.05f, glide = 0.04f)
            noise(0f, 0.08f, 0.06f, bp0 = 2000f, attack = 0.02f, decay = 0.03f)
        }.finish(-13f)
        Sfx.ZONE_POP -> Mix(0.12f).apply {
            tone(0f, 0.12f, 700f, 1400f, amp = 0.35f, decay = 0.04f, glide = 0.03f)
            tone(0f, 0.08f, 2800f, wave = Wave.TRI, amp = 0.06f, decay = 0.02f)
        }.finish(-9f)
        Sfx.GOLDEN -> Mix(0.9f).apply {
            tone(0f, 0.075f, 988f, wave = Wave.SQUARE, amp = 0.2f)
            tone(0.07f, 0.3f, 1319f, wave = Wave.SQUARE, amp = 0.2f, decay = 0.12f)
            sparkle(this, 0.05f, 16, 0.03f)
            lowPass(7000f)
        }.finish(-3f)
        Sfx.REFLEX_READY -> Mix(0.24f).apply {
            tone(0f, 0.24f, 440f, amp = 0.35f, attack = 0.01f, decay = 0.12f)
            tone(0f, 0.2f, 880f, wave = Wave.TRI, amp = 0.08f, attack = 0.01f, decay = 0.08f)
        }.finish(-9f)
    }

    private fun tapLayers(m: Mix, base: Float) {
        m.tone(0f, 0.075f, base * 1.6f, base * 0.8f, amp = 0.55f, attack = 0.0015f, decay = 0.028f, glide = 0.025f)
        m.tone(0f, 0.04f, base * 2f, wave = Wave.TRI, amp = 0.1f, attack = 0.001f, decay = 0.012f)
        m.noise(0f, 0.006f, 0.22f, hp = 3000f, decay = 0.002f)
    }

    private fun tap(base: Float, wave: Wave): FloatArray = Mix(0.08f).apply {
        tapLayers(this, base)
        if (wave == Wave.TRI) tone(0f, 0.05f, base * 3f, wave = Wave.TRI, amp = 0.05f, decay = 0.01f)
    }.finish(-8f)

    private fun combo(level: Int): FloatArray = Mix(0.55f).apply {
        val root = 72 + level * 2
        val notes = intArrayOf(root, root + 4, root + 7, root + 12)
        for ((i, n) in notes.withIndex()) {
            val last = i == notes.lastIndex
            tone(i * 0.05f, if (last) 0.4f else 0.15f, hz(n), wave = Wave.TRI, amp = 0.28f, decay = if (last) 0.2f else 0.08f)
            tone(i * 0.05f, if (last) 0.4f else 0.15f, hz(n + 12), amp = 0.06f, decay = 0.06f)
        }
        echo(0.06f, 0.3f, 0.35f)
        lowPass(7000f)
    }.finish(-6f)

    private fun star(f: Float): FloatArray = Mix(0.4f).apply {
        tone(0f, 0.4f, f, amp = 0.35f, attack = 0.003f, decay = 0.22f)
        tone(0f, 0.3f, f * 2f, amp = 0.1f, attack = 0.003f, decay = 0.12f)
        tone(0.03f, 0.3f, f * 1.5f, amp = 0.1f, attack = 0.003f, decay = 0.12f)
        noise(0f, 0.12f, 0.05f, hp = 6000f, decay = 0.05f)
    }.finish(-5f)

    /** Cascade of short, high sine pings (deterministic pseudo-random pitches). */
    private fun sparkle(m: Mix, start: Float, count: Int, spacing: Float) {
        var seed = 0x2A
        for (i in 0 until count) {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            val f = 2200f + (seed % 3800)
            m.tone(start + i * spacing, 0.18f, f, amp = 0.07f, attack = 0.002f, decay = 0.06f)
        }
    }
}
