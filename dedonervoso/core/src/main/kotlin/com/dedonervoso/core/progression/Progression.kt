package com.dedonervoso.core.progression

import com.dedonervoso.core.engine.Loadout
import com.dedonervoso.core.engine.MatchResult
import com.dedonervoso.core.online.OnlineService
import com.dedonervoso.core.save.SaveCodec
import com.dedonervoso.core.save.SaveData
import com.dedonervoso.core.stage.Mechanic
import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.stage.StageType
import com.dedonervoso.core.util.Rng
import kotlin.math.max
import kotlin.math.roundToInt

/** Wall clock + local calendar, injected so progression stays testable and timezone aware. */
interface GameClock {
    fun nowMs(): Long

    /** Local calendar day number for [ms] (days since epoch in the player's timezone). */
    fun dayIndex(ms: Long): Long

    object System : GameClock {
        override fun nowMs(): Long = java.lang.System.currentTimeMillis()
        override fun dayIndex(ms: Long): Long {
            val offset = java.util.TimeZone.getDefault().getOffset(ms).toLong()
            return Math.floorDiv(ms + offset, 86_400_000L)
        }
    }
}

/** Everything a finished match produced, for the result screen. */
class MatchOutcome(
    val result: MatchResult,
    val stage: StageConfig,
    val daily: Boolean,
    val coins: CoinBreakdown,
    val dailyCoins: Int,
    val xpGained: Int,
    val levelBefore: Int,
    val levelAfter: Int,
    val levelUpCoins: Int,
    /**
     * Beat a record that already existed: the stage's best time (campaign) or the Arena best
     * (Arena). The first clear or first Arena match sets a record without celebrating it.
     */
    val newRecord: Boolean,
    /** The record before this match: best time in ms (campaign) or points (Arena); 0 if none. */
    val previousRecord: Long,
    val newStageBest: Boolean,
    val newMaxCombo: Boolean,
    val newMaxTps: Boolean,
    val starsBefore: Int,
    val starsAfter: Int,
    val unlockedStage: Int?,
    val achievements: List<AchievementDef>,
    val achievementCoins: Int,
    val missionsCompleted: List<Mission>,
    /** Arena: place of this match among this device's Arena results (1-based), or -1. */
    val rank: Int,
    val arena: Boolean = false,
    /** Arena: this week's best on this device after the match. */
    val arenaWeekBest: Long = 0L,
) {
    val totalCoins: Int get() = coins.total + dailyCoins + levelUpCoins + achievementCoins
    val anyRecord: Boolean get() = newRecord || newMaxCombo || newMaxTps
}

/**
 * Domain service owning [SaveData]: applies match results (stats, stars, unlocks, coins, XP,
 * missions, ranking, achievements), purchases and profile edits. Every mutation calls
 * [onChanged] so the platform layer can auto-save (spec §21: never ask the player to save).
 */
class Progression(save: SaveData, private val clock: GameClock = GameClock.System) {

    var save: SaveData = save
        private set

    /** Invoked after every mutation (hook for auto-save). */
    var onChanged: (() -> Unit)? = null

    init {
        if (save.profile.createdAt == 0L) {
            save.profile.createdAt = clock.nowMs()
            save.profile.nickname = SaveData.defaultNickname(clock.nowMs() / 997)
        }
        ensureMissions()
    }

    val levelInfo: Economy.LevelInfo get() = Economy.levelFor(save.totalXp)
    val level: Int get() = levelInfo.level
    val loadout: Loadout get() = Upgrades.loadout(save.upgrades)
    val today: Long get() = clock.dayIndex(clock.nowMs())
    val leaderboard: LocalLeaderboard get() = LocalLeaderboard(save.ranking)

    private fun changed() {
        onChanged?.invoke()
    }

    // ---- stages ------------------------------------------------------------------------------

    /**
     * Light adaptive difficulty (spec §56): after repeated failures on a stage its objective is
     * lowered a little. It never makes anything harder than the catalog.
     */
    fun assistFactor(stage: Int): Float = when (save.stageFailStreak[stage] ?: 0) {
        in 0..1 -> 1f
        in 2..3 -> 0.9f
        in 4..5 -> 0.8f
        else -> 0.72f
    }

