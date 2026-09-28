package com.dedonervoso.core.engine

import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.stage.StageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live duel rules inside a match: falling orbs, capturing by tap rate, throwing and receiving items. */
class DuelSessionTest {
    private val t0 = 10_000L
    private val playStart = t0 + GameBalance.COUNTDOWN_MS

    /** Just taps: no STOP, zones or frenzy, so rates and points are easy to reason about. */
    private val plain = StageConfig(number = StageCatalog.DUEL, type = StageType.SCORE, target = 0, scoreTarget = 0, star2Score = 0, star3Score = 0)

    private class Events : GameListener {
        val log = ArrayList<String>()
        override fun onOrbSpawn(orb: DuelOrb) { log += "spawn ${orb.item}" }
        override fun onOrbCaptured(orb: DuelOrb) { log += "captured ${orb.item}" }
        override fun onOrbMissed(orb: DuelOrb) { log += "missed ${orb.item}" }
        override fun onItemUsed(item: DuelItem) { log += "used $item" }
        override fun onItemHit(item: DuelItem) { log += "hit $item" }
        override fun onInterruptStart(kind: InterruptKind) { log += "start $kind" }
        override fun onStopFault(x: Float, y: Float, penaltyPoints: Int, lifeLost: Boolean, repeat: Boolean) { log += "fault" }
    }

    private fun duel(stage: StageConfig = plain, seed: Long = 7L, events: GameListener = GameListener.NONE) =
        GameSession(stage, Loadout.NONE, 1.4f, seed, events, duel = true).also {
            it.start(t0)
            it.update(playStart)
        }

    /** Taps steadily at [tps] from match time [fromMs] to [toMs], one frame per tap. */
    private fun tapAt(s: GameSession, tps: Float, fromMs: Long, toMs: Long) {
        val step = (1000f / tps).toLong()
        var t = playStart + fromMs
        while (t < playStart + toMs) {
            s.tap(t, 0.5f, 0.7f)
            s.update(t)
            t += step
        }
        s.update(playStart + toMs)
    }

    @Test
    fun bothPlayersGetTheSameOrbsWithEveryItemKind() {
        val a = duel(seed = 1234L).orbs
        val b = duel(seed = 1234L).orbs
        assertEquals(GameBalance.DUEL_ORB_TIMES_MS.size, a.size)
        assertEquals(a.map { it.item to it.spawnAtMs }, b.map { it.item to it.spawnAtMs })
        assertEquals(a.map { it.x }, b.map { it.x })
        assertTrue(DuelItem.values().all { kind -> a.any { it.item == kind } })
        assertTrue(a.all { it.x in 0.2f..0.8f && it.expireAtMs - it.spawnAtMs == GameBalance.DUEL_ORB_LIFETIME_MS })
        assertTrue(GameSession(plain, Loadout.NONE, 1.4f, 1L).orbs.isEmpty(), "no orbs outside duels")
    }

    @Test
    fun sustainingTheRateCapturesTheOrb() {
        val events = Events()
        val s = duel(events = events)
        val orb = s.orbs.first()
        s.update(playStart + orb.spawnAtMs)
        assertEquals(OrbState.FALLING, orb.state)
        assertEquals(orb, s.activeOrb)
        // Fast enough for any item (15 taps/s) for the required time, plus the rate window's warm-up.
        tapAt(s, 15f, orb.spawnAtMs, orb.spawnAtMs + orb.item.holdMs + 1_600)
        assertEquals(OrbState.CAPTURED, orb.state)
        assertEquals(orb.item, s.heldItem)
        assertTrue("captured ${orb.item}" in events.log)

        assertEquals(orb.item, s.useItem(playStart + orb.spawnAtMs + 6_000))
        assertNull(s.heldItem)
        assertNull(s.useItem(playStart + orb.spawnAtMs + 6_100), "each item is thrown once")
        assertEquals(1, s.itemsUsed)
    }

    @Test
    fun tappingTooSlowlyNeverCapturesAndTheOrbFallsAway() {
        val s = duel()
        val orb = s.orbs.first()
        val slowRate = orb.item.requiredTps - 3f
        tapAt(s, slowRate, orb.spawnAtMs, orb.expireAtMs + 100)
        assertEquals(OrbState.MISSED, orb.state)
        assertNull(s.heldItem)
    }

    @Test
    fun holdingAnItemBlocksTheNextCapture() {
        val s = duel()
        val (first, second) = s.orbs
        tapAt(s, 15f, first.spawnAtMs, first.spawnAtMs + first.item.holdMs + 1_600)
        assertEquals(first.item, s.heldItem)
        tapAt(s, 15f, second.spawnAtMs, second.expireAtMs + 100)
        assertEquals(OrbState.MISSED, second.state)
        assertEquals(0f, second.progress)
        assertEquals(first.item, s.heldItem)
    }

