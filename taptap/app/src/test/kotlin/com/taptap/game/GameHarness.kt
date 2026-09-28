package com.taptap.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.taptap.game.core.engine.GameState
import com.taptap.game.core.save.SaveCodec
import com.taptap.game.core.save.SaveData
import com.taptap.game.platform.SaveStore
import com.taptap.game.ui.Button
import com.taptap.game.ui.screens.PlayScreen
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.time.Duration

/** Drives the real activity under Robolectric: frames, touches, screenshots, match bot. */
class GameHarness {
    lateinit var controller: ActivityController<MainActivity>
    val activity: MainActivity get() = controller.get()
    val app: GameApp get() = activity.app

    val saveFile: File get() = File(RuntimeEnvironment.getApplication().filesDir, SaveStore.FILE_NAME)

    fun launch(seed: SaveData? = null): GameHarness {
        // Deliver vsync at a real 60 Hz cadence as the virtual clock advances (the game loop
        // re-posts its frame callback every frame; immediate vsync would spin forever).
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        if (seed != null) saveFile.writeText(SaveCodec.encode(seed))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        frames(40)
        return this
    }

    /** Advances the virtual clock one ~60 Hz frame at a time. */
    fun frames(n: Int, ms: Long = 16) {
        repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    }

    fun touch(action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        activity.view.dispatchTouchEvent(e)
        e.recycle()
    }

    fun tap(x: Float, y: Float, settle: Int = 30) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        frames(2)
        touch(MotionEvent.ACTION_UP, x, y)
        frames(settle)
    }

    fun buttons(): List<Button> = app.host.current!!.buttonsForTest

    fun button(label: String): Button = buttons().firstOrNull { it.label == label && it.visible }
        ?: error("No button '$label' on ${app.host.current!!::class.simpleName}: ${buttons().map { it.label }}")

    fun click(b: Button, settle: Int = 40) {
        val off = b.scroll?.offset ?: 0f
        tap(b.rect.centerX(), b.rect.centerY() - off, settle)
    }

    fun click(label: String, settle: Int = 40) = click(button(label), settle)

    fun screenshot(name: String) {
        val v = activity.view
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("taptap.screenshots") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /**
     * Plays the current [PlayScreen] like a human: ~9 taps/s, aims at zones, stops on the STOP
     * warning and resumes after "TAP!". Returns when the screen changes (results).
     */
    fun playMatch(onFrame: (PlayScreen, Int) -> Unit = { _, _ -> }, tapIntervalMs: Int = 110, respectStop: Boolean = true) {
        val play = app.host.current as PlayScreen
        if (play.inIntro) {
            tap(activity.view.width / 2f, activity.view.height / 2f, 10)
        }
        var t = 0
        var nextTap = 0
        var holdUntil = 0
        while (app.host.current === play && t < 90_000) {
            frames(1)
            t += 16
            val sess = play.sessionForTest ?: continue
            onFrame(play, t)
            if (sess.state == GameState.STOP || (respectStop && sess.warningActive)) {
                holdUntil = t + 260
                continue
            }
            if (t < nextTap || t < holdUntil || !sess.state.isActive) continue
            val arena = play.arenaForTest
            val zone = sess.zones.firstOrNull { it.active }
            val at = sess.activeTimeMs
            val x: Float
            val y: Float
            if (zone != null && (t / 110) % 2 == 0) {
                x = arena.left + zone.x(at) * arena.width()
                y = arena.top + zone.y(at) * arena.width()
            } else {
                x = arena.centerX() + ((t * 7919) % 101 - 50) * 0.6f
                y = arena.centerY() + ((t * 104729) % 101 - 50) * 0.6f
            }
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_UP, x, y)
            nextTap = t + tapIntervalMs
        }
        frames(60)
    }

    companion object {
        /** A save with some progress so every menu has content. */
        fun progressedSave(): SaveData {
            val d = SaveData()
            d.onboardingDone = true
            d.profile.nickname = "KAWE"
            d.profile.avatar = 2
            d.profile.createdAt = 1_700_000_000_000L
            d.coins = 1_234
            d.totalXp = 5_600
            d.highestUnlocked = 13
            d.selectedStage = 12
            for (n in 1..12) d.stageStars[n] = 1 + n % 3
            for (n in 1..12) d.stageBest[n] = 1_000L + n * 537
            d.stats.totalTaps = 23_456
            d.stats.totalTouches = 15_000
            d.stats.matches = 31
            d.stats.wins = 19
            d.stats.bestScore = 7_890
            d.stats.maxCombo = 412
            d.stats.maxTps = 11.3f
            d.stats.perfects = 87
            d.stats.stopsSurvived = 25
            d.stats.stopErrors = 4
            d.stats.zoneHits = 240
            d.stats.playTimeMs = 31L * 60_000
            d.stats.coinsEarned = 3_456
            d.stats.frenzies = 14
            d.stats.reflexBestMs = 231
            d.stats.bossesDefeated = 1
            d.upgrades[com.taptap.game.core.progression.UpgradeId.DOUBLE_TAP] = 1
            d.upgrades[com.taptap.game.core.progression.UpgradeId.COMBO_BOOST] = 2
            d.achievements["first_tap"] = 1L
            d.achievements["tap_machine"] = 1L
            d.achievements["tap_master"] = 1L
            d.achievements["first_win"] = 1L
            d.achievements["dont_blink"] = 1L
            d.achievements["boss_slayer"] = 1L
            d.settings.music = false
            // Ranking spread over today, this week and older days so every scope has rows.
            val clock = com.taptap.game.core.progression.GameClock.System
            val now = clock.nowMs()
            val scores = longArrayOf(7_890, 7_444, 6_977, 6_440, 5_903, 4_220, 3_150, 2_009, 1_537, 980)
            for ((i, score) in scores.withIndex()) {
                val at = now - i * 26L * 3_600_000L
                d.ranking += com.taptap.game.core.progression.RankEntry(
                    score = score, stage = 12 - i, maxCombo = 400 - i * 30, maxTps = 11.3f - i * 0.4f,
                    taps = 900L - i * 40, timestamp = at, dayIndex = clock.dayIndex(at), daily = i == 3,
                )
            }
            return d
        }
    }
}
