package com.taptap.game.core.progression

import com.taptap.game.core.engine.GameBalance
import com.taptap.game.core.engine.MatchResult

/**
 * Basic local anti-cheat (spec §37): flags results that are physically impossible or internally
 * inconsistent. Flagged results still count for the player (no punishment); they are only
 * marked in the ranking. Deliberately lenient to never hurt real players.
 */
object ResultValidator {
    /** Upper bound of points a single tap can produce. */
    private fun maxPointsPerTap(tapValue: Int): Float {
        val combo = GameBalance.COMBO_MULTIPLIERS.last()
        val zone = 8f * 1.5f // critical zone at max zone boost
        val frenzy = GameBalance.MEGA_FRENZY_MULTIPLIER + 1.25f
        val go = GameBalance.FAKE_STOP_MULTIPLIER
        val perfect = GameBalance.PERFECT_BONUS_TAPS * 2f * combo
        return tapValue * (combo * zone * frenzy * go + perfect)
    }

    fun isSuspicious(r: MatchResult): Boolean {
        val seconds = r.playedMs / 1000f
        if (r.playedMs <= 0 && r.touches > 0) return true
        if (r.maxTps > GameBalance.MAX_HUMAN_TPS + 0.5f) return true
        if (r.touches > (seconds + 1f) * GameBalance.MAX_HUMAN_TPS + GameBalance.TAP_BUCKET_CAPACITY) return true
        if (r.taps > r.touches.toLong() * r.tapValue) return true
        if (r.maxCombo > r.touches * (1 + GameBalance.COMBO_ZONE_EXTRA + GameBalance.PERFECT_COMBO_EXTRA)) return true
        if (r.score > r.touches * maxPointsPerTap(r.tapValue) + 50_000) return true
        if (r.droppedTaps > GameBalance.SUSPICIOUS_DROPPED_TAPS) return true
        if (r.badTimestamps > 10) return true
        if (r.playedMs > r.timeBonusMs + GameBalance.MATCH_DURATION_MS + 1_000) return true
        return false
    }
}
