package com.taptap.game.core.save

import com.taptap.game.core.progression.Mission
import com.taptap.game.core.progression.RankEntry
import com.taptap.game.core.progression.Stats
import com.taptap.game.core.progression.UpgradeId
import com.taptap.game.core.stage.Mechanic

/** Player-facing preferences (spec §33, §35, §51). */
class Settings(
    var music: Boolean = true,
    var sfx: Boolean = true,
    var vibration: Boolean = true,
    var reduceEffects: Boolean = false,
    var showFps: Boolean = false,
    /** "auto", "pt" or "en". */
    var language: String = "auto",
)

class Profile(
    var nickname: String,
    var avatar: Int = 0,
    var createdAt: Long = 0L,
)

/**
 * Everything persisted between sessions (spec §21). Mutated only through
 * [com.taptap.game.core.progression.Progression], which marks it dirty for auto-save.
 */
class SaveData(
    var profile: Profile = Profile(defaultNickname(0L)),
    var coins: Long = 0L,
    var totalXp: Long = 0L,
    /** Highest stage the player may play (cleared + 1). */
    var highestUnlocked: Int = 1,
    var selectedStage: Int = 1,
    val stageStars: MutableMap<Int, Int> = HashMap(),
    val stageBest: MutableMap<Int, Long> = HashMap(),
    /** Consecutive failures per stage (drives the light adaptive assist, spec §56). */
    val stageFailStreak: MutableMap<Int, Int> = HashMap(),
    val upgrades: MutableMap<UpgradeId, Int> = HashMap(),
    val stats: Stats = Stats(),
    /** Achievement id → unlock time. */
    val achievements: MutableMap<String, Long> = LinkedHashMap(),
    val missions: MutableList<Mission> = ArrayList(),
    var missionCounter: Int = 0,
    var dailyLastDay: Long = -1L,
    var dailyStreak: Int = 0,
    val ranking: MutableList<RankEntry> = ArrayList(),
    val settings: Settings = Settings(),
    var onboardingDone: Boolean = false,
    val seenIntros: MutableSet<Mechanic> = HashSet(),
) {
    val totalStars: Int get() = stageStars.values.sum()
    val highestCleared: Int get() = highestUnlocked - 1

    companion object {
        const val VERSION = 1
        fun defaultNickname(seed: Long): String = "PLAYER" + (1000 + Math.floorMod(seed, 9000L))
    }
}
