package com.taptap.game.core.progression

import com.taptap.game.core.FakeClock
import com.taptap.game.core.result
import com.taptap.game.core.save.SaveCodec
import com.taptap.game.core.save.SaveData
import com.taptap.game.core.stage.StageCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionTest {
    private val clock = FakeClock()
    private fun fresh() = Progression(SaveData(), clock)

    @Test
    fun winningUnlocksNextStageAndPaysFirstClear() {
        val p = fresh()
        val outcome = p.applyMatch(result(stage = 1, won = true, stars = 2), StageCatalog.stage(1))
        assertEquals(2, p.save.highestUnlocked)
        assertEquals(2, outcome.unlockedStage)
        assertEquals(2, p.save.stageStars[1])
        assertTrue(outcome.coins.firstClear > 0)
        assertEquals(2 * Economy.STAR_COINS, outcome.coins.stars)
        assertEquals(p.save.coins, outcome.totalCoins.toLong())
        // Replaying: no first clear again, stars only for improvements.
        val again = p.applyMatch(result(stage = 1, won = true, stars = 3), StageCatalog.stage(1))
        assertEquals(0, again.coins.firstClear)
        assertEquals(Economy.STAR_COINS, again.coins.stars)
        assertNull(again.unlockedStage)
    }

    @Test
    fun losingKeepsStageLockedAndTracksFailStreakForAssist() {
        val p = fresh()
        repeat(2) { p.applyMatch(result(stage = 1, won = false), StageCatalog.stage(1)) }
        assertEquals(1, p.save.highestUnlocked)
        assertTrue(p.isAssisted(1))
        assertTrue(p.stage(1).target < StageCatalog.stage(1).target, "assist lowers the objective")
        p.applyMatch(result(stage = 1, won = true), StageCatalog.stage(1))
        assertFalse(p.isAssisted(1))
    }

    @Test
    fun xpAccumulatesAndLevelsUpWithRewards() {
        val p = fresh()
        var levelUps = 0
        repeat(10) {
            val o = p.applyMatch(result(stage = 1, won = true, touches = 400, maxCombo = 150), StageCatalog.stage(1))
            levelUps += o.levelAfter - o.levelBefore
            if (o.levelAfter > o.levelBefore) assertTrue(o.levelUpCoins > 0)
        }
        assertTrue(levelUps >= 2, "early levels come quickly (got $levelUps)")
        assertEquals(levelUps + 1, p.level)
    }

    @Test
    fun buyingSpendsExactlyAndNeverWithoutFunds() {
        val p = fresh()
        assertEquals(BuyCheck.NOT_ENOUGH_COINS, p.buy(UpgradeId.DOUBLE_TAP))
        assertEquals(0L, p.save.coins)
        p.save.coins = 1_000
        assertEquals(BuyCheck.OK, p.buy(UpgradeId.DOUBLE_TAP))
        assertEquals(750L, p.save.coins)
        assertEquals(2, p.loadout.tapValue)
        assertEquals(BuyCheck.MAXED, p.buy(UpgradeId.DOUBLE_TAP))
        assertEquals(750L, p.save.coins)
        assertEquals(BuyCheck.LOCKED_STAGE, p.buy(UpgradeId.STOP_SHIELD))
        assertEquals(BuyCheck.LOCKED_LEVEL, p.buy(UpgradeId.TRIPLE_TAP))
    }

    @Test
    fun tripleTapRequiresDoubleTap() {
        val p = fresh()
        p.save.coins = 10_000
        p.save.totalXp = 100_000
        assertEquals(BuyCheck.REQUIRES_OTHER, p.check(UpgradeId.TRIPLE_TAP))
        p.buy(UpgradeId.DOUBLE_TAP)
        assertEquals(BuyCheck.OK, p.buy(UpgradeId.TRIPLE_TAP))
        assertEquals(3, p.loadout.tapValue)
    }

    @Test
    fun upgradeCostsGrowSteeply() {
        for (def in Upgrades.ALL) {
            for (i in 1 until def.costs.size) {
                assertTrue(def.costs[i] >= def.costs[i - 1] * 2, "${def.id} level ${i + 1} should cost much more")
            }
        }
        val cheapest = Upgrades.ALL.minOf { it.costs[0] }
        val firstMatch = Economy.matchCoins(result(stage = 1, won = true, touches = 300, maxCombo = 60), 1, true, false, 0)
        assertTrue(firstMatch.total >= cheapest / 2, "first upgrade is within a couple of matches")
    }

    @Test
    fun achievementsUnlockOnceAndPay() {
        val p = fresh()
        val o = p.applyMatch(result(stage = 1, won = true, taps = 1_200, touches = 1_000), StageCatalog.stage(1))
        val ids = o.achievements.map { it.id }
        assertTrue("first_tap" in ids && "tap_machine" in ids && "first_win" in ids, "$ids")
        assertTrue(o.achievementCoins > 0)
        val o2 = p.applyMatch(result(stage = 2, won = true), StageCatalog.stage(2))
        assertFalse(o2.achievements.any { it.id == "first_tap" })
    }

    @Test
    fun missionsProgressAndClaim() {
        val p = fresh()
        assertEquals(Missions.ACTIVE, p.save.missions.size)
        val m = p.save.missions.first()
        m.progress = m.target
        val before = p.save.coins
        assertTrue(p.claimMission(m.id))
        assertEquals(before + m.rewardCoins, p.save.coins)
        assertFalse(p.claimMission(m.id), "cannot claim twice")
        assertEquals(Missions.ACTIVE, p.save.missions.count { !it.claimed })
    }

    @Test
    fun cumulativeAndBestMissionKinds() {
        val taps = Mission(1, MissionKind.TAPS, 500, 0, 10, 10)
        val combo = Mission(2, MissionKind.REACH_COMBO, 100, 0, 10, 10)
        Missions.apply(listOf(taps, combo), result(taps = 300, maxCombo = 60), 0)
        Missions.apply(listOf(taps, combo), result(taps = 300, maxCombo = 40), 0)
        assertEquals(500, taps.progress.coerceAtMost(500))
        assertTrue(taps.done)
        assertEquals(60, combo.progress)
        assertFalse(combo.done)
    }

    @Test
    fun dailyRewardOncePerDayWithStreak() {
        val p = fresh()
        val (_, cfg) = p.daily()
        val first = p.applyMatch(result(stage = -1, won = true), cfg, daily = true)
        assertTrue(first.dailyCoins > 0)
        assertEquals(1, p.save.dailyStreak)
        val second = p.applyMatch(result(stage = -1, won = true), cfg, daily = true)
        assertEquals(0, second.dailyCoins, "only once per day")
        clock.advanceDays(1)
        val (_, cfg2) = p.daily()
        p.applyMatch(result(stage = -1, won = true), cfg2, daily = true)
        assertEquals(2, p.save.dailyStreak)
        clock.advanceDays(2)
        p.applyMatch(result(stage = -1, won = true), p.daily().second, daily = true)
        assertEquals(1, p.save.dailyStreak, "missing a day resets the streak")
        assertEquals(1, p.save.highestUnlocked, "daily does not touch the stage ladder")
    }

    @Test
    fun leaderboardRanksAndScopes() {
        val entries = mutableListOf<RankEntry>()
        val board = LocalLeaderboard(entries)
        assertEquals(1, board.submit(RankEntry(100, 1, 1, 1f, 1, 0, dayIndex = 10)))
        assertEquals(1, board.submit(RankEntry(300, 1, 1, 1f, 1, 0, dayIndex = 10)))
        assertEquals(2, board.submit(RankEntry(200, 1, 1, 1f, 1, 0, dayIndex = 12)))
        assertEquals(listOf(300L, 200L, 100L), board.top(RankScope.ALL_TIME, 12, 10).map { it.score })
        assertEquals(listOf(200L), board.top(RankScope.TODAY, 12, 10).map { it.score })
        assertEquals(3, board.top(RankScope.WEEK, 12, 10).size)
        assertEquals(1, board.top(RankScope.WEEK, 18, 10).size)
        assertEquals(0, board.top(RankScope.WEEK, 20, 10).size)
        repeat(100) { board.submit(RankEntry(it.toLong() + 1, 1, 1, 1f, 1, 0, dayIndex = 20)) }
        assertTrue(entries.size <= LocalLeaderboard.MAX_ENTRIES)
    }

    @Test
    fun resetKeepsSettingsAndName() {
        val p = fresh()
        p.save.settings.music = false
        p.setNickname("Kawe")
        p.save.coins = 999
        p.applyMatch(result(stage = 1, won = true), StageCatalog.stage(1))
        p.resetProgress()
        assertEquals(0L, p.save.coins)
        assertEquals(1, p.save.highestUnlocked)
        assertFalse(p.save.settings.music)
        assertEquals("Kawe", p.save.profile.nickname)
        assertTrue(p.save.stageStars.isEmpty())
        assertEquals(Missions.ACTIVE, p.save.missions.size)
    }

    @Test
    fun suspiciousResultsAreFlaggedButStillCount() {
        val p = fresh()
        val cheat = result(stage = 1, won = true, touches = 5_000, taps = 5_000, maxTps = 80f)
        val o = p.applyMatch(cheat, StageCatalog.stage(1))
        assertTrue(o.result.suspicious)
        assertTrue(p.save.ranking.first().suspicious)
        assertNull(p.leaderboard.best)
        val normal = result(stage = 1, won = true)
        assertFalse(ResultValidator.isSuspicious(normal))
    }

    @Test
    fun saveSurvivesEncodeDecode() {
        val p = fresh()
        p.save.coins = 1_234
        p.setNickname("Ana Ç")
        p.setAvatar(5)
        p.save.settings.vibration = false
        p.save.settings.language = "en"
        p.applyMatch(result(stage = 1, won = true, stars = 3, perfects = 7), StageCatalog.stage(1))
        p.save.upgrades[UpgradeId.COMBO_BOOST] = 2
        p.markIntroSeen(com.taptap.game.core.stage.Mechanic.STOP)
        val text = SaveCodec.encode(p.save)
        val back = SaveCodec.decode(text)
        assertEquals(SaveCodec.encode(back), text, "round trip is lossless")
        assertEquals(p.save.coins, back.coins)
        assertEquals("Ana Ç", back.profile.nickname)
        assertEquals(3, back.stageStars[1])
        assertEquals(2, back.upgrades[UpgradeId.COMBO_BOOST])
        assertFalse(back.settings.vibration)
        assertEquals(7L, back.stats.perfects)
        assertEquals(p.save.missions.size, back.missions.size)
        assertEquals(p.save.ranking.size, back.ranking.size)
        assertTrue(com.taptap.game.core.stage.Mechanic.STOP in back.seenIntros)
    }

    @Test
    fun decodeClampsAbsurdAndIgnoresUnknown() {
        val text = """{"version":1,"coins":-5,"totalXp":99999999999999,"highestUnlocked":0,
            "upgrades":{"DOUBLE_TAP":99,"NOPE":3},"stageStars":{"1":9,"x":2},"settings":{"language":"klingon"},
            "futureField":{"a":[1,2,3]}}"""
        val d = SaveCodec.decode(text)
        assertEquals(0L, d.coins)
        assertEquals(1, d.highestUnlocked)
        assertEquals(1, d.upgrades[UpgradeId.DOUBLE_TAP])
        assertEquals(3, d.stageStars[1])
        assertEquals("auto", d.settings.language)
        assertTrue(d.totalXp < 99999999999999)
    }
}
