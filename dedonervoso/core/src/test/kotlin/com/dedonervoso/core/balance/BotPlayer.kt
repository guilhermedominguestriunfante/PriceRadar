package com.dedonervoso.core.balance

import com.dedonervoso.core.engine.GameBalance
import com.dedonervoso.core.engine.GameSession
import com.dedonervoso.core.engine.GameState
import com.dedonervoso.core.engine.Loadout
import com.dedonervoso.core.engine.MatchResult
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.util.Rng
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Skill profile of a simulated player. */
class BotProfile(
    val name: String,
    /** Mean physical taps per second while tapping freely. */
    val tps: Float,
    /** Mean/stdev time to react to a visual cue (STOP, GO, TAP!). */
    val reactionMs: Int,
    val reactionSdMs: Int,
    /** Probability of stopping already at the STOP warning (safer, loses some taps). */
    val cautious: Float,
    /** Probability that a tap is aimed at a visible zone. */
    val zoneFocus: Float,
    /** Aim error (arena units, gaussian sigma). */
    val aimError: Float,
) {
    companion object {
        val CASUAL = BotProfile("casual", 5.5f, 330, 70, 0.45f, 0.35f, 0.07f)
        val AVERAGE = BotProfile("average", 7.0f, 270, 55, 0.6f, 0.55f, 0.05f)
        val SKILLED = BotProfile("skilled", 9.5f, 225, 40, 0.75f, 0.75f, 0.035f)
    }
}

/**
 * Plays a full match against the real engine at 5 ms resolution, mimicking human limits:
 * finite tapping speed, reaction delays to STOP/TAP!/GO, imperfect aim at zones.
 */
object BotPlayer {
    fun play(stage: StageConfig, loadout: Loadout, p: BotProfile, seed: Long): MatchResult {
        val rng = Rng(seed * 31 + 7)
        val s = GameSession(stage, loadout, ARENA_H, seed)
        var t = 0L
        s.start(t)
        var nextTap = GameBalance.COUNTDOWN_MS + reaction(rng, p)
        var lastState = s.state
        var lastWarning = false
        // The bot keeps tapping until it has reacted to a cue, and waits a reaction before resuming.
        var tappingUntil = Long.MAX_VALUE
        var pausedUntil = 0L
        var lastX = 0.5f
        var lastY = ARENA_H / 2f
        while (s.state != GameState.FINISHED && t < 200_000) {
            t += STEP
            s.update(t)
            val state = s.state
            val warning = s.warningActive
            if (warning && !lastWarning && rng.chance(p.cautious)) {
                tappingUntil = minOf(tappingUntil, t + reaction(rng, p) / 2)
            }
            if (state == GameState.STOP && lastState != GameState.STOP) {
                tappingUntil = minOf(tappingUntil, t + reaction(rng, p))
            }
            if (lastState == GameState.STOP && state != GameState.STOP) {
                tappingUntil = Long.MAX_VALUE
                pausedUntil = t + reaction(rng, p)
            }
            if (!warning && lastWarning && state != GameState.STOP) {
                // FAKE STOP revealed (GO!): if the bot had stopped, it restarts after reacting.
                if (tappingUntil <= t) pausedUntil = t + reaction(rng, p)
                tappingUntil = Long.MAX_VALUE
            }
            lastState = state
            lastWarning = warning
            if (t >= nextTap) {
                var extra = 0L
                if (t < tappingUntil && t >= pausedUntil && state.isActive) {
                    val (x, y, aimed) = aim(s, rng, p)
                    s.tap(t, x, y, activePointers = 1)
                    if (aimed) {
                        // Moving the finger to a target costs time (Fitts-like).
                        val d = sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY))
                        extra = (40f + 260f * d).toLong()
                    }
                    lastX = x
                    lastY = y
                }
                val mean = 1000f / p.tps
                nextTap = t + extra + (mean * rng.range(0.75f, 1.25f)).toLong().coerceAtLeast(20L)
            }
        }
        return s.result ?: error("match did not finish")
    }

    private fun reaction(rng: Rng, p: BotProfile): Long =
        (p.reactionMs + gaussian(rng) * p.reactionSdMs).toLong().coerceAtLeast(140L)

    private fun aim(s: GameSession, rng: Rng, p: BotProfile): Triple<Float, Float, Boolean> {
        val at = s.activeTimeMs
        val target = s.zones.filter { it.active }.maxByOrNull { if (it.locked) 10f else it.type.multiplier }
        if (target != null && (target.locked || rng.chance(p.zoneFocus))) {
            val angle = rng.range(0f, 6.2831855f)
            val r = kotlin.math.abs(gaussian(rng)) * p.aimError
            return Triple(target.x(at) + cos(angle) * r, target.y(at) + sin(angle) * r, true)
        }
        return Triple(0.5f + rng.range(-0.05f, 0.05f), ARENA_H * 0.6f + rng.range(-0.05f, 0.05f), false)
    }

    private fun gaussian(rng: Rng): Float {
        // Box–Muller.
        val u = rng.nextFloat().coerceAtLeast(1e-6f)
        val v = rng.nextFloat()
        return (sqrt(-2.0 * kotlin.math.ln(u.toDouble())) * cos(2.0 * Math.PI * v)).toFloat()
    }

    private const val STEP = 5L
    const val ARENA_H = 1.4f
}
