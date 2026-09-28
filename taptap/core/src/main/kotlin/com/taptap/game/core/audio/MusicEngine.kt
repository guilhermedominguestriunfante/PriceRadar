package com.taptap.game.core.audio

import kotlin.math.exp
import kotlin.math.sin

enum class MusicMode { MENU, GAME, BOSS, RESULT }

/**
 * Real-time adaptive synthwave soundtrack (spec §33). A 16-step sequencer drives a small synth
 * (kick, snare, hats, side-chained bass, arpeggio, glide pad, lead with echo). Gameplay steers
 * it through volatile controls written from the UI thread:
 *  - [intensity] 0..4 adds layers as the combo grows,
 *  - [frenzy] speeds the tempo up and lifts the arpeggio an octave,
 *  - [stopped] muffles and ducks the music while STOP holds,
 *  - [mode] swaps progression/groove (menu, game, boss, result) at the next bar.
 *
 * [render] is called from the audio thread and never allocates.
 */
class MusicEngine(val sampleRate: Int) {
    @Volatile var mode: MusicMode = MusicMode.MENU
    @Volatile var intensity: Int = 0
    @Volatile var frenzy: Boolean = false
    @Volatile var stopped: Boolean = false
    @Volatile var volume: Float = 1f

    private val dt = 1f / sampleRate

    // ---- sequencer
    private var activeMode = MusicMode.MENU
    private var samplesToNextStep = 0.0
    private var stepSamples = 0.0
    private var step = 0
    private var bar = 0
    private var chordIndex = 0
    private var echoSamples = 1

    // ---- drums
    private val noise = Noise(0xBEEF)
    private var kickT = 10f
    private var kickPhase = 0f
    private var kickVel = 0f
    private var snareT = 10f
    private var snareVel = 0f
    private var snarePhase = 0f
    private val snareBand = Svf(sampleRate).also { it.set(1800f, 0.9f) }
    private var hatT = 10f
    private var hatVel = 0f
    private var hatDecay = 0.03f
    private val hatHp1 = OnePole(sampleRate).also { it.setCutoff(7000f) }
    private val hatHp2 = OnePole(sampleRate).also { it.setCutoff(7000f) }

    // ---- bass
    private var bassFreq = 55f
    private var bassPhase = 0f
    private var bassSubPhase = 0f
    private var bassT = 10f
    private var bassGate = false
    private var bassAmp = 0f
    private val bassFilter = Svf(sampleRate)

    // ---- arpeggio: two alternating voices, each with its own pan
    private val arpPhase = FloatArray(2)
    private val arpFreq = FloatArray(2) { 440f }
    private val arpT = FloatArray(2) { 10f }
    private val arpVel = FloatArray(2)
    private val arpFilterL = Svf(sampleRate)
    private val arpFilterR = Svf(sampleRate)
    private var arpVoice = 0
    private var arpCounter = 0

    // ---- pad: 4 voices gliding between chord tones, 2 detuned saws each
    private val padFreq = FloatArray(PAD_VOICES) { 220f }
    private val padTarget = FloatArray(PAD_VOICES) { 220f }
    private val padPhaseA = FloatArray(PAD_VOICES)
    private val padPhaseB = FloatArray(PAD_VOICES)
    private val padFilter = Svf(sampleRate)
    private var padLevel = 0f
    private var lfoPhase = 0f

    // ---- lead
    private var leadFreq = 440f
    private var leadTargetFreq = 440f
    private var leadPhase = 0f
    private var leadT = 10f
    private var leadGate = false
    private var leadAmp = 0f
    private val leadFilter = Svf(sampleRate).also { it.set(2600f, 0.9f) }

