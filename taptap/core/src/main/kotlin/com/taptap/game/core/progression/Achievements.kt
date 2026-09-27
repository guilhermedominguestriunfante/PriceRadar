package com.taptap.game.core.progression

/** What an achievement measures; used to show progress bars. */
class AchievementProgress(val current: Long, val target: Long)

/**
 * Extensible achievement definition (spec §23): add an entry to [Achievements.ALL] with an id,
 * a reward and a progress function; texts come from i18n by id.
 */
class AchievementDef(
    val id: String,
    val coins: Int,
    val icon: String,
    val progress: (AchievementContext) -> AchievementProgress,
) {
    fun isUnlocked(ctx: AchievementContext): Boolean = progress(ctx).let { it.current >= it.target }
}

/** Everything achievements may look at. */
class AchievementContext(
    val stats: Stats,
    val playerLevel: Int,
    val highestCleared: Int,
    val totalStars: Int,
    val dailyStreak: Int,
)

object Achievements {
    private fun ach(id: String, coins: Int, icon: String, target: Long, value: (AchievementContext) -> Long) =
        AchievementDef(id, coins, icon) { AchievementProgress(minOf(value(it), target), target) }

    val ALL: List<AchievementDef> = listOf(
        ach("first_tap", 10, "tap", 1) { it.stats.totalTouches },
        ach("first_win", 15, "trophy", 1) { it.stats.wins.toLong() },
        ach("tap_machine", 30, "tap", 1_000) { it.stats.totalTaps },
        ach("tap_master", 100, "tap", 10_000) { it.stats.totalTaps },
        ach("tap_god", 500, "tap", 100_000) { it.stats.totalTaps },
        ach("dont_blink", 50, "stop", 10) { it.stats.stopsSurvived },
        ach("flawless", 60, "shield", 1) { it.stats.flawlessStages.toLong() },
        ach("perfect_machine", 100, "target", 100) { it.stats.perfects },
        ach("unstoppable", 150, "combo", 500) { it.stats.maxCombo.toLong() },
        ach("speed_demon", 100, "bolt", 12) { it.stats.maxTps.toLong() },
        ach("frenzy_first", 20, "flame", 1) { it.stats.frenzies },
        ach("mega", 100, "flame", 1) { it.stats.megaFrenzies },
        ach("golden_touch", 80, "coin", 1) { it.stats.goldenHits.toLong() },
        ach("lightning", 80, "bolt", 1) { ctx -> if (ctx.stats.reflexBestMs in 0..250) 1L else 0L },
        ach("zone_hunter", 100, "target", 500) { it.stats.zoneHits },
        ach("boss_slayer", 100, "skull", 1) { it.stats.bossesDefeated.toLong() },
        ach("boss_hunter", 300, "skull", 5) { it.stats.bossesDefeated.toLong() },
        ach("star_collector", 100, "star", 30) { it.totalStars.toLong() },
        ach("stage_20", 100, "flag", 20) { it.highestCleared.toLong() },
        ach("stage_50", 300, "flag", 50) { it.highestCleared.toLong() },
        ach("level_10", 100, "level", 10) { it.playerLevel.toLong() },
        ach("level_25", 300, "level", 25) { it.playerLevel.toLong() },
        ach("marathon", 100, "clock", 50) { it.stats.matches.toLong() },
        ach("rich", 100, "coin", 10_000) { it.stats.coinsEarned },
        ach("daily_3", 80, "calendar", 3) { it.dailyStreak.toLong() },
    )

    private val byId = ALL.associateBy { it.id }

    fun def(id: String): AchievementDef? = byId[id]

    /** Definitions satisfied by [ctx] and not yet in [unlocked]. */
    fun newlyUnlocked(ctx: AchievementContext, unlocked: Set<String>): List<AchievementDef> =
        ALL.filter { it.id !in unlocked && it.isUnlocked(ctx) }
}
