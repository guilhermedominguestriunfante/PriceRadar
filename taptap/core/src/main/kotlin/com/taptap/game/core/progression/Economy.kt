package com.taptap.game.core.progression

import com.taptap.game.core.engine.MatchResult
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
) {
    val total: Int get() = base + stars + firstClear + performance + collected + record + boss + boost
}

/**
 * Coin and XP formulas (spec §16, §18, §19). Early matches pay generously (first clears, first
 * stars, first records) so the first minutes feel fast; afterwards income is steady while
 * upgrade prices grow geometrically, so there is always a next goal in reach.
 */
object Economy {
    const val STAR_COINS = 8
    const val RECORD_COINS = 25
    const val COIN_BOOST_PER_LEVEL = 0.10f
    const val DAILY_BASE_COINS = 120

    fun matchCoins(
        result: MatchResult,
        newStars: Int,
        firstClear: Boolean,
        newRecord: Boolean,
        coinBoostLevel: Int,
    ): CoinBreakdown {
        val n = result.stageNumber.coerceAtLeast(1)
        val base = if (result.won) 10 + n else 3 + n / 3
        val stars = newStars * STAR_COINS * (if (result.isBoss) 2 else 1)
        val first = if (firstClear && result.won) 20 + n * 2 else 0
        val performance = minOf(60, result.maxCombo / 10) + result.touches / 40
        val collected = result.coinsCollected
        val record = if (newRecord) RECORD_COINS else 0
        val subtotal = base + stars + first + performance + collected + record
        val boss = if (result.isBoss && result.won) subtotal / 2 else 0
        val boost = ((subtotal + boss) * COIN_BOOST_PER_LEVEL * coinBoostLevel).roundToInt()
        return CoinBreakdown(base, stars, first, performance, collected, record, boss, boost)
    }

    fun matchXp(result: MatchResult): Int {
        val n = result.stageNumber.coerceAtLeast(1)
        return 10 + result.touches / 10 + result.maxCombo / 5 + result.perfects +
            (if (result.won) 15 + n + result.stars * 5 else 0) +
            (if (result.isBoss && result.won) 50 else 0)
    }

    /** XP needed to go from [level] to level + 1. */
    fun xpToNext(level: Int): Int = (100 + 45 * (level - 1).coerceAtLeast(0).toDouble().pow(1.35)).roundToInt()

    fun levelUpCoins(newLevel: Int): Int = 20 + 5 * newLevel

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