    @Test
    fun clockHitTakesThreeSecondsButNeverTheLastOne() {
        val events = Events()
        val s = duel(events = events)
        s.update(playStart + 10_000)
        assertEquals(50_000L, s.timeLeftMs)
        assertTrue(s.receiveItem(DuelItem.CLOCK, playStart + 10_000))
        assertEquals(47_000L, s.timeLeftMs)
        assertEquals("hit CLOCK", events.log.last())
        // 1.5 s left: a CLOCK can only take half a second of it.
        s.update(playStart + 55_500)
        s.receiveItem(DuelItem.CLOCK, playStart + 55_500)
        assertEquals(GameBalance.DUEL_MIN_TIME_LEFT_MS, s.timeLeftMs)
        s.receiveItem(DuelItem.CLOCK, playStart + 55_500)
        assertEquals(GameBalance.DUEL_MIN_TIME_LEFT_MS, s.timeLeftMs)
        s.update(playStart + 56_500)
        assertEquals(GameState.FINISHED, s.state)
        assertEquals(3_000L + 500L, s.clockLostMs)
        assertFalse(s.receiveItem(DuelItem.SLOW, playStart + 57_000), "nothing lands after the end")
    }

    @Test
    fun slowHitHalvesPointsForFiveSeconds() {
        val s = duel()
        s.update(playStart + 1_000)
        s.receiveItem(DuelItem.SLOW, playStart + 1_000)
        assertTrue(s.slowActive)
        val before = s.score
        s.tap(playStart + 1_100, 0.5f, 0.7f)
        s.update(playStart + 1_100)
        val slowPoints = s.score - before
        s.update(playStart + 6_100)
        assertFalse(s.slowActive)
        val mid = s.score
        s.tap(playStart + 6_200, 0.5f, 0.7f)
        s.update(playStart + 6_200)
        // A single fresh tap is worth 1 point normally; at half value it rounds up to the 1-point floor
        // only when the combo multiplier is 1, so compare with a combo running instead.
        assertTrue(slowPoints <= s.score - mid, "slowed tap ($slowPoints) not worth more than a normal one (${s.score - mid})")

        // With a combo running the halving is visible.
        val t = duel()
        tapAt(t, 10f, 1_000, 6_000)
        val normal = run {
            val b = t.score
            t.tap(playStart + 6_000, 0.5f, 0.7f)
            t.score - b
        }
        t.receiveItem(DuelItem.SLOW, playStart + 6_050)
        val b = t.score
        t.tap(playStart + 6_100, 0.5f, 0.7f)
        val halved = t.score - b
        assertEquals(kotlin.math.max(1L, Math.round(normal * GameBalance.DUEL_SLOW_FACTOR.toDouble())), halved)
    }

    @Test
    fun stopHitFreezesTheOpponentWithAWarningFirst() {
        val events = Events()
        val s = duel(events = events)
        s.update(playStart + 5_000)
        s.receiveItem(DuelItem.STOP, playStart + 5_000)
        s.update(playStart + 5_000 + GameBalance.DUEL_STOP_WARNING_MS - 1)
        assertEquals(GameState.PLAYING, s.state, "warning shows first")
        s.update(playStart + 5_000 + GameBalance.DUEL_STOP_WARNING_MS)
        assertEquals(GameState.STOP, s.state)
        assertTrue("start STOP" in events.log)
        // Tapping through it is punished like any STOP (after the reaction grace).
        s.tap(playStart + 5_000 + GameBalance.DUEL_STOP_WARNING_MS + 500, 0.5f, 0.7f)
        assertTrue("fault" in events.log)
        s.update(playStart + 5_000 + GameBalance.DUEL_STOP_WARNING_MS + GameBalance.DUEL_STOP_MS)
        assertEquals(GameState.PLAYING, s.state)
    }

    @Test
    fun stopHitStillLandsInTheClosingSecondsAndAfterARunningStop() {
        val s = duel()
        s.update(playStart + 57_000)
        s.receiveItem(DuelItem.STOP, playStart + 57_000)
        s.update(playStart + 57_000 + GameBalance.DUEL_STOP_WARNING_MS)
        assertEquals(GameState.STOP, s.state, "thrown STOPs ignore the quiet closing margin")

        val events = Events()
        val t = duel(events = events)
        t.update(playStart + 5_000)
        t.receiveItem(DuelItem.STOP, playStart + 5_000)
        t.update(playStart + 5_700)
        assertEquals(GameState.STOP, t.state)
        t.receiveItem(DuelItem.STOP, playStart + 5_700)   // a second one queues behind the first
        t.update(playStart + 5_000 + GameBalance.DUEL_STOP_WARNING_MS + GameBalance.DUEL_STOP_MS + 1)
        assertEquals(GameState.PLAYING, t.state)
        t.update(playStart + 12_000)
        assertEquals(2, events.log.count { it == "start STOP" })
    }

    @Test
    fun theDuelArenaPlaysAFullMatchWithItsOwnEvents() {
        val s = GameSession(StageCatalog.duel(), Loadout.NONE, 1.4f, 99L, GameListener.NONE, duel = true)
        s.start(t0)
        var t = playStart
        var thrown = 0
        while (s.state != GameState.FINISHED && t < playStart + 70_000) {
            s.tap(t, 0.5f, 0.7f)
            s.update(t)
            if (s.heldItem != null) {
                s.useItem(t)
                thrown++
            }
            t += 60   // ~16 taps/s: strong enough to catch every orb it can
        }
        val r = assertNotNull(s.result)
        assertEquals(StageCatalog.DUEL, r.stageNumber)
        assertTrue(s.plannedInterrupts >= 3, "the arena has STOPs")
        assertTrue(thrown >= 2, "a fast player catches orbs ($thrown)")
        assertTrue(r.score > 0)
    }
}
