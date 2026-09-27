package com.taptap.game.core.stage

import com.taptap.game.core.engine.GameBalance
import com.taptap.game.core.engine.PenaltyTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StageCatalogTest {
    @Test
    fun everyStageIsValid() {
        for (n in 1..300) {
            val s = StageCatalog.stage(n)
            assertEquals(n, s.number)
            assertTrue(s.target >= 0, "stage $n target")
            assertTrue(s.star2Score < s.star3Score, "stage $n stars")
            assertTrue(s.comboTimeoutMs >= GameBalance.COMBO_TIMEOUT_HARD_MS, "stage $n combo timeout")
            s.stop?.let { assertTrue(it.graceMs >= GameBalance.MIN_REACTION_GRACE_MS, "stage $n grace") }
            if (s.type == StageType.PRECISION || s.type == StageType.PERFECT) assertNotNull(s.zones, "stage $n needs zones")
            if (s.type == StageType.SURVIVAL) assertNotNull(s.stop, "stage $n needs STOP")
            if (s.type == StageType.FRENZY) assertNotNull(s.frenzy, "stage $n needs frenzy")
            assertEquals(StageCatalog.isBoss(n), s.isBoss)
        }
    }

    @Test
    fun mechanicsAreIntroducedInTheSpecOrder() {
        for (n in 1..5) {
            assertNull(StageCatalog.stage(n).stop, "no STOP before stage 6")
            assertNull(StageCatalog.stage(n).zones)
        }
        assertNotNull(StageCatalog.stage(6).stop)
        assertNull(StageCatalog.stage(10).zones, "zones start at 11")
        assertNotNull(StageCatalog.stage(11).zones)
        assertEquals(0f, StageCatalog.stage(20).zones!!.driftChance, "zones move from 21")
        assertTrue(StageCatalog.stage(21).zones!!.driftChance > 0f)
        assertEquals(0f, StageCatalog.stage(23).stop!!.fakeChance)
        assertTrue(StageCatalog.stage(24).stop!!.fakeChance > 0f)
        assertEquals(PenaltyTier.LIGHT, StageCatalog.stage(7).penaltyTier)
        assertEquals(PenaltyTier.MEDIUM, StageCatalog.stage(15).penaltyTier)
        assertEquals(PenaltyTier.HEAVY, StageCatalog.stage(35).penaltyTier)
        assertEquals(Mechanic.STOP, StageCatalog.stage(6).introduces)
        assertEquals(Mechanic.HOT_ZONES, StageCatalog.stage(11).introduces)
    }

    @Test
    fun laterStagesAreFasterAndLessForgiving() {
        val early = StageCatalog.stage(7).stop!!
        val mid = StageCatalog.stage(22).stop!!
        val late = StageCatalog.stage(60).stop!!
        assertTrue(early.warningMs > mid.warningMs && mid.warningMs > late.warningMs)
        assertTrue(early.graceMs > mid.graceMs && mid.graceMs >= late.graceMs)
        assertTrue(StageCatalog.stage(60).comboTimeoutMs < StageCatalog.stage(3).comboTimeoutMs)
    }

    @Test
    fun proceduralStagesVaryObjectives() {
        val types = (31..60).map { StageCatalog.stage(it).type }.toSet()
        assertTrue(types.size >= 6, "variety: $types")
    }
}
