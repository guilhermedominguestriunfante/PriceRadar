package com.dedonervoso.core.engine

import com.dedonervoso.core.util.Rng

/**
 * Sabotage items of live duels. An orb carrying one falls during the match; sustaining
 * [requiredTps] taps per second for [holdMs] while it falls captures it, and the player can then
 * throw it at the opponent. The stronger the effect, the harder the capture.
 */
enum class DuelItem(val requiredTps: Float, val holdMs: Long) {
    /** The opponent's taps are worth half for [GameBalance.DUEL_SLOW_MS]. */
    SLOW(9f, 3_000L),

    /** The opponent's clock loses [GameBalance.DUEL_CLOCK_PENALTY_MS]. */
    CLOCK(11f, 4_000L),

    /** A surprise STOP on the opponent's screen. */
    STOP(13f, 5_000L),
    ;

    companion object {
        /** Decodes the name sent over the network (unknown names are ignored). */
        fun of(name: String): DuelItem? = values().firstOrNull { it.name == name }
    }
}

enum class OrbState { WAITING, FALLING, CAPTURED, MISSED }

/** One orb of a duel: it falls from [spawnAtMs] to [expireAtMs] (match time) at column [x] (0..1). */
class DuelOrb(val index: Int, val item: DuelItem, val x: Float, val spawnAtMs: Long, val expireAtMs: Long) {
    var state: OrbState = OrbState.WAITING
        internal set

    /** Capture progress 0..1: grows while the tap rate is high enough, drains otherwise. */
    var progress: Float = 0f
        internal set

    /** How far it has fallen (0 = top, 1 = gone) at match time [matchMs]. */
    fun fall(matchMs: Long): Float = ((matchMs - spawnAtMs).toFloat() / (expireAtMs - spawnAtMs)).coerceIn(0f, 1f)
}

/**
 * The orb schedule of a duel. Both players derive it from the room's seed, so they get the same
 * items at the same times; each item kind appears at least once.
 */
object DuelPlan {
    fun orbs(rng: Rng, durationMs: Long): List<DuelOrb> {
        val kinds = ArrayList<DuelItem>()
        kinds += DuelItem.values()
        while (kinds.size < GameBalance.DUEL_ORB_TIMES_MS.size) kinds += if (rng.chance(0.5f)) DuelItem.SLOW else DuelItem.CLOCK
        for (i in kinds.lastIndex downTo 1) {
            val j = rng.between(0, i)
            val t = kinds[i]
            kinds[i] = kinds[j]
            kinds[j] = t
        }
        return GameBalance.DUEL_ORB_TIMES_MS.withIndex()
            .filter { it.value + GameBalance.DUEL_ORB_LIFETIME_MS <= durationMs }
            .map { (i, at) -> DuelOrb(i, kinds[i], rng.range(0.2f, 0.8f), at, at + GameBalance.DUEL_ORB_LIFETIME_MS) }
    }
}
