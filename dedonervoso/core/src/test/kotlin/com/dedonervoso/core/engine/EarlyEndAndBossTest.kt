package com.dedonervoso.core.engine

import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.stage.StageType
import com.dedonervoso.core.stage.StopConfig
import com.dedonervoso.core.stage.ZoneConfig
import com.dedonervoso.core.util.Rng
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 1.3: stages end on their objective and grade by time; SURVIVAL counts STOPs; boss fights. */
class EarlyEndAndBossTest {
    private val t0 = 10_000L
    private val playStart = t0 + GameBalance.COUNTDOWN_MS

    private fun stage(
        type: StageType = StageType.SPEED,
        target: Int = 20,
        scoreTarget: Int = target,
        endOnObjective: Boolean = true,
        star2Ms: Long = 0L,
        star3Ms: Long = 0L,
        stop: StopConfig? = null,
        zones: ZoneConfig? = null,
        lives: Int = 0,
        boss: BossKind? = null,
        penalty: PenaltyTier = PenaltyTier.LIGHT,
        durationMs: Long = GameBalance.MATCH_DURATION_MS,
    ) = StageConfig(
        number = 1, type = type, target = target, scoreTarget = scoreTarget, star2Score = 1_000_000, star3Score = 2_000_000,
        durationMs = durationMs, lives = lives, stop = stop, zones = zones, penaltyTier = penalty, isBoss = boss != null,
        endOnObjective = endOnObjective, star2TimeMs = star2Ms, star3TimeMs = star3Ms, boss = boss,
    )

    private fun session(stage: StageConfig, listener: GameListener = GameListener.NONE, seed: Long = 42L) =
        GameSession(stage, Loadout.NONE, arenaHeight = 1.4f, seed = seed, listener = listener).also {
            it.start(t0)
            it.update(playStart)
        }

    /** Taps at the arena's lower edge (where no boss sits) every [gapMs] until [until] holds. */
    private fun tapUntil(s: GameSession, from: Long, gapMs: Long = 100L, x: Float = 0.5f, y: Float = 1.35f, until: () -> Boolean): Long {
        var t = from
        while (!until() && s.state != GameState.FINISHED) {
            t += gapMs
            s.update(t)
            if (s.state.isActive && s.state != GameState.STOP) s.tap(t, x, y)
        }
        return t
    }

    @Test
    fun stageEndsTheMomentTheObjectiveIsMet() {
        val rec = Rec()
        val s = session(stage(target = 20), rec)
        var t = playStart
        repeat(19) { t += 100; s.tap(t, 0.5f, 0.5f) }
        assertEquals(GameState.PLAYING, s.state)
        t += 100
        s.tap(t, 0.5f, 0.5f)
        assertEquals(GameState.FINISHED, s.state)
        val r = assertNotNull(s.result)
        assertTrue(r.won && r.endedEarly)
        assertEquals(2_000L, r.playedMs)
        assertEquals(1, rec.objectives)
        assertFalse(s.tap(t + 100, 0.5f, 0.5f), "nothing counts after the end")
    }

    @Test
    fun withoutEarlyEndTheMatchPlaysTheFullTime() {
        val s = session(stage(target = 20, endOnObjective = false))
        var t = playStart
        repeat(30) { t += 100; s.tap(t, 0.5f, 0.5f) }
        assertEquals(GameState.PLAYING, s.state)
        s.update(playStart + GameBalance.MATCH_DURATION_MS)
        assertEquals(60_000L, s.result!!.playedMs)
        assertFalse(s.result!!.endedEarly)
    }

    @Test
    fun arenaHasNoObjectiveToAnnounce() {
        val rec = Rec()
        val s = session(StageCatalog.arena(202640), rec)
        var t = playStart
        repeat(50) { t += 100; s.tap(t, 0.5f, 1.35f) }
        s.update(playStart + 70_000)
        assertEquals(0, rec.objectives)
        assertEquals(60_000L, s.result!!.playedMs)
    }

