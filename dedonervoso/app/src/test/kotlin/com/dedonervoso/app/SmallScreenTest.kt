package com.dedonervoso.app

import com.dedonervoso.app.ui.screens.PlayScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Layout must fit small phones (spec §45): everything on screen, nothing overlapping. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h568dp-xhdpi")
class SmallScreenTest {
    @Test
    fun homeAndPlayFitOnSmallPhone() {
        val h = GameHarness().launch(GameHarness.progressedSave())
        h.screenshot("40_small_home")
        val v = h.activity.view
        for (b in h.buttons().filter { it.visible && it.scroll == null }) {
            assertTrue("button '${b.label}' inside screen: ${b.rect}", b.rect.left >= 0f && b.rect.right <= v.width && b.rect.top >= 0f && b.rect.bottom <= v.height)
        }
        h.click(h.app.strings.play, 60)
        val play = h.app.host.current as PlayScreen
        if (play.inIntro) h.tap(v.width / 2f, v.height / 2f, 10)
        h.frames(220)
        h.screenshot("41_small_play")
        assertTrue(play.arenaForTest.height() > v.height * 0.4f)
    }
}
