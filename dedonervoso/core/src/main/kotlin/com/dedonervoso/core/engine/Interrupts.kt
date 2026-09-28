package com.dedonervoso.core.engine

import com.dedonervoso.core.stage.StopConfig
import com.dedonervoso.core.util.Rng

/**
 * One planned interrupt. Times are *match time* (ms of play, excluding pauses/countdowns).
 *
 *  - STOP:      [warnAt, startAt) warning · [startAt, endAt) STOP active · endAt → "TAP!"
 *  - FAKE_STOP: [warnAt, startAt) warning · startAt → "GO!" bonus burst
 *  - REFLEX:    [startAt, endAt) READY/WAIT hold · endAt → "TAP!" (reaction measured)
 */
class Interrupt(
    val kind: InterruptKind,
    var warnAt: Long,
    var startAt: Long,
    var endAt: Long,
) {
    var warned = false
    var started = false
    var ended = false
    /** An unshielded tap happened while taps were forbidden. */
    var faulted = false
    /** A STOP shield absorbed this interrupt's first fault. */
    var shielded = false
    /** Already pushed back once by a frenzy. */
    var postponed = false

    val durationMs: Long get() = endAt - startAt

    fun shift(deltaMs: Long) {
        warnAt += deltaMs
        startAt += deltaMs
        endAt += deltaMs
    }
}

/**
 * Builds the interrupt timeline for a match (spec §41): count drawn from the stage range,
 * one interrupt per segment of the usable window with random placement inside it — so events
 * feel unpredictable yet never cluster — respecting minimum gaps and a quiet opening/closing.
 */
object InterruptPlanner {
    fun plan(config: StopConfig, durationMs: Long, rng: Rng): MutableList<Interrupt> {
        val count = rng.between(config.countMin, config.countMax)
        val result = ArrayList<Interrupt>(count)
        if (count == 0) return result
        val windowStart = GameBalance.INTERRUPT_EARLIEST_MS + config.warningMs
        val windowEnd = durationMs - GameBalance.INTERRUPT_END_MARGIN_MS - config.durationMaxMs
        if (windowEnd <= windowStart) return result
        val segment = (windowEnd - windowStart) / count
        var earliest = windowStart
        for (i in 0 until count) {
            val segStart = maxOf(windowStart + segment * i, earliest)
            val segEnd = windowStart + segment * (i + 1)
            if (segEnd <= segStart) continue
            val start = rng.betweenLong(segStart, segEnd)
            val roll = rng.nextFloat()
            val kind = when {
                roll < config.reflexChance -> InterruptKind.REFLEX
                roll < config.reflexChance + config.fakeChance -> InterruptKind.FAKE_STOP
                else -> InterruptKind.STOP
            }
            val interrupt = when (kind) {
                InterruptKind.STOP -> Interrupt(
                    kind, start - config.warningMs, start,
                    start + rng.betweenLong(config.durationMinMs, config.durationMaxMs),
                )
                InterruptKind.FAKE_STOP -> Interrupt(kind, start - config.warningMs, start, start)
                InterruptKind.REFLEX -> Interrupt(
                    kind, start, start,
                    start + GameBalance.REFLEX_READY_MS +
                        rng.betweenLong(GameBalance.REFLEX_WAIT_MIN_MS, GameBalance.REFLEX_WAIT_MAX_MS),
                )
            }
            result += interrupt
            earliest = interrupt.endAt + config.minGapMs + config.warningMs
        }
        return result
    }
}