    @Test
    fun starsGradeTheTimeWithTwoSecondsPerStopError() {
        fun play(gapMs: Long): MatchResult {
            val s = session(stage(target = 40, star2Ms = 8_000, star3Ms = 5_000))
            var t = playStart
            repeat(40) { t += gapMs; s.tap(t, 0.5f, 0.5f) }
            return s.result!!
        }
        assertEquals(3, play(120).stars)   // 4.8 s
        assertEquals(2, play(150).stars)   // 6.0 s
        assertEquals(1, play(250).stars)   // 10 s
        val r = play(120)
        assertEquals(r.playedMs, r.gradeTimeMs)
        val withErrors = com.dedonervoso.core.result(playedMs = 4_000, stopErrors = 2)
        assertEquals(8_000L, withErrors.gradeTimeMs)
    }

    @Test
    fun survivalEndsAfterItsLastStopAndGradesErrors() {
        val stop = StopConfig(3, 3, 1_000, 1_000, warningMs = 500, graceMs = 250, fakeChance = 1f, minGapMs = 3_000, minHolds = 3)
        val s = session(stage(type = StageType.SURVIVAL, target = 3, stop = stop, lives = 3, durationMs = 48_000))
        assertEquals(3L, s.objectiveTarget, "fakes never replace the holds a SURVIVAL needs")
        tapUntil(s, playStart) { false }
        val r = s.result!!
        assertTrue(r.won && r.endedEarly, "ended at the last STOP, not the time limit")
        assertEquals(3, r.stopsSurvived)
        assertEquals(3, r.stars, "no errors: three stars")
    }

    @Test
    fun survivalFailsAsSoonAsTheErrorsRunOut() {
        val stop = StopConfig(3, 3, 1_500, 1_500, warningMs = 500, graceMs = 200, minGapMs = 3_000, minHolds = 3)
        val s = session(stage(type = StageType.SURVIVAL, target = 3, stop = stop, lives = 1, penalty = PenaltyTier.MEDIUM))
        var t = playStart
        while (s.state != GameState.STOP) { t += 50; s.update(t) }
        s.tap(t + 600, 0.5f, 0.5f)
        assertEquals(GameState.FINISHED, s.state)
        assertEquals(FailReason.NO_LIVES, s.result!!.failReason)
    }

    @Test
    fun plannerKeepsTheRequiredHolds() {
        val config = StopConfig(4, 4, 1_000, 1_000, warningMs = 400, graceMs = 200, fakeChance = 1f, minGapMs = 3_000, minHolds = 3)
        repeat(20) { seed ->
            val plan = InterruptPlanner.plan(config, 60_000, Rng(seed.toLong()))
            assertTrue(plan.count { it.kind != InterruptKind.FAKE_STOP } >= 3)
        }
    }

    @Test
    fun missesOutsideAMandatoryZoneAreCounted() {
        val zones = ZoneConfig(
            spawnMinMs = 500, spawnMaxMs = 500, maxConcurrent = 1, lifeMinMs = 10_000, lifeMaxMs = 10_000,
            weights = ZoneConfig.weights(ZoneType.X2 to 1), lockChance = 1f,
        )
        val s = session(stage(target = 1_000, zones = zones))
        var t = playStart
        while (!s.lockActive) { t += 50; s.update(t) }
        val z = s.zones.first { it.active }
        val farX = if (z.x(s.activeTimeMs) > 0.5f) 0.05f else 0.95f
        s.tap(t + 10, farX, 0.05f)
        s.update(t + 20)
        assertEquals(1, s.misses)
    }

    // ---- bosses -------------------------------------------------------------------------------------

    private fun bossStage(health: Int = 5_000, kind: BossKind = BossKind.FURIOSO) =
        stage(type = StageType.BOSS, target = health, scoreTarget = health, lives = 3, boss = kind, star2Ms = 40_000, star3Ms = 30_000)

    @Test
    fun hittingTheBossIsWorthTripleAndItsCentreIsPerfect() {
        val rec = Rec()
        val s = session(bossStage(), rec)
        val b = assertNotNull(s.boss)
        val far = if (b.y > 0.7f) 0.1f else 1.35f
        s.tap(playStart + 100, 0.5f, far)
        val normal = s.score
        s.tap(playStart + 200, b.x, b.y)
        assertEquals(TapKind.PERFECT, rec.kinds.last(), "dead centre of the boss")
        s.tap(playStart + 300, b.x + b.radius * 0.8f, b.y)
        assertEquals(TapKind.BOSS, rec.kinds.last())
        assertTrue(s.score - normal >= 3 * normal + 3, "hits on the boss are worth x3 (plus the perfect bonus)")
        assertEquals(2, s.boss!!.hits)
        assertEquals(s.stage.scoreTarget - s.score, s.bossHealth)
    }