    fun stage(number: Int): StageConfig {
        val base = StageCatalog.stage(number)
        val factor = assistFactor(number)
        if (factor >= 1f) return base
        val scaled = { v: Int -> max(1, (v * factor).roundToInt()) }
        return StageConfig(
            number = base.number,
            type = base.type,
            target = scaled(base.target),
            scoreTarget = if (base.type == StageType.BOSS || base.type == StageType.SCORE) scaled(base.scoreTarget) else base.scoreTarget,
            star2Score = base.star2Score,
            star3Score = base.star3Score,
            durationMs = base.durationMs,
            lives = base.lives,
            stop = base.stop,
            zones = base.zones,
            frenzy = base.frenzy,
            comboTimeoutMs = base.comboTimeoutMs,
            penaltyTier = base.penaltyTier,
            isBoss = base.isBoss,
            introduces = base.introduces,
            seed = base.seed,
            customTitle = base.customTitle,
            endOnObjective = base.endOnObjective,
            // A lower objective is met sooner: the star times shrink with it.
            star2TimeMs = (base.star2TimeMs * factor).toLong(),
            star3TimeMs = (base.star3TimeMs * factor).toLong(),
            boss = base.boss,
            bossTier = base.bossTier,
        )
    }

    fun isAssisted(stage: Int): Boolean = assistFactor(stage) < 1f

    fun selectStage(number: Int) {
        val n = number.coerceIn(1, save.highestUnlocked)
        if (n != save.selectedStage) {
            save.selectedStage = n
            changed()
        }
    }

    fun daily(): Pair<DailyTemplate, StageConfig> = DailyChallenge.forDay(today, save.highestUnlocked)

    val dailyRewardAvailable: Boolean get() = save.dailyLastDay != today

    // ---- match results ---------------------------------------------------------------------------

    /** The Arena of this week (same seed for everyone until Monday 00:00 UTC). */
    fun arena(): StageConfig = StageCatalog.arena(OnlineService.weekIndex(clock.nowMs()))

    /** The Arena opens after the first boss. */
    val arenaUnlocked: Boolean get() = save.highestCleared >= StageCatalog.BOSS_EVERY