    // ---- effects & master
    private val echoL = Delay(sampleRate)
    private val echoR = Delay(sampleRate)
    private val masterL = Svf(sampleRate)
    private val masterR = Svf(sampleRate)
    private var cutoff = 18_000f
    private var gain = 0f
    private val cutoffCoef = Dsp.smoothCoef(60f, sampleRate)
    private val gainCoef = Dsp.smoothCoef(40f, sampleRate)
    private val slowCoef = Dsp.smoothCoef(300f, sampleRate)
    private val bassAttack = Dsp.smoothCoef(1.5f, sampleRate)
    private val bassRelease = Dsp.smoothCoef(6f, sampleRate)
    private val leadAttack = Dsp.smoothCoef(4f, sampleRate)
    private val leadRelease = Dsp.smoothCoef(35f, sampleRate)
    private val glideCoef = Dsp.smoothCoef(25f, sampleRate)
    private val padGlideCoef = Dsp.smoothCoef(90f, sampleRate)
    private var intensityLevel = 0f
    private var outL = 0f
    private var outR = 0f

    /** Renders [frames] interleaved stereo frames of 16-bit PCM into [out]. */
    fun render(out: ShortArray, frames: Int) {
        var o = 0
        for (i in 0 until frames) {
            tick()
            out[o++] = (Dsp.softClip(outL) * 32_000f).toInt().toShort()
            out[o++] = (Dsp.softClip(outR) * 32_000f).toInt().toShort()
        }
    }

