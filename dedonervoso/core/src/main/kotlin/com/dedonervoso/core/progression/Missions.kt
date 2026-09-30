package com.dedonervoso.core.progression

import com.dedonervoso.core.engine.MatchResult
import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.util.Rng
import kotlin.math.max
import kotlin.math.roundToInt

/** Mission families (spec §53). Enum names are persisted. */
enum class MissionKind(
    /** Progress accumulates across matches (true) or keeps the best single-match value (false). */
    val cumulative: Boolean,
    /** Stage the player must have reached for the mechanic to exist. */
    val minStage: Int,
) {
    TAPS(true, 1),
    PLAY_MATCHES(true, 1),
    WIN_STAGES(true, 1),
    REACH_COMBO(false, 1),
    SCORE_IN_MATCH(false, 1),
    REACH_TPS(false, 1),
    EARN_STARS(true, 1),
    FRENZIES(true, 4),
    SURVIVE_STOPS(true, 6),
    ZONE_HITS(true, 11),
    PERFECTS(true, 13),
}

class Mission(
    val id: Int,
    val kind: MissionKind,
    val target: Int,
    var progress: Int,
    val rewardCoins: Int,
    val rewardXp: Int,
    var claimed: Boolean = false,
) {
    val done: Boolean get() = progress >= target
    val fraction: Float get() = if (target <= 0) 1f else (progress.toFloat() / target).coerceIn(0f, 1f)
}

object Missions {
    const val ACTIVE = 3

    /** Creates a mission of a kind not in [exclude], sized for the player's progress. */
    fun generate(id: Int, rng: Rng, playerLevel: Int, highestStage: Int, exclude: Set<MissionKind>): Mission {
        val candidates = MissionKind.values().filter { it.minStage <= highestStage && it !in exclude }
        val kind = if (candidates.isEmpty()) MissionKind.TAPS else candidates[rng.nextInt(candidates.size)]
        val s = 1f + (playerLevel - 1) * 0.12f
        val target = when (kind) {
            MissionKind.TAPS -> round(300 * s, 50)
            MissionKind.PLAY_MATCHES -> 3 + playerLevel / 8
            MissionKind.WIN_STAGES -> 2 + playerLevel / 10
            MissionKind.REACH_COMBO -> round(minOf(400f, 40 * s), 10)
            // Stages end at their objective now: stay within what a stage of the player's level yields.
            MissionKind.SCORE_IN_MATCH -> round(minOf(700 * s * s, StageCatalog.expectedScore(highestStage) * 0.6f), 100)
            MissionKind.REACH_TPS -> minOf(14, 6 + playerLevel / 5)
            MissionKind.EARN_STARS -> 3 + playerLevel / 10
            MissionKind.FRENZIES -> 2 + playerLevel / 10
            MissionKind.SURVIVE_STOPS -> round(5 * s, 1)
            MissionKind.ZONE_HITS -> round(30 * s, 5)
            MissionKind.PERFECTS -> round(8 * s, 1)
        }
        val coins = round(20f + 5f * playerLevel + rng.nextInt(10), 5)
        val xp = round(30f + 8f * playerLevel, 5)
        return Mission(id, kind, max(1, target), 0, coins, xp)
    }

    /** Adds a match to every active, unclaimed mission. Returns missions completed by it. */
    fun apply(missions: List<Mission>, r: MatchResult, starsGained: Int): List<Mission> {
        val completed = ArrayList<Mission>()
        for (m in missions) {
            if (m.claimed || m.done) continue
            val value = when (m.kind) {
                MissionKind.TAPS -> r.taps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                MissionKind.PLAY_MATCHES -> 1
                MissionKind.WIN_STAGES -> if (r.won) 1 else 0
                MissionKind.REACH_COMBO -> r.maxCombo
                MissionKind.SCORE_IN_MATCH -> r.score.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                MissionKind.REACH_TPS -> r.maxTps.toInt()
                MissionKind.EARN_STARS -> starsGained
                MissionKind.FRENZIES -> r.frenzies
                MissionKind.SURVIVE_STOPS -> r.stopsSurvived
                MissionKind.ZONE_HITS -> r.zoneHits
                MissionKind.PERFECTS -> r.perfects
            }
            m.progress = if (m.kind.cumulative) m.progress + value else max(m.progress, value)
            if (m.done) completed += m
        }
        return completed
    }

    private fun round(v: Float, step: Int): Int = max(step, (v / step).roundToInt() * step)
}
