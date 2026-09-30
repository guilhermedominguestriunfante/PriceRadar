package com.dedonervoso.core.progression

import com.dedonervoso.core.engine.MatchResult
import kotlin.math.pow
import kotlin.math.roundToInt

/** Itemised coin reward shown on the result screen. */
class CoinBreakdown(
    val base: Int,
    val stars: Int,
    val firstClear: Int,
    val performance: Int,
    val collected: Int,
    val record: Int,
    val boss: Int,
    val boost: Int,
    /** Finishing the objective early: coins for the seconds left on the clock. */
    val speed: Int = 0,
) {
    val total: Int get() = base + stars + firstClear + performance + collected + record + boss + boost + speed
}

/**
 * Coin and XP formulas (spec §16, §18, §19). Coins are earned slowly on purpose (1.3): first
 * clears and first stars still pay well so the first upgrades come in a few matches, but
 * replaying a cleared stage pays half its base and every other source is modest, so upgrades and
 * (later) cosmetics are goals to work for. Finishing faster pays a small speed bonus.
 */
object Economy {
    const val STAR_COINS = 6
    const val RECORD_COINS = 10
    const val COIN_BOOST_PER_LEVEL = 0.06f
    const val DAILY_BASE_COINS = 50

    /** Seconds left per coin of speed bonus, and its cap. */
    private const val SPEED_SECONDS_PER_COIN = 4
    private const val SPEED_MAX = 10

    fun matchCoins(
        result: MatchResult,
        newStars: Int,
        firstClear: Boolean,
        newRecord: Boolean,
        coinBoostLevel: Int,
        /** The stage had been cleared before this match. */
        replay: Boolean = false,
    ): CoinBreakdown {
        val n = result.stageNumber.coerceAtLeast(1)
        val fullBase = if (result.won) 4 + n / 2 else 1 + n / 10
        val base = if (replay) (fullBase / 2).coerceAtLeast(1) else fullBase
        val stars = newStars * STAR_COINS * (if (result.isBoss) 2 else 1)
        val first = if (firstClear && result.won) 10 + n else 0
        val performance = minOf(15, result.maxCombo / 25) + result.touches / 100
        val speed = if (result.won && result.endedEarly) {
            (((result.limitMs - result.playedMs) / 1000L).toInt() / SPEED_SECONDS_PER_COIN).coerceIn(0, SPEED_MAX)
        } else {
            0
        }
        val collected = result.coinsCollected
        val record = if (newRecord) RECORD_COINS else 0
        val subtotal = base + stars + first + performance + collected + record + speed
        val boss = if (result.isBoss && result.won) (subtotal * 0.3f).roundToInt() else 0
        val boost = ((subtotal + boss) * COIN_BOOST_PER_LEVEL * coinBoostLevel).roundToInt()
        return CoinBreakdown(base, stars, first, performance, collected, record, boss, boost, speed)
    }

    /** The Arena pays little (it is about the ranking): a base, performance, what was collected, a record. */
    fun arenaCoins(result: MatchResult, newRecord: Boolean, coinBoostLevel: Int): CoinBreakdown {
        val base = 3
        val performance = minOf(15, result.maxCombo / 25) + result.touches / 100
        val record = if (newRecord) RECORD_COINS else 0
        val subtotal = base + performance + result.coinsCollected + record
        val boost = (subtotal * COIN_BOOST_PER_LEVEL * coinBoostLevel).roundToInt()
        return CoinBreakdown(base, 0, 0, performance, result.coinsCollected, record, 0, boost)
    }

    fun matchXp(result: MatchResult): Int {
        val n = result.stageNumber.coerceAtLeast(1)
        val secondsLeft = if (result.won && result.endedEarly) ((result.limitMs - result.playedMs) / 1000L).toInt() else 0
        return 10 + result.touches / 10 + result.maxCombo / 5 + result.perfects + secondsLeft / 2 +
            (if (result.won) 15 + n + result.stars * 5 else 0) +
            (if (result.isBoss && result.won) 50 else 0)
    }

    fun arenaXp(result: MatchResult): Int = 10 + result.touches / 10 + result.maxCombo / 5 + result.perfects

    /** XP needed to go from [level] to level + 1. */
    fun xpToNext(level: Int): Int = (100 + 45 * (level - 1).coerceAtLeast(0).toDouble().pow(1.35)).roundToInt()

    fun levelUpCoins(newLevel: Int): Int = 10 + 2 * newLevel

    class LevelInfo(val level: Int, val xpIntoLevel: Long, val xpForNext: Int) {
        val fraction: Float get() = if (xpForNext <= 0) 0f else (xpIntoLevel.toFloat() / xpForNext).coerceIn(0f, 1f)
    }

    fun levelFor(totalXp: Long): LevelInfo {
        var level = 1
        var remaining = totalXp.coerceAtLeast(0L)
        while (level < MAX_LEVEL) {
            val need = xpToNext(level)
            if (remaining < need) break
            remaining -= need
            level++
        }
        return LevelInfo(level, remaining, xpToNext(level))
    }

    const val MAX_LEVEL = 999
}
