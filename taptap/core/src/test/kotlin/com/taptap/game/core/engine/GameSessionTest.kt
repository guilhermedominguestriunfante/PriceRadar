package com.taptap.game.core.engine

import com.taptap.game.core.stage.FrenzyConfig
import com.taptap.game.core.stage.StageConfig
import com.taptap.game.core.stage.StageType
import com.taptap.game.core.stage.StopConfig
import com.taptap.game.core.stage.ZoneConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameSessionTest {

    private val t0 = 10_000L
    private val playStart = t0 + GameBalance.COUNTDOWN_MS

    private fun plainStage(
        type: StageType = StageType.SCORE,
        target: Int = 100,
        stop: StopConfig? = null,
        zones: ZoneConfig? = null,
        frenzy: FrenzyConfig? = null,
        lives: Int = 0,
        penalty: PenaltyTier = PenaltyTier.LIGHT,
        comboTimeoutMs: Long = 2_000L,
    ) = StageConfig(
        number = 1, type = type, target = target, scoreTarget = target, star2Score = target * 2, star3Score = target * 3,
        lives = lives, stop = stop, zones = zones, frenzy = frenzy, comboTimeoutMs = comboTimeoutMs, penaltyTier = penalty,
    )

    private fun session(
        stage: StageConfig,
        loadout: Loadout = Loadout.NONE,
        listener: GameListener = GameListener.NONE,
        seed: Long = 42L,
    ) = GameSession(stage, loadout, arenaHeight = 1.4f, seed = seed, listener = listener).also {
        it.start(t0)
        it.update(playStart)
    }

    @Test
    fun countdownRunsThreeTwoOneThenPlay() {
        val rec = Recorder()
        val s = GameSession(plainStage(), Loadout.NONE, 1.4f, 1L, rec)
        s.start(t0)
        assertEquals(GameState.COUNTDOWN, s.state)
        assertFalse(s.tap(t0 + 100, 0.5f, 0.5f), "taps during countdown are ignored")
        s.update(t0 + GameBalance.COUNTDOWN_MS - 1)
        assertEquals(GameState.COUNTDOWN, s.state)
        s.update(t0 + GameBalance.COUNTDOWN_MS)
        assertEquals(GameState.PLAYING, s.state)
        assertEquals(listOf(3, 2, 1, 0), rec.countdowns)
        assertEquals(0L, s.score)
    }

    @Test
    fun tapsScoreAndComboTiersApply() {
        val s = session(plainStage())
        var t = playStart
        repeat(9) { t += 100; assertTrue(s.tap(t, 0.5f, 0.5f)) }
        assertEquals(9L, s.score)
        assertEquals(9, s.combo)
        assertEquals(0, s.comboTier)
        t += 100
        s.tap(t, 0.5f, 0.5f) // 10th tap reaches tier 1 (x1.2 → rounds to 1)
        assertEquals(1, s.comboTier)
        assertEquals(1.2f, s.comboMultiplier)
        repeat(15) { t += 100; s.tap(t, 0.5f, 0.5f) } // combo 25 → x1.5
        assertEquals(2, s.comboTier)
        assertEquals(25L, s.taps)
    }

    @Test
    fun doubleTapDoublesTapValueAndScore() {
        val single = session(plainStage())
        val double = session(plainStage(), Loadout(tapValue = 2))
        var t = playStart
        repeat(5) {
            t += 150
            single.tap(t, 0.5f, 0.5f)
            double.tap(t, 0.5f, 0.5f)
        }
        assertEquals(5L, single.taps)
        assertEquals(10L, double.taps)
        assertEquals(single.score * 2, double.score)
        assertEquals(5, double.touches)
    }

    @Test
    fun comboBreaksAfterTimeout() {
        val rec = Recorder()
        val s = session(plainStage(comboTimeoutMs = 1_000L), listener = rec)
        var t = playStart
        repeat(12) { t += 100; s.tap(t, 0.5f, 0.5f) }
        assertEquals(12, s.combo)
        s.update(t + 999)
        assertEquals(12, s.combo)
        s.update(t + 1_000)
        assertEquals(0, s.combo)
        assertEquals(listOf(BreakReason.TIMEOUT), rec.breaks)
        assertEquals(12, s.maxCombo)
    }

    @Test
    fun comboShieldAbsorbsOneBreak() {
        val rec = Recorder()
        val s = session(plainStage(comboTimeoutMs = 1_000L), Loadout(comboShields = 1), rec)
        var t = playStart
        repeat(15) { t += 100; s.tap(t, 0.5f, 0.5f) }
        s.update(t + 1_000)
        assertEquals(15, s.combo, "shield keeps the combo")
        assertEquals(listOf(ShieldType.COMBO), rec.shields)
        s.update(t + 2_000)
        assertEquals(0, s.combo, "second break is not shielded")
    }

    @Test
    fun matchLastsSixtySecondsOfPlayIgnoringPauses() {
        val rec = Recorder()
        val s = session(plainStage(target = 1), listener = rec)
        s.update(playStart + 20_000)
        s.pause(playStart + 20_000)
        assertEquals(GameState.PAUSED, s.state)
        s.update(playStart + 50_000) // 30 s paused: must not count
        assertEquals(20_000L, s.matchTimeMs)
        s.resume(playStart + 50_000)
        assertEquals(GameState.COUNTDOWN, s.state)
        s.update(playStart + 50_000 + GameBalance.COUNTDOWN_MS)
        assertEquals(GameState.PLAYING, s.state)
        val resumedAt = playStart + 50_000 + GameBalance.COUNTDOWN_MS
        s.update(resumedAt + 39_999)
        assertEquals(GameState.PLAYING, s.state)
        s.update(resumedAt + 40_000)
        assertEquals(GameState.FINISHED, s.state)
        assertEquals(60_000L, s.result!!.playedMs)
        assertEquals(listOf(10, 5, 4, 3, 2, 1), rec.timeWarnings)
    }

    @Test
    fun largeFrameGapsStillEndExactly() {
        val s = session(plainStage(target = 1))
        s.update(playStart + 120_000)
        assertEquals(GameState.FINISHED, s.state)
        assertEquals(60_000L, s.result!!.playedMs)
    }

    // ---- STOP ---------------------------------------------------------------------------------

    private val stopOnce = StopConfig(1, 1, 2_000, 2_000, warningMs = 500, graceMs = 250, minGapMs = 5_000)

    private fun stopStage(penalty: PenaltyTier = PenaltyTier.LIGHT, lives: Int = 0) =
        plainStage(stop = stopOnce, penalty = penalty, lives = lives)

    /** Advances frame by frame until the STOP starts; returns the real time it started at. */
    private fun untilStop(s: GameSession, from: Long): Long {
        var t = from
        while (s.state != GameState.STOP) {
            t += 5
            s.update(t)
            check(t < from + 70_000) { "no STOP happened" }
        }
        return t
    }

    @Test
    fun stopWarnsThenHoldsThenResumes() {
        val rec = Recorder()
        val s = session(stopStage(), listener = rec)
        val stopAt = untilStop(s, playStart)
        assertEquals(listOf("warn:STOP", "start:STOP"), rec.interruptLog)
        assertEquals(InterruptKind.STOP, s.holdKind)
        s.update(stopAt + 2_000)
        assertEquals(GameState.PLAYING, s.state)
        assertEquals(listOf("warn:STOP", "start:STOP", "end:STOP:true"), rec.interruptLog)
        assertEquals(1, s.stopsSurvived)
    }

    @Test
    fun tapsInsideReactionGraceAreIgnoredNotPunished() {
        val rec = Recorder()
        val s = session(stopStage(), listener = rec)
        var t = playStart
        repeat(20) { t += 100; s.tap(t, 0.5f, 0.5f) }
        val stopAt = untilStop(s, t)
        val scoreBefore = s.score
        assertFalse(s.tap(stopAt + 100, 0.5f, 0.5f))
        assertFalse(s.tap(stopAt + 240, 0.5f, 0.5f))
        assertEquals(scoreBefore, s.score)
        assertEquals(0, s.stopErrors)
        assertEquals(2, rec.ignored.count { it == IgnoreReason.REACTION_GRACE })
    }

    @Test
    fun lateDeliveredTapFromBeforeStopStillCounts() {
        val s = session(stopStage())
        untilStop(s, playStart)
        val before = s.score
        // No pauses: real time = playStart + match time. Event stamped 3 ms before the STOP
        // began, but delivered after the frame that started it.
        val stopStartReal = playStart + s.currentInterrupt!!.startAt
        assertTrue(s.tap(stopStartReal - 3, 0.5f, 0.5f))
        assertTrue(s.score > before)
        assertEquals(0, s.stopErrors)
    }

    @Test
    fun tapDuringStopIsAFaultWithLightPenalty() {
        val rec = Recorder()
        val s = session(stopStage(PenaltyTier.LIGHT), listener = rec)
        var t = playStart
        repeat(40) { t += 100; s.tap(t, 0.5f, 0.5f) }
        val stopAt = untilStop(s, t)
        val combo = s.combo
        val score = s.score
        assertFalse(s.tap(stopAt + 400, 0.5f, 0.5f))
        assertEquals(1, s.stopErrors)
        assertEquals(combo, s.combo, "LIGHT tier keeps the combo")
        assertEquals(score - GameBalance.STOP_PENALTY_MIN_POINTS[0], s.score)
        // A second tap in the same STOP only costs a little.
        s.tap(stopAt + 500, 0.5f, 0.5f)
        assertEquals(1, s.stopErrors)
        assertEquals(listOf(false, true), rec.faults.map { it.second })
        s.update(stopAt + 2_000)
        assertEquals(0, s.stopsSurvived)
    }

    @Test
    fun mediumPenaltyBreaksComboAndCostsTaps() {
        val s = session(stopStage(PenaltyTier.MEDIUM), Loadout(tapValue = 2))
        var t = playStart
        repeat(30) { t += 100; s.tap(t, 0.5f, 0.5f) }
        val stopAt = untilStop(s, t)
        val taps = s.taps
        s.tap(stopAt + 400, 0.5f, 0.5f)
        assertEquals(0, s.combo)
        assertEquals(taps - GameBalance.STOP_TAP_LOSS * 2, s.taps)
    }

    @Test
    fun heavyPenaltyWithLivesCanFailTheStage() {
        val rec = Recorder()
        val s = session(stopStage(PenaltyTier.HEAVY, lives = 1), listener = rec)
        val stopAt = untilStop(s, playStart)
        s.tap(stopAt + 400, 0.5f, 0.5f)
        assertEquals(GameState.FINISHED, s.state)
        val result = s.result!!
        assertFalse(result.won)
        assertEquals(FailReason.NO_LIVES, result.failReason)
        assertEquals(listOf(0), rec.livesLost)
    }

    @Test
    fun stopShieldIgnoresTheAccidentalTap() {
        val rec = Recorder()
        val s = session(stopStage(PenaltyTier.HEAVY, lives = 3), Loadout(stopShields = 1), rec)
        val stopAt = untilStop(s, playStart)
        s.tap(stopAt + 400, 0.5f, 0.5f)
        s.tap(stopAt + 500, 0.5f, 0.5f)
        assertEquals(0, s.stopErrors)
        assertEquals(3, s.lives)
        assertEquals(listOf(ShieldType.STOP), rec.shields)
        s.update(stopAt + 2_000)
        assertEquals(1, s.stopsSurvived)
    }

    @Test
    fun pausingDuringStopEndsItAndResumesSafely() {
        val s = session(stopStage(PenaltyTier.HEAVY, lives = 1))
        val stopAt = untilStop(s, playStart)
        s.pause(stopAt + 300)
        assertEquals(GameState.PAUSED, s.state)
        s.resume(stopAt + 5_000)
        s.update(stopAt + 5_000 + GameBalance.COUNTDOWN_MS)
        assertEquals(GameState.PLAYING, s.state)
        assertTrue(s.tap(stopAt + 5_000 + GameBalance.COUNTDOWN_MS + 1, 0.5f, 0.5f), "first tap after resume is valid")
        assertEquals(0, s.stopErrors)
    }

    @Test
    fun pausingDuringWarningReplaysTheWarningAfterResume() {
        val rec = Recorder()
        val s = session(stopStage(), listener = rec)
        var t = playStart
        while (!s.warningActive) { t += 5; s.update(t) }
        s.pause(t)
        s.resume(t + 1_000)
        var t2 = t + 1_000 + GameBalance.COUNTDOWN_MS
        s.update(t2)
        assertFalse(s.warningActive)
        while (s.state != GameState.STOP) { t2 += 5; s.update(t2); check(t2 < t + 70_000) }
        assertEquals(2, rec.interruptLog.count { it == "warn:STOP" })
        assertEquals(1, s.interruptsSeen)
    }

    @Test
    fun matchEndingDuringStopCountsItAsSurvived() {
        val late = StopConfig(1, 1, 1_500, 1_500, warningMs = 200, graceMs = 200, minGapMs = 1_000)
        val stage = StageConfig(
            number = 1, type = StageType.SURVIVAL, target = 0, scoreTarget = 0, star2Score = 1, star3Score = 2,
            durationMs = 12_000L, lives = 1, stop = late,
        )
        val s = session(stage)
        s.update(playStart + 12_000)
        val result = s.result
        assertNotNull(result)
        assertEquals(1, result.stopsSurvived)
        assertTrue(result.won)
    }

    // ---- frenzy ----------------------------------------------------------------------------------

    @Test
    fun frenzyStartsWhenMeterFillsAndDoublesPoints() {
        val rec = Recorder()
        val s = session(plainStage(frenzy = FrenzyConfig(fillScale = 5f, megaChance = 0f)), listener = rec)
        var t = playStart
        while (s.state != GameState.FRENZY) {
            t += 100
            s.tap(t, 0.5f, 0.5f)
            check(t < playStart + 30_000)
        }
        assertEquals(listOf(false), rec.frenzies)
        val before = s.score
        val mult = s.comboMultiplier
        t += 100
        s.tap(t, 0.5f, 0.5f)
        assertEquals((1 * s.comboMultiplier * GameBalance.FRENZY_MULTIPLIER).toInt().coerceAtLeast(1).toLong(),
            s.score - before, "multiplier $mult")
        s.update(t + GameBalance.FRENZY_DURATION_MS)
        assertEquals(GameState.PLAYING, s.state)
        assertEquals(1, rec.frenzyEnds)
    }

    @Test
    fun frenzyAndStopNeverOverlap() {
        val stop = StopConfig(4, 4, 1_500, 1_500, warningMs = 400, graceMs = 250, minGapMs = 2_000)
        val rec = Recorder()
        val s = session(plainStage(stop = stop, frenzy = FrenzyConfig(fillScale = 8f, megaChance = 0f)), listener = rec)
        var t = playStart
        var sawFrenzy = false
        while (s.state != GameState.FINISHED) {
            t += 50
            s.update(t)
            if (s.state != GameState.STOP) s.tap(t, 0.5f, 0.5f)
            if (s.state == GameState.FRENZY) {
                sawFrenzy = true
                assertFalse(s.warningActive, "no STOP warning during frenzy")
            }
        }
        assertTrue(sawFrenzy)
        assertTrue(rec.interruptLog.any { it.startsWith("start:STOP") })
    }

    // ---- zones -----------------------------------------------------------------------------------

    private val zoneConfig = ZoneConfig(
        spawnMinMs = 500, spawnMaxMs = 500, maxConcurrent = 1, lifeMinMs = 4_000, lifeMaxMs = 4_000,
        weights = ZoneConfig.weights(ZoneType.X3 to 1),
    )

    private fun firstZone(s: GameSession, from: Long): Pair<Zone, Long> {
        var t = from
        while (true) {
            t += 10
            s.update(t)
            s.zones.firstOrNull { it.active }?.let { return it to t }
            check(t < from + 10_000)
        }
    }

    @Test
    fun tappingAHotZoneMultipliesAndCenterIsPerfect() {
        val rec = Recorder()
        val s = session(plainStage(zones = zoneConfig), listener = rec)
        val (zone, t) = firstZone(s, playStart)
        val zt = s.activeTimeMs
        val x = zone.x(zt)
        val y = zone.y(zt)
        // Edge of the zone: zone hit, not perfect (x3).
        val before = s.score
        s.tap(t + 1, x + zone.radius(zt) * 0.9f, y)
        assertEquals(3L, s.score - before)
        assertEquals(TapKind.ZONE, rec.tapKinds.last())
        // Dead centre: perfect.
        s.tap(t + 2, x, y)
        assertEquals(TapKind.PERFECT, rec.tapKinds.last())
        assertEquals(1, s.perfects)
        assertEquals(2, s.zoneHits)
        // Far away: normal tap.
        s.tap(t + 3, if (x > 0.5f) 0.02f else 0.98f, if (y > 0.7f) 0.02f else 1.38f)
        assertEquals(TapKind.NORMAL, rec.tapKinds.last())
    }

    @Test
    fun lockedZoneTurnsOutsideTapsIntoMisses() {
        val locked = ZoneConfig(
            spawnMinMs = 500, spawnMaxMs = 500, maxConcurrent = 1, lifeMinMs = 4_000, lifeMaxMs = 4_000,
            weights = ZoneConfig.weights(ZoneType.X2 to 1), lockChance = 1f,
        )
        val rec = Recorder()
        val s = session(plainStage(zones = locked), listener = rec)
        var t = playStart
        repeat(5) { t += 50; s.tap(t, 0.5f, 0.5f) }
        val (zone, tz) = firstZone(s, t)
        assertTrue(zone.locked)
        assertTrue(s.lockActive)
        val zt = s.activeTimeMs
        val outsideX = if (zone.x(zt) > 0.5f) 0.02f else 0.98f
        val outsideY = if (zone.y(zt) > 0.7f) 0.02f else 1.38f
        assertFalse(s.tap(tz + 1, outsideX, outsideY))
        assertEquals(1, rec.misses)
        assertEquals(0, s.combo)
    }

    @Test
    fun timeZoneAddsTime() {
        val timeZones = ZoneConfig(
            spawnMinMs = 500, spawnMaxMs = 500, maxConcurrent = 1, lifeMinMs = 4_000, lifeMaxMs = 4_000,
            weights = ZoneConfig.weights(ZoneType.TIME to 1),
        )
        val s = session(plainStage(zones = timeZones))
        val (zone, t) = firstZone(s, playStart)
        val zt = s.activeTimeMs
        s.tap(t + 1, zone.x(zt), zone.y(zt))
        assertEquals(GameBalance.MATCH_DURATION_MS + GameBalance.TIME_BONUS_MS, s.durationMs)
        assertFalse(zone.active, "TIME zones are single use")
    }

    @Test
    fun zonesFreezeDuringStop() {
        val drifting = ZoneConfig(
            spawnMinMs = 300, spawnMaxMs = 300, maxConcurrent = 1, lifeMinMs = 30_000, lifeMaxMs = 30_000,
            weights = ZoneConfig.weights(ZoneType.X2 to 1), driftChance = 1f, speed = 0.3f,
        )
        val s = session(plainStage(stop = stopOnce, zones = drifting))
        val stopAt = untilStop(s, playStart)
        val zone = s.zones.first { it.active }
        val x1 = zone.x(s.activeTimeMs)
        s.update(stopAt + 1_500)
        assertEquals(x1, zone.x(s.activeTimeMs), "zone must not move while STOP holds")
    }

    // ---- anti-cheat ------------------------------------------------------------------------------

    @Test
    fun impossibleBurstsAreRateLimited() {
        val rec = Recorder()
        val s = session(plainStage(), listener = rec)
        val t = playStart + 1_000
        var accepted = 0
        repeat(50) { if (s.tap(t, 0.5f, 0.5f)) accepted++ }
        assertEquals(GameBalance.TAP_BUCKET_CAPACITY, accepted)
        assertEquals(50 - accepted, s.droppedTaps)
    }

    @Test
    fun sustainedRateIsCappedAtHumanMaximum() {
        val s = session(plainStage())
        var t = playStart
        var accepted = 0
        repeat(1_000) { // 100 taps/s for 10 s
            t += 10
            if (s.tap(t, 0.5f, 0.5f)) accepted++
        }
        assertTrue(accepted <= 10 * 25 + GameBalance.TAP_BUCKET_CAPACITY, "accepted $accepted")
        assertTrue(s.tps.maxTps <= GameBalance.MAX_HUMAN_TPS + 1f)
    }

    @Test
    fun thirdFingerAndFutureTimestampsAreIgnored() {
        val rec = Recorder()
        val s = session(plainStage(), listener = rec)
        assertFalse(s.tap(playStart + 10, 0.5f, 0.5f, activePointers = 3))
        assertFalse(s.tap(playStart + 10_000, 0.5f, 0.5f, receivedAtMs = playStart + 15))
        assertEquals(listOf(IgnoreReason.TOO_MANY_POINTERS, IgnoreReason.BAD_TIMESTAMP), rec.ignored)
        assertTrue(s.tap(playStart + 20, 0.5f, 0.5f, activePointers = 2))
    }

    @Test
    fun sameSeedGivesSameMatchTimeline() {
        fun timeline(seed: Long): List<String> {
            val rec = Recorder()
            val stop = StopConfig(3, 5, 800, 2_000, warningMs = 300, graceMs = 200, reflexChance = 0.3f, fakeChance = 0.3f)
            val s = session(plainStage(stop = stop, zones = zoneConfig), listener = rec, seed = seed)
            var t = playStart
            while (s.state != GameState.FINISHED) { t += 16; s.update(t) }
            return rec.interruptLog + rec.zoneLog
        }
        assertEquals(timeline(7L), timeline(7L))
        assertTrue(timeline(7L) != timeline(8L))
    }

    @Test
    fun speedObjectiveWinsWithStars() {
        val stage = StageConfig(
            number = 1, type = StageType.SPEED, target = 50, scoreTarget = 50, star2Score = 60, star3Score = 1_000,
        )
        val s = session(stage)
        var t = playStart
        repeat(80) { t += 120; s.tap(t, 0.5f, 0.5f) }
        s.update(playStart + 60_000)
        val r = s.result!!
        assertTrue(r.won)
        assertEquals(2, r.stars)
        assertEquals(80L, r.taps)
    }

    @Test
    fun abortedMatchIsALoss() {
        val s = session(plainStage(target = 1))
        s.tap(playStart + 100, 0.5f, 0.5f)
        s.abort(playStart + 200)
        assertEquals(FailReason.ABORTED, s.result!!.failReason)
        assertFalse(s.result!!.won)
    }
}

