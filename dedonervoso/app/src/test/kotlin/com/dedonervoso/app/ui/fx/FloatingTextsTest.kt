package com.dedonervoso.app.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Test

/** Popup merging only touches the pooled arrays, so it runs as a plain JVM test (paints are for drawing). */
class FloatingTextsTest {
    private val plus = FloatingTexts.Label { "+$it" }

    @Test
    fun burstOnOneSpotMergesIntoARunningTotal() {
        val ft = FloatingTexts(1f, emptyArray())
        ft.addMerged(KEY, 3, plus, 100f, 100f, 0, 16f)
        ft.update(0.1f)
        ft.addMerged(KEY, 4, plus, 110f, 105f, 0, 16f)
        ft.update(0.1f)
        ft.addMerged(KEY, 5, plus, 95f, 98f, 0, 16f)
        assertEquals(listOf("+12"), ft.textsForTest())
    }

    @Test
    fun otherSpotsKeysAndPausesStartNewPopups() {
        val ft = FloatingTexts(1f, emptyArray())
        ft.addMerged(KEY, 2, plus, 100f, 100f, 0, 16f)
        ft.addMerged(KEY, 2, plus, 400f, 400f, 0, 16f)
        ft.addMerged(KEY + 1, 2, plus, 100f, 100f, 0, 16f)
        ft.add("PLAIN", 100f, 100f, 0, 16f)
        assertEquals(4, ft.textsForTest().size)
        // After the merge window a new burst starts its own popup.
        ft.update(0.35f)
        ft.addMerged(KEY, 7, plus, 100f, 100f, 0, 16f, lifeS = 1f)
        assertEquals("+7", ft.textsForTest().last())
    }

    @Test
    fun longBurstsSplitSoTheNumbersKeepMoving() {
        val ft = FloatingTexts(1f, emptyArray())
        repeat(10) { k ->
            if (k > 0) ft.update(0.15f)
            ft.addMerged(KEY, 1, plus, 100f, 100f, 0, 16f)
        }
        // A popup absorbs taps for at most ~1.1 s (taps 0..7 here); the burst then continues in a fresh one.
        assertEquals(listOf("+8", "+2"), ft.textsForTest())
    }

    private companion object {
        const val KEY = 7
    }
}
