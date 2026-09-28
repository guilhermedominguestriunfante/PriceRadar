package com.dedonervoso.core.save

import com.dedonervoso.core.online.OnlineAccount
import com.dedonervoso.core.progression.Mission
import com.dedonervoso.core.progression.RankEntry
import com.dedonervoso.core.progression.Stats
import com.dedonervoso.core.progression.UpgradeId
import com.dedonervoso.core.stage.Mechanic

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
 * [com.dedonervoso.core.progression.Progression], which marks it dirty for auto-save.
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
    /** Last release manifest seen (JSON), so update notices and the online gate work offline. */
    var releaseCache: String = "",
    /** Where that manifest was found (it may have moved from the built-in address). */
    var releaseManifestUrl: String = "",
    var releaseCheckedAt: Long = 0L,
    /** Online ranking and duels are opt-in; the account below is kept when switched off. */
    var onlineEnabled: Boolean = false,
    val online: OnlineAccount = OnlineAccount(),
) {
    val totalStars: Int get() = stageStars.values.sum()
    val highestCleared: Int get() = highestUnlocked - 1

    companion object {
        const val VERSION = 1
        fun defaultNickname(seed: Long): String = "PLAYER" + (1000 + Math.floorMod(seed, 9000L))
    }
}