/** Captures listener callbacks for assertions. */
class Recorder : GameListener {
    val countdowns = mutableListOf<Int>()
    val breaks = mutableListOf<BreakReason>()
    val shields = mutableListOf<ShieldType>()
    val ignored = mutableListOf<IgnoreReason>()
    val interruptLog = mutableListOf<String>()
    val zoneLog = mutableListOf<String>()
    val faults = mutableListOf<Pair<Int, Boolean>>()
    val livesLost = mutableListOf<Int>()
    val frenzies = mutableListOf<Boolean>()
    val tapKinds = mutableListOf<TapKind>()
    val timeWarnings = mutableListOf<Int>()
    var frenzyEnds = 0
    var misses = 0

    override fun onCountdown(value: Int, resuming: Boolean) { countdowns += value }
    override fun onComboBreak(combo: Int, reason: BreakReason) { breaks += reason }
    override fun onShieldUsed(type: ShieldType) { shields += type }
    override fun onTapIgnored(x: Float, y: Float, reason: IgnoreReason) { ignored += reason }
    override fun onInterruptWarning(kind: InterruptKind) { interruptLog += "warn:$kind" }
    override fun onInterruptStart(kind: InterruptKind) { interruptLog += "start:$kind" }
    override fun onInterruptEnd(kind: InterruptKind, clean: Boolean) { interruptLog += "end:$kind:$clean" }
    override fun onFakeStopReveal() { interruptLog += "fake" }
    override fun onStopFault(x: Float, y: Float, penaltyPoints: Int, lifeLost: Boolean, repeat: Boolean) { faults += penaltyPoints to repeat }
    override fun onLifeLost(livesLeft: Int) { livesLost += livesLeft }
    override fun onFrenzyStart(mega: Boolean) { frenzies += mega }
    override fun onFrenzyEnd() { frenzyEnds++ }
    override fun onTap(x: Float, y: Float, points: Int, kind: TapKind, zone: Zone?, comboTier: Int) { tapKinds += kind }
    override fun onMiss(x: Float, y: Float) { misses++ }
    override fun onZoneSpawn(zone: Zone) { zoneLog += "spawn:${zone.type}:${zone.spawnAt}" }
    override fun onTimeWarning(secondsLeft: Int) { timeWarnings += secondsLeft }
}