    @Test
    fun beatingTheBossEndsTheStage() {
        val s = session(bossStage(health = 200))
        tapUntil(s, playStart, x = 0.5f, y = 1.35f) { false }
        val r = s.result!!
        assertTrue(r.won && r.endedEarly && r.isBoss)
        assertEquals(0L, s.bossHealth)
    }

    @Test
    fun bossMovesTelegraphsAttacksAndGetsAngry() {
        val rec = Rec()
        val s = session(bossStage(health = 1_500, kind = BossKind.REI), rec)
        val b = s.boss!!
        val start = b.x to b.y
        s.update(playStart + 3_000)
        assertTrue(start != (b.x to b.y), "the boss wanders")
        assertTrue(b.x - b.radius >= 0f && b.x + b.radius <= 1f && b.y - b.radius >= 0f && b.y + b.radius <= 1.4f)
        tapUntil(s, playStart + 3_000, gapMs = 120) { s.matchTimeMs > 40_000 }
        assertTrue(rec.attacks.isNotEmpty(), "attacks happen")
        // Every attack was announced first.
        for (i in rec.bossLog.indices) {
            if (rec.bossLog[i].startsWith("attack:")) assertEquals("warn:" + rec.bossLog[i].removePrefix("attack:"), rec.bossLog[i - 1])
        }
        assertTrue(b.rage >= 1, "hurt past half health it is angrier")
        assertEquals(listOf(1, 2).take(rec.rages.size), rec.rages)
    }

    @Test
    fun aShieldedBossBlocksTaps() {
        val s = session(bossStage(kind = BossKind.PUNHO, health = 100_000))
        val b = s.boss!!
        var t = playStart
        while (!b.shielded && t < playStart + 60_000) { t += 20; s.update(t) }
        assertTrue(b.shielded)
        val before = s.score
        assertFalse(s.tap(t + 5, b.x, b.y), "a tap on the shield does nothing")
        assertEquals(before, s.score)
    }

    @Test
    fun theStopwatchStealsTime() {
        val rec = Rec()
        val s = session(bossStage(kind = BossKind.CRONOMETRO, health = 100_000), rec)
        var t = playStart
        while (BossAttack.CLOCK !in rec.attacks && t < playStart + 60_000) { t += 20; s.update(t) }
        assertTrue(BossAttack.CLOCK in rec.attacks)
        assertEquals(GameBalance.MATCH_DURATION_MS - GameBalance.BOSS_CLOCK_MS, s.durationMs)
    }

    @Test
    fun bossesFollowTheLadder() {
        assertEquals(BossKind.FURIOSO, StageCatalog.stage(10).boss)
        assertEquals(BossKind.PUNHO, StageCatalog.stage(20).boss)
        assertEquals(BossKind.REI, StageCatalog.stage(50).boss)
        assertEquals(BossKind.FURIOSO, StageCatalog.stage(60).boss)
        assertEquals(1, StageCatalog.stage(60).bossTier)
        assertEquals(null, StageCatalog.stage(11).boss)
    }

    private class Rec : GameListener {
        var objectives = 0
        val kinds = mutableListOf<TapKind>()
        val attacks = mutableListOf<BossAttack>()
        val bossLog = mutableListOf<String>()
        val rages = mutableListOf<Int>()
        override fun onObjectiveComplete() { objectives++ }
        override fun onTap(x: Float, y: Float, points: Int, kind: TapKind, zone: Zone?, comboTier: Int) { kinds += kind }
        override fun onBossWarning(attack: BossAttack) { bossLog += "warn:$attack" }
        override fun onBossAttack(attack: BossAttack) {
            attacks += attack
            bossLog += "attack:$attack"
        }
        override fun onBossRage(rage: Int) { rages += rage }
    }

    @Suppress("unused")
    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}
