package com.dedonervoso.core.progression

import com.dedonervoso.core.engine.PenaltyTier
import com.dedonervoso.core.engine.ZoneType
import com.dedonervoso.core.stage.FrenzyConfig
import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.stage.StageTimes
import com.dedonervoso.core.stage.StageType
import com.dedonervoso.core.stage.StopConfig
import com.dedonervoso.core.stage.ZoneConfig
import com.dedonervoso.core.util.Rng
import kotlin.math.max

/** Daily challenge templates (spec §52). */
enum class DailyTemplate { TAPS_NO_STOP_ERRORS, COMBO_RUN, ZONE_HUNT, PERFECT_AIM, SCORE_ATTACK, FRENZY_PARTY }

/**
 * A deterministic challenge per calendar day, generated locally from the date so it works
 * offline; an online source can replace [forDay] later.
 */
object DailyChallenge {

    fun templateFor(dayIndex: Long): DailyTemplate {
        val values = DailyTemplate.values()
        return values[Rng(Rng.mix(0xDA11L, dayIndex)).nextInt(values.size)]
    }

    fun forDay(dayIndex: Long, highestStage: Int): Pair<DailyTemplate, StageConfig> {
        val template = templateFor(dayIndex)
        // Difficulty follows the player (never below a gentle baseline).
        val level = max(8, highestStage)
        val tier = StageCatalog.stage(level)
        val stop = StopConfig(
            3, 4, 1_000, 2_000,
            warningMs = max(350L, tier.stop?.warningMs ?: 500L),
            graceMs = max(250L, tier.stop?.graceMs ?: 280L),
            minGapMs = 5_000,
        )
        val zones = ZoneConfig(
            spawnMinMs = 1_300, spawnMaxMs = 2_300, maxConcurrent = 2, lifeMinMs = 2_800, lifeMaxMs = 4_200,
            weights = ZoneConfig.weights(ZoneType.X2 to 8, ZoneType.X3 to 4, ZoneType.X5 to 1, ZoneType.COIN to 2, ZoneType.COMBO to 2),
            driftChance = if (level >= 21) 0.3f else 0f, goldenChance = 0.01f,
        )
        val expected = StageCatalog.expectedScore(level)
        val seed = Rng.mix(0xDA7L, dayIndex)
        fun cfg(type: StageType, target: Int, lives: Int = 0, useStop: Boolean = true, useZones: Boolean = true, fill: Float = 1f) =
            StageConfig(
                number = StageCatalog.DAILY, type = type, target = target,
                scoreTarget = if (type == StageType.SCORE || type == StageType.BOSS) target else (expected * 0.7f).toInt(),
                star2Score = (expected * 0.95f).toInt(), star3Score = (expected * 1.35f).toInt(),
                lives = lives, stop = if (useStop) stop else null, zones = if (useZones) zones else null,
                frenzy = FrenzyConfig(fillScale = fill), comboTimeoutMs = 1_500L, penaltyTier = PenaltyTier.MEDIUM,
                seed = seed, customTitle = template.name,
                endOnObjective = true,
                star2TimeMs = StageTimes.forType(type).star2Ms, star3TimeMs = StageTimes.forType(type).star3Ms,
            )
        val config = when (template) {
            DailyTemplate.TAPS_NO_STOP_ERRORS ->
                cfg(StageType.SPEED, StageCatalog.roundTo(StageCatalog.expectedTouches(level) * StageCatalog.expectedTapValue(level) * 0.78f, 50), lives = 1, useZones = false)
            DailyTemplate.COMBO_RUN -> cfg(StageType.COMBO, StageCatalog.roundTo(minOf(500f, 250f + level * 4f), 10))
            DailyTemplate.ZONE_HUNT -> cfg(StageType.PRECISION, StageCatalog.roundTo(minOf(110f, 60f + level), 5), useStop = false)
            DailyTemplate.PERFECT_AIM -> cfg(StageType.PERFECT, minOf(40, 15 + level / 3), useStop = false)
            DailyTemplate.SCORE_ATTACK -> cfg(StageType.SCORE, StageCatalog.roundTo(expected * 0.75f, 50))
            DailyTemplate.FRENZY_PARTY -> cfg(StageType.FRENZY, 3, fill = 1.5f)
        }
        return template to config
    }

    fun rewardCoins(playerLevel: Int, streak: Int): Int = Economy.DAILY_BASE_COINS + 4 * playerLevel + 10 * minOf(streak, 7)
}