    fun applyMatch(result: MatchResult, stage: StageConfig, daily: Boolean = false): MatchOutcome {
        result.suspicious = ResultValidator.isSuspicious(result)
        val arena = stage.number == StageCatalog.ARENA
        val s = save.stats
        val levelBefore = level
        val newMaxCombo = result.maxCombo > s.maxCombo
        val newMaxTps = result.maxTps > s.maxTps + 0.01f && result.maxTps > 0f

        // Lifetime statistics.
        s.matches++
        if (result.won && !arena) s.wins++
        s.totalTaps += result.taps
        s.totalTouches += result.touches
        s.perfects += result.perfects
        s.stopErrors += result.stopErrors
        s.stopsSurvived += result.stopsSurvived
        s.zoneHits += result.zoneHits
        s.playTimeMs += result.playedMs
        s.frenzies += result.frenzies
        s.megaFrenzies += result.megaFrenzies
        s.goldenHits += result.goldenHits
        if (newMaxCombo) s.maxCombo = result.maxCombo
        if (newMaxTps) s.maxTps = result.maxTps
        if (result.reflexBestMs >= 0 && (s.reflexBestMs < 0 || result.reflexBestMs < s.reflexBestMs)) s.reflexBestMs = result.reflexBestMs
        if (result.won && result.isBoss) s.bossesDefeated++
        if (result.won && result.interrupts >= 3 && result.stopErrors == 0) s.flawlessStages++

        // Records and stage progress (the daily challenge and the Arena live outside the ladder).
        val n = stage.number
        val ladder = !daily && !arena
        val starsBefore = if (ladder) save.stageStars[n] ?: 0 else 0
        var starsAfter = starsBefore
        var unlocked: Int? = null
        var newStageBest = false
        var newRecord = false
        var previousRecord = 0L
        if (arena) {
            s.arenaMatches++
            previousRecord = s.arenaBest
            if (!result.suspicious && result.score > s.arenaBest) {
                newRecord = s.arenaBest > 0L
                s.arenaBest = result.score
            }
            val week = OnlineService.isoWeek(clock.nowMs())
            if (save.arenaWeekId != week) {
                save.arenaWeekId = week
                save.arenaWeekBest = 0L
            }
            if (!result.suspicious && result.score > save.arenaWeekBest) save.arenaWeekBest = result.score
        } else if (ladder) {
            if (result.won) {
                starsAfter = max(starsBefore, result.stars)
                save.stageStars[n] = starsAfter
                save.stageFailStreak.remove(n)
                if (n >= save.highestUnlocked) {
                    save.highestUnlocked = n + 1
                    save.selectedStage = n + 1
                    unlocked = n + 1
                }
                // The stage's record is its best graded time.
                previousRecord = save.stageBestTime[n] ?: 0L
                if (previousRecord == 0L || result.gradeTimeMs < previousRecord) {
                    save.stageBestTime[n] = result.gradeTimeMs
                    newRecord = previousRecord > 0L
                    newStageBest = true
                }
            } else if (result.failReason != com.dedonervoso.core.engine.FailReason.ABORTED) {
                save.stageFailStreak[n] = (save.stageFailStreak[n] ?: 0) + 1
            }
            if (result.score > (save.stageBest[n] ?: 0L)) save.stageBest[n] = result.score
        }
        val newStars = starsAfter - starsBefore

        // Coins.
        val coins = if (arena) {
            Economy.arenaCoins(result, newRecord, loadout.coinBoostLevel)
        } else {
            Economy.matchCoins(
                result, newStars, firstClear = ladder && starsBefore == 0 && result.won,
                newRecord = newRecord, coinBoostLevel = loadout.coinBoostLevel, replay = ladder && starsBefore > 0,
            )
        }
        var dailyCoins = 0
        if (daily && result.won && dailyRewardAvailable) {
            val todayIdx = today
            save.dailyStreak = if (save.dailyLastDay == todayIdx - 1) save.dailyStreak + 1 else 1
            save.dailyLastDay = todayIdx
            s.dailyCompleted++
            dailyCoins = DailyChallenge.rewardCoins(levelBefore, save.dailyStreak)
        }

        // XP & level ups.
        val xp = if (arena) Economy.arenaXp(result) else Economy.matchXp(result)
        save.totalXp += xp
        val levelAfter = level
        var levelUpCoins = 0
        for (lv in levelBefore + 1..levelAfter) levelUpCoins += Economy.levelUpCoins(lv)

        addCoins(coins.total + dailyCoins + levelUpCoins)

        // Missions, ranking (the Arena's), achievements.
        val completedMissions = Missions.apply(save.missions, result, newStars)
        val now = clock.nowMs()
        val rank = if (!arena) -1 else leaderboard.submit(
            RankEntry(
                score = result.score, stage = save.highestCleared, maxCombo = result.maxCombo, maxTps = result.maxTps,
                taps = result.taps, timestamp = now, dayIndex = clock.dayIndex(now), suspicious = result.suspicious,
            ),
        )
        val newAchievements = unlockAchievements()
        val achievementCoins = newAchievements.sumOf { it.coins }

        changed()
        return MatchOutcome(
            result = result, stage = stage, daily = daily, coins = coins, dailyCoins = dailyCoins, xpGained = xp,
            levelBefore = levelBefore, levelAfter = levelAfter, levelUpCoins = levelUpCoins,
            newRecord = newRecord, previousRecord = previousRecord, newStageBest = newStageBest,
            newMaxCombo = newMaxCombo, newMaxTps = newMaxTps, starsBefore = starsBefore, starsAfter = starsAfter,
            unlockedStage = unlocked, achievements = newAchievements, achievementCoins = achievementCoins,
            missionsCompleted = completedMissions, rank = rank, arena = arena, arenaWeekBest = save.arenaWeekBest,
        )
    }

    private fun addCoins(amount: Int) {
        if (amount <= 0) return
        save.coins += amount
        save.stats.coinsEarned += amount
    }

    // ---- achievements ------------------------------------------------------------------------------

    fun achievementContext() = AchievementContext(
        stats = save.stats, playerLevel = level, highestCleared = save.highestCleared,
        totalStars = save.totalStars, dailyStreak = save.dailyStreak,
    )

    /** Unlocks every newly satisfied achievement, pays its reward and returns them. */
    fun unlockAchievements(): List<AchievementDef> {
        val unlockedNow = Achievements.newlyUnlocked(achievementContext(), save.achievements.keys)
        if (unlockedNow.isEmpty()) return unlockedNow
        val now = clock.nowMs()
        for (a in unlockedNow) {
            save.achievements[a.id] = now
            addCoins(a.coins)
        }
        // Rewards may push other achievements (e.g. coins earned); a second pass settles them.
        val chained = Achievements.newlyUnlocked(achievementContext(), save.achievements.keys)
        for (a in chained) {
            save.achievements[a.id] = now
            addCoins(a.coins)
        }
        return unlockedNow + chained
    }

