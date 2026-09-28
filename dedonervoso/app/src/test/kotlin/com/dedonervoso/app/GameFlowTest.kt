package com.dedonervoso.app

import android.content.Intent
import android.view.MotionEvent
import com.dedonervoso.core.engine.GameState
import com.dedonervoso.core.save.SaveCodec
import com.dedonervoso.core.save.SaveData
import com.dedonervoso.app.platform.Links
import com.dedonervoso.app.ui.screens.HomeScreen
import com.dedonervoso.app.ui.screens.IntroScreen
import com.dedonervoso.app.ui.screens.OnboardingScreen
import com.dedonervoso.app.ui.screens.PlayScreen
import com.dedonervoso.app.ui.screens.ProfileScreen
import com.dedonervoso.app.ui.screens.RankingScreen
import com.dedonervoso.app.ui.screens.ResultScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** End-to-end flows on the real activity (Robolectric, real Skia rendering). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class GameFlowTest {

    private fun freshSave() = SaveData().also { it.settings.music = false }

    @Test
    fun firstLaunchOnboardingThenFullMatchThenPersisted() {
        val h = GameHarness().launch(freshSave())
        assertTrue(h.app.host.current is OnboardingScreen)
        h.screenshot("01_onboarding_1")
        val w = h.activity.view.width / 2f
        val y = h.activity.view.height / 2f
        h.tap(w, y, 20)
        h.screenshot("02_onboarding_2")
        h.tap(w, y, 20)
        h.screenshot("03_onboarding_3")
        h.tap(w, y, 20)
        h.screenshot("04_onboarding_ready")
        h.click(h.app.strings.start, 60)
        val play = h.app.host.current as PlayScreen
        h.screenshot("05_intro_tap")
        var shotPlaying = false
        h.playMatch(onFrame = { p, t ->
            val s = p.sessionForTest!!
            if (!shotPlaying && s.state == GameState.PLAYING && s.combo > 60) {
                shotPlaying = true
                h.screenshot("06_playing_stage1")
            }
        })
        assertTrue("reached results", h.app.host.current is ResultScreen)
        h.frames(200)
        h.screenshot("07_result_stage1")
        val save = h.app.progression.save
        assertEquals(2, save.highestUnlocked)
        assertTrue(save.coins > 0)
        assertTrue(save.stats.totalTaps > 200)
        assertTrue(save.onboardingDone)
        assertTrue(play.sessionForTest!!.result!!.won)

        // Close the app completely and reopen: progress must be there.
        h.controller.pause().stop().destroy()
        assertTrue(h.saveFile.exists())
        val restored = SaveCodec.decode(h.saveFile.readText())
        assertEquals(2, restored.highestUnlocked)
        val h2 = GameHarness().launch()
        assertTrue(h2.app.host.current is HomeScreen)
        assertEquals(save.coins, h2.app.progression.save.coins)
        assertEquals(2, h2.app.progression.save.selectedStage)
        h2.screenshot("08_home_after_first_win")
    }

    @Test
    fun pauseOnBackgroundAndResumeWithCountdown() {
        val h = GameHarness().launch(GameHarness.progressedSave())
        h.click(h.app.strings.play, 60)
        val play = h.app.host.current as PlayScreen
        if (play.inIntro) h.tap(200f, 600f, 10)
        h.frames(200)
        val sess = play.sessionForTest!!
        assertTrue(sess.state.isActive)
        val before = sess.matchTimeMs
        h.controller.pause()
        assertTrue(play.isPaused)
        h.frames(300)
        assertEquals("time frozen while paused", before, sess.matchTimeMs)
        h.controller.resume()
        h.screenshot("20_pause_overlay")
        h.click(h.app.strings.resume, 5)
        assertEquals(GameState.COUNTDOWN, sess.state)
        h.frames(170)
        assertTrue(sess.state.isActive)
    }

    @Test
    fun matchWithStopZonesAndFrenzyRenders() {
        val save = GameHarness.progressedSave()
        save.seenIntros += com.dedonervoso.core.stage.Mechanic.values()
        val h = GameHarness().launch(save)
        h.click(h.app.strings.play, 60)
        val shots = HashSet<String>()
        h.playMatch(onFrame = { p, _ ->
            val s = p.sessionForTest!!
            fun shot(key: String) {
                if (shots.add(key)) h.screenshot(key)
            }
            when {
                s.state == GameState.COUNTDOWN && s.matchTimeMs == 0L -> shot("10_countdown")
                s.state == GameState.STOP -> shot("12_stop")
                s.warningActive -> shot("11_stop_warning")
                s.state == GameState.FRENZY -> shot("14_frenzy")
                s.zones.count { it.active } >= 1 && s.combo > 30 -> shot("13_zones")
            }
        })
        assertTrue(h.app.host.current is ResultScreen)
        h.frames(220)
        h.screenshot("15_result_stage12")
        val r = h.app.progression.save.stats
        assertTrue(r.zoneHits > 240)
    }

    @Test
    fun menusRenderAndNavigate() {
        val h = GameHarness().launch(GameHarness.progressedSave())
        h.screenshot("30_home")
        val s = h.app.strings
        for ((label, name) in listOf(
            s.upgrades to "31_shop", s.ranking to "32_ranking", s.achievements to "33_achievements",
            s.profile to "34_profile", s.missions to "35_missions",
        )) {
            h.click(label, 60)
            // The ranking opens on its ONLINE tab; the local results are under THIS DEVICE.
            if (h.app.host.current is RankingScreen) h.click(s.deviceTab, 20)
            h.screenshot(name)
            // Layout runs again after onEnter loaded the data: lists are scrollable to their end
            // and the ranking's empty-state PLAY button is absent when there are results.
            val screen = h.app.host.current!!
            for (scroll in screen.scrollsForTest) {
                assertTrue("$name content height covers its rows", scroll.contentHeight > 0f)
            }
            if (screen is RankingScreen) {
                assertTrue("no empty-state button with results", h.buttons().none { it.label == s.play })
                assertTrue("ranking rows scroll", screen.scrollsForTest.single().contentHeight > screen.scrollsForTest.single().rect.height())
                h.click(s.onlineTab, 10)
            }
            if (screen is ProfileScreen) {
                val scroll = screen.scrollsForTest.single()
                assertTrue("every statistic reachable", scroll.contentHeight > scroll.rect.height())
            }
            h.activity.onBackPressed()
            h.frames(40)
            assertTrue("back to home from $name", h.app.host.current is HomeScreen)
        }
        h.click(h.buttons().first { it.icon == com.dedonervoso.app.ui.Icon.GEAR }, 60)
        h.screenshot("36_settings")
        h.activity.onBackPressed()
        h.frames(40)
        // Stage map via the stage card.
        val card = h.buttons().filter { it.label.isEmpty() && it.icon == null }.maxByOrNull { it.rect.top }!!
        h.click(card, 60)
        h.screenshot("37_stage_select")
    }

    @Test
    fun openingPlaysThenOpensTheGameByItself() {
        val h = GameHarness().launch(GameHarness.progressedSave(), skipIntro = false)
        assertTrue("opening first", h.app.host.current is IntroScreen)
        h.frames(10)
        h.screenshot("00_intro_name")
        h.frames(120)
        assertTrue("then Home without any touch", h.app.host.current is HomeScreen)
    }

    @Test
    fun developerButtonOpensLinkedIn() {
        val h = GameHarness().launch(GameHarness.progressedSave())
        h.click(h.app.strings.developer, 10)
        val intent = shadowOf(h.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Links.DEVELOPER_LINKEDIN, intent.dataString)
        assertEquals("https://www.linkedin.com/in/guilhermekawe/", intent.dataString)
    }

    @Test
    fun onlyTwoSimultaneousFingersCount() {
        val save = GameHarness.progressedSave()
        save.seenIntros += com.dedonervoso.core.stage.Mechanic.values()
        save.selectedStage = 1
        val h = GameHarness().launch(save)
        h.click(h.app.strings.play, 60)
        val play = h.app.host.current as PlayScreen
        h.frames(170)
        val sess = play.sessionForTest!!
        assertEquals(GameState.PLAYING, sess.state)
        val t = android.os.SystemClock.uptimeMillis()
        val props = Array(3) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(3) { i -> MotionEvent.PointerCoords().apply { x = 300f + i * 80f; y = 900f } }
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        h.activity.view.dispatchTouchEvent(down)
        val second = MotionEvent.obtain(t, t, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        h.activity.view.dispatchTouchEvent(second)
        val third = MotionEvent.obtain(t, t, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 3, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        h.activity.view.dispatchTouchEvent(third)
        assertEquals("third finger ignored", 2, sess.touches)
    }
}