    /** Float variant of [render] (tests / analysis). */
    fun renderFloat(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            tick()
            left[i] = Dsp.softClip(outL)
            right[i] = Dsp.softClip(outR)
        }
    }

    private fun tick() {
        if (samplesToNextStep <= 0.0) {
            advanceStep()
            samplesToNextStep += stepSamples
        }
        samplesToNextStep -= 1.0
        renderSample()
        if (outL.isNaN() || outR.isNaN()) recover()
    }

    /** Numerical safety net: never let a filter blow up the stream. */
    private fun recover() {
        outL = 0f
        outR = 0f
        for (f in arrayOf(bassFilter, arpFilterL, arpFilterR, padFilter, leadFilter, masterL, masterR, snareBand)) f.reset()
        echoL.clear()
        echoR.clear()
    }

    private val isGame: Boolean get() = activeMode == MusicMode.GAME || activeMode == MusicMode.BOSS

    private fun bpm(): Double {
        val base = when (activeMode) {
            MusicMode.MENU -> 100.0
            MusicMode.RESULT -> 96.0
            MusicMode.GAME -> 124.0
            MusicMode.BOSS -> 132.0
        }
        return if (frenzy && isGame) base * 1.15 else base
    }

    private fun advanceStep() {
        if (step == 0) {
            // Bar boundary: switch mode and move through the progression.
            if (mode != activeMode) {
                activeMode = mode
                bar = 0
            }
            chordIndex = bar % 4
            val chord = chords()[chordIndex]
            for (v in 0 until PAD_VOICES) padTarget[v] = Dsp.midiToHz(chord[v].toFloat())
        }
        stepSamples = sampleRate * 60.0 / bpm() / 4.0
        echoSamples = (stepSamples * 3).toInt().coerceIn(1, sampleRate - 2)
        val lvl = if (frenzy) 5 else intensity.coerceIn(0, 4)
        val root = roots()[chordIndex]
        val chord = chords()[chordIndex]

        when (activeMode) {
            MusicMode.MENU -> {
                if (step == 0 || step == 10) kick(0.5f)
                if (step % 4 == 2) hat(0.3f, 0.03f)
                if (step == 0) bass(root)
                if (step == 12) bassGate = false
                if (step % 2 == 0) arp(chord, 0.55f, 0)
                leadGate = false
            }
            MusicMode.RESULT -> {
                if (step == 0) bass(root)
                if (step == 14) bassGate = false
                if (step % 4 == 0) arp(chord, 0.5f, 12)
                leadGate = false
            }
            MusicMode.GAME, MusicMode.BOSS -> {
                if (step % 4 == 0) kick(1f)
                if (activeMode == MusicMode.BOSS && lvl >= 3 && step == 14) kick(0.7f)
                if (lvl >= 4 && step == 7) kick(0.45f)
                if (lvl >= 1 && (step == 4 || step == 12)) snare(1f)
                if (frenzy && bar % 2 == 1 && step >= 13) snare(0.55f)
                when {
                    step % 4 == 2 -> hat(if (lvl >= 2) 0.8f else 0.6f, if (lvl >= 2) 0.16f else 0.03f)
                    lvl >= 3 || (step % 2 == 0 && lvl >= 1) -> hat(if (step % 2 == 0) 0.45f else 0.3f, 0.025f)
                }
                // Octave-bouncing eighth-note bass, gated on the off sixteenths.
                if (step % 2 == 0) bass(if (step % 4 == 0) root else root + 12) else bassGate = false
                if (lvl >= 2 || (lvl >= 1 && step % 2 == 0)) arp(chord, if (lvl >= 2) 0.9f else 0.7f, if (frenzy) 12 else 0)
                if (lvl >= 3) lead(MELODY[bar % MELODY.size][step], if (frenzy) 12 else 0) else leadGate = false
            }
        }
        step++
        if (step == 16) {
            step = 0
            bar++
        }
    }

    private fun chords() = if (activeMode == MusicMode.BOSS) BOSS_CHORDS else MAIN_CHORDS
    private fun roots() = if (activeMode == MusicMode.BOSS) BOSS_ROOTS else MAIN_ROOTS

    private fun kick(v: Float) {
        kickT = 0f
        kickVel = v
    }

    private fun snare(v: Float) {
        snareT = 0f
        snareVel = v
    }

    private fun hat(v: Float, decay: Float) {
        hatT = 0f
        hatVel = v
        hatDecay = decay
    }

    private fun bass(midi: Int) {
        bassFreq = Dsp.midiToHz(midi.toFloat())
        bassT = 0f
        bassGate = true
    }

    private fun arp(chord: IntArray, velocity: Float, octave: Int) {
        val order = ARP_ORDER[arpCounter % ARP_ORDER.size]
        arpCounter++
        val note = chord[order % chord.size] + 12 + octave + (if (order >= chord.size) 12 else 0)
        val v = arpVoice
        arpVoice = 1 - arpVoice
        arpFreq[v] = Dsp.midiToHz(note.toFloat())
        arpT[v] = 0f
        arpVel[v] = velocity
    }

    private fun lead(n: Int, octave: Int) {
        when {
            n > 0 -> {
                leadTargetFreq = Dsp.midiToHz((n + octave).toFloat())
                if (!leadGate) leadFreq = leadTargetFreq
                leadGate = true
                leadT = 0f
            }
            n == 0 -> leadGate = false
        }
    }

    private fun renderSample() {
        val game = isGame
        intensityLevel += slowCoef * ((if (frenzy) 5f else intensity.toFloat()) - intensityLevel)

        // ---- kick (pitch-swept sine) and its envelope for side-chaining
        var drums = 0f
        var kickEnv = 0f
        if (kickT < 0.5f) {
            kickPhase += (45f + 85f * exp(-kickT / 0.03f)) * dt
            if (kickPhase >= 1f) kickPhase -= 1f
            kickEnv = exp(-kickT / 0.17f) * kickVel
            drums += Dsp.sine(kickPhase) * kickEnv * 0.85f
            kickT += dt
        }
        // ---- snare (band-passed noise + body)
        if (snareT < 0.4f) {
            snareBand.process(noise.next())
            snarePhase += 185f * dt
            if (snarePhase >= 1f) snarePhase -= 1f
            drums += (snareBand.band * 1.4f * exp(-snareT / 0.11f) + Dsp.sine(snarePhase) * 0.5f * exp(-snareT / 0.05f)) * snareVel * 0.36f
            snareT += dt
        }
        // ---- hats (double high-passed noise)
        var hatL = 0f
        var hatR = 0f
        if (hatT < 0.5f) {
            val h = hatHp2.highPass(hatHp1.highPass(noise.next())) * exp(-hatT / hatDecay) * hatVel * 0.2f
            hatL = h * 0.85f
            hatR = h * 1.15f
            hatT += dt
        }

        // ---- bass: saw (square in boss mode) + sub, filter envelope, ducked by the kick
        bassAmp += ((if (bassGate) 1f else 0f) - bassAmp) * (if (bassGate) bassAttack else bassRelease)
        val bdt = bassFreq * dt
        bassPhase += bdt
        if (bassPhase >= 1f) bassPhase -= 1f
        bassSubPhase += bdt * 0.5f
        if (bassSubPhase >= 1f) bassSubPhase -= 1f
        val raw = if (activeMode == MusicMode.BOSS) Dsp.square(bassPhase, bdt, 0.35f) * 0.6f else Dsp.saw(bassPhase, bdt) * 0.7f
        bassFilter.set(260f + 1100f * exp(-bassT / 0.12f) + intensityLevel * 140f, 1.3f)
        val bass = (bassFilter.process(raw) + Dsp.sine(bassSubPhase) * 0.35f) * bassAmp *
            (if (game) 0.34f else 0.24f) * (1f - 0.55f * kickEnv)
        bassT += dt

        // ---- arpeggio
        var arpL = 0f
        var arpR = 0f
        val arpDecay = if (game) 0.07f else 0.16f
        for (v in 0..1) {
            val t = arpT[v]
            if (t < 0.7f) {
                val fdt = arpFreq[v] * dt
                arpPhase[v] += fdt
                if (arpPhase[v] >= 1f) arpPhase[v] -= 1f
                val s = Dsp.square(arpPhase[v], fdt, 0.28f) * exp(-t / arpDecay) * arpVel[v]
                val pan = if (v == 0) -0.4f else 0.4f
                arpL += s * (1f - pan)
                arpR += s * (1f + pan)
                arpT[v] = t + dt
            }
        }
        val arpCut = if (frenzy) 5200f else 2600f + intensityLevel * 300f
        arpFilterL.set(arpCut, 0.8f)
        arpFilterR.set(arpCut, 0.8f)
        val arpGain = if (game) 0.06f else 0.05f
        arpL = arpFilterL.process(arpL) * arpGain
        arpR = arpFilterR.process(arpR) * arpGain

        // ---- pad
        lfoPhase += 0.07f * dt
        if (lfoPhase >= 1f) lfoPhase -= 1f
        var pad = 0f
        for (v in 0 until PAD_VOICES) {
            padFreq[v] += (padTarget[v] - padFreq[v]) * padGlideCoef
            val fa = padFreq[v] * 1.003f * dt
            val fb = padFreq[v] * 0.997f * dt
            padPhaseA[v] += fa
            if (padPhaseA[v] >= 1f) padPhaseA[v] -= 1f
            padPhaseB[v] += fb
            if (padPhaseB[v] >= 1f) padPhaseB[v] -= 1f
            pad += Dsp.saw(padPhaseA[v], fa) + Dsp.saw(padPhaseB[v], fb)
        }
        padFilter.set(700f + 500f * Dsp.sine(lfoPhase) + (if (game) intensityLevel * 120f else 0f), 0.7f)
        val padTargetLevel = if (game) 0.045f - intensityLevel * 0.004f else 0.07f
        padLevel += (padTargetLevel - padLevel) * slowCoef
        pad = padFilter.process(pad) * padLevel

        // ---- lead
        var lead = 0f
        leadAmp += ((if (leadGate) 1f else 0f) - leadAmp) * (if (leadGate) leadAttack else leadRelease)
        if (leadAmp > 0.0005f) {
            leadFreq += (leadTargetFreq - leadFreq) * glideCoef
            val vib = if (leadT > 0.15f) 1f + 0.004f * sin(Dsp.TWO_PI * 5.5f * leadT) else 1f
            val ldt = leadFreq * vib * dt
            leadPhase += ldt
            if (leadPhase >= 1f) leadPhase -= 1f
            lead = leadFilter.process(Dsp.saw(leadPhase, ldt) * 0.6f + Dsp.square(leadPhase, ldt) * 0.25f) * leadAmp * 0.13f
            leadT += dt
        }

        // ---- ping-pong-ish echo on arp + lead
        val wetIn = (arpL + arpR) * 0.5f + lead
        val echoOutL = echoL.process(wetIn, echoSamples, 0.35f, 0.3f) - wetIn
        val echoOutR = echoR.process(wetIn, echoSamples + sampleRate / 97, 0.35f, 0.3f) - wetIn

        var left = drums + hatL + bass + arpL + pad + lead + echoOutL
        var right = drums + hatR + bass + arpR + pad + lead + echoOutR

        // ---- master: STOP muffle/duck, volume
        cutoff += ((if (stopped) 380f else 18_000f) - cutoff) * cutoffCoef
        gain += (volume * (if (stopped) 0.38f else 1f) - gain) * gainCoef
        masterL.set(cutoff, 0.75f)
        masterR.set(cutoff, 0.75f)
        left = masterL.process(left) * gain
        right = masterR.process(right) * gain
        outL = left * 0.9f
        outR = right * 0.9f
    }

    companion object {
        private const val PAD_VOICES = 4

        // A minor: Am7 – Fmaj7 – C – G6 (i – VI – III – VII), voice-led for the gliding pad.
        private val MAIN_CHORDS = arrayOf(
            intArrayOf(57, 60, 64, 67),
            intArrayOf(57, 60, 64, 65),
            intArrayOf(55, 60, 64, 67),
            intArrayOf(55, 59, 62, 64),
        )
        private val MAIN_ROOTS = intArrayOf(33, 29, 36, 31)

        // Boss: Am – Dm – F – E (harmonic minor tension).
        private val BOSS_CHORDS = arrayOf(
            intArrayOf(57, 60, 64, 69),
            intArrayOf(57, 62, 65, 69),
            intArrayOf(57, 60, 65, 69),
            intArrayOf(56, 59, 64, 68),
        )
        private val BOSS_ROOTS = intArrayOf(33, 38, 29, 28)

        private val ARP_ORDER = intArrayOf(0, 1, 2, 3, 4, 3, 2, 1)

        // 8-bar lead melody, 16 steps per bar: >0 = MIDI note, 0 = rest, -1 = hold.
        private val MELODY = arrayOf(
            intArrayOf(76, -1, 74, 72, -1, 74, -1, 76, -1, -1, 79, -1, 76, -1, -1, 0),
            intArrayOf(77, -1, 76, -1, 72, -1, -1, 0, 69, -1, 72, -1, 74, -1, -1, 0),
            intArrayOf(76, -1, 79, -1, 84, -1, -1, 83, -1, 79, -1, -1, 76, -1, -1, 0),
            intArrayOf(74, -1, 76, -1, 74, -1, 71, -1, 67, -1, -1, 0, 71, -1, 74, -1),
            intArrayOf(76, -1, -1, 79, -1, 81, -1, 79, 76, -1, 74, -1, 76, -1, -1, 0),
            intArrayOf(77, -1, 76, -1, 74, -1, 72, -1, 74, -1, 76, -1, 72, -1, -1, 0),
            intArrayOf(79, -1, 76, -1, 72, -1, 76, -1, 79, -1, 84, -1, 83, -1, 79, -1),
            intArrayOf(81, -1, -1, -1, 79, -1, 76, -1, 74, -1, 71, -1, 74, -1, -1, 0),
        )
    }
}