    // ---- shop -------------------------------------------------------------------------------------

    fun upgradeLevel(id: UpgradeId): Int = save.upgrades[id] ?: 0

    fun check(id: UpgradeId): BuyCheck =
        Upgrades.check(id, save.upgrades, save.coins, save.highestUnlocked, level)

    /** Buys the next level of [id]. Never spends coins unless the purchase succeeds (spec §65). */
    fun buy(id: UpgradeId): BuyCheck {
        val check = check(id)
        if (check != BuyCheck.OK) return check
        val lv = upgradeLevel(id)
        val cost = Upgrades.def(id).costs[lv]
        save.coins -= cost
        save.upgrades[id] = lv + 1
        unlockAchievements()
        changed()
        return BuyCheck.OK
    }

    /** Cheapest upgrade the player can buy right now (used for result-screen hints). */
    fun affordableUpgrade(): UpgradeId? = Upgrades.ALL
        .filter { check(it.id) == BuyCheck.OK }
        .minByOrNull { Upgrades.nextCost(it.id, upgradeLevel(it.id)) ?: Int.MAX_VALUE }?.id

    // ---- missions ------------------------------------------------------------------------------------

    fun ensureMissions() {
        val active = save.missions.filter { !it.claimed }
        save.missions.retainAll(active)
        var added = false
        while (save.missions.size < Missions.ACTIVE) {
            val id = ++save.missionCounter
            val rng = Rng(Rng.mix(save.profile.createdAt + 17, id.toLong()))
            save.missions += Missions.generate(id, rng, level, save.highestUnlocked, save.missions.map { it.kind }.toSet())
            added = true
        }
        if (added) changed()
    }

    /** Claims a completed mission's reward and rolls a new one. Returns false if not claimable. */
    fun claimMission(id: Int): Boolean {
        val m = save.missions.firstOrNull { it.id == id } ?: return false
        if (!m.done || m.claimed) return false
        m.claimed = true
        addCoins(m.rewardCoins)
        save.totalXp += m.rewardXp
        ensureMissions()
        unlockAchievements()
        changed()
        return true
    }

    val claimableMissions: Int get() = save.missions.count { it.done && !it.claimed }

    // ---- profile & flags ----------------------------------------------------------------------------

    fun setNickname(name: String) {
        save.profile.nickname = SaveCodec.sanitizeNickname(name)
        changed()
    }

    fun setAvatar(index: Int) {
        save.profile.avatar = index.coerceIn(0, SaveCodec.AVATAR_COUNT - 1)
        changed()
    }

    fun markIntroSeen(mechanic: Mechanic) {
        if (save.seenIntros.add(mechanic)) changed()
    }

    fun completeOnboarding() {
        if (!save.onboardingDone) {
            save.onboardingDone = true
            changed()
        }
    }

    fun settingsChanged() = changed()

    /** Stores what a background online sync learned (identity, code, friends, synced bests). */
    fun updateOnline(account: com.dedonervoso.core.online.OnlineAccount) {
        save.online.set(account)
        changed()
    }

    fun setOnlineEnabled(enabled: Boolean) {
        save.onlineEnabled = enabled
        changed()
    }

    /** Remembers the last release manifest seen (see [SaveData.releaseCache]). */
    fun recordRelease(manifestJson: String, manifestUrl: String, checkedAt: Long) {
        save.releaseCache = manifestJson
        save.releaseManifestUrl = manifestUrl
        save.releaseCheckedAt = checkedAt
        changed()
    }

    /** Wipes progress but keeps settings and identity (spec §51, confirmed by the UI). */
    fun resetProgress() {
        // Progress starts over; identity, preferences and the online account stay.
        val fresh = SaveData(
            profile = save.profile, settings = save.settings,
            releaseCache = save.releaseCache, releaseManifestUrl = save.releaseManifestUrl, releaseCheckedAt = save.releaseCheckedAt,
            onlineEnabled = save.onlineEnabled, online = save.online,
        )
        fresh.onboardingDone = true
        save = fresh
        ensureMissions()
        changed()
    }
}
