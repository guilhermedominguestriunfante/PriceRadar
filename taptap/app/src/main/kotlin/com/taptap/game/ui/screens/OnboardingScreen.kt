package com.taptap.game.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.taptap.game.GameApp
import com.taptap.game.core.audio.Sfx
import com.taptap.game.core.util.Ease
import com.taptap.game.ui.Button
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.Screen
import kotlin.math.sin

/**
 * First-run tutorial (spec §31): three quick animated cards, then READY? → stage 1.
 * Deliberately short — the game keeps teaching through stage intros.
 */
class OnboardingScreen(app: GameApp) : Screen(app) {
    private var page = 0
    private var pageAge = 0f

    override fun layout() {
        val skip = button(s.skip, null, Button.Style.GHOST) { finish() }
        skip.rect.set(safe.right - 90f * u, safe.top, safe.right, safe.top + 44f * u)
        skip.visible = page < PAGES
        if (page == PAGES) {
            val w = kotlin.math.min(width * 0.7f, 300f * u)
            val start = button(s.start, null, Button.Style.PRIMARY, Palette.CYAN) { finish() }
            start.rect.set(width / 2f - w / 2f, height * 0.62f, width / 2f + w / 2f, height * 0.62f + 66f * u)
            start.pulse = true
        }
    }

    private fun finish() {
        app.progression.completeOnboarding()
        app.host.replace(PlayScreen.forStage(app, 1))
    }

    override fun onTouch(e: MotionEvent): Boolean {
        if (page < PAGES && e.actionMasked == MotionEvent.ACTION_UP && buttons.none { it.contains(e.x, e.y) } && pageAge > 0.35f) {
            page++
            pageAge = 0f
            app.sfx.play(Sfx.UI_CLICK)
            app.haptics.click()
            relayout()
            return true
        }
        return super.onTouch(e)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        pageAge += dt
        ui.background.update(dt)
        ui.rings.update(dt)
        ui.particles.update(dt)
        ui.popups.update(dt)
        // Demo feedback on the tapping page.
        if (page == 0 && ((pageAge % 0.3f) < dt)) {
            val x = width / 2f + sin(pageAge * 3f) * 30f * u
            val y = height * 0.42f
            ui.rings.add(x, y, 8f, 50f, Palette.CYAN, 0.35f, 3f)
            ui.particles.burst(x, y, 5, Palette.S_CYAN, 60f, 220f, 3f, 6f, 0.35f)
            ui.popups.add("+1", x, y - 20f * u, Palette.WHITE, 20f, 0.6f, 80f, 0)
        }
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.7f, 0.72f)
        val cx = width / 2f
        val cy = height * 0.42f
        val a = Ease.outCubic((pageAge / 0.35f).coerceIn(0f, 1f))
        val r = 70f * u
        when (page) {
            0 -> {
                ui.neon.glowBlob(c, cx, cy, r * 2.2f, Palette.CYAN, 0.35f)
                ui.neon.circleStroke(c, cx, cy, r, Palette.CYAN, 3f * u)
                ui.icons.draw(c, Icon.BOLT, cx, cy, r, Palette.CYAN)
            }
            1 -> {
                val blink = 0.5f + 0.5f * sin(pageAge * 6f)
                ui.neon.glowBlob(c, cx, cy, r * 2.4f, Palette.RED, 0.3f + 0.25f * blink)
                ui.neon.octagon(c, cx, cy, r, Palette.RED, Palette.WHITE, 4f * u)
                ui.icons.draw(c, Icon.HAND, cx, cy - r * 0.2f, r * 0.72f, Palette.WHITE)
                val p = ui.style(ui.displayPaint, 26f, Palette.WHITE, Paint.Align.CENTER)
                c.drawText("STOP", cx, cy + r * 0.55f, p)
            }
            2 -> {
                val beat = pageAge % 1f
                ui.neon.glowBlob(c, cx, cy, r * 2f, Palette.MAGENTA, 0.35f)
                ui.neon.circle(c, cx, cy, r, Palette.withAlpha(Palette.MAGENTA, 0.2f))
                ui.neon.circleStroke(c, cx, cy, r, Palette.MAGENTA, 3f * u)
                ui.neon.circleStroke(c, cx, cy, r * (1f + beat * 0.5f), Palette.withAlpha(Palette.MAGENTA, 1f - beat), 2f * u)
                val p = ui.style(ui.displayPaint, 34f, Palette.WHITE, Paint.Align.CENTER)
                c.drawText("x3", cx, cy + p.textSize * 0.36f, p)
            }
            else -> {
                val p = ui.style(ui.displayPaint, 54f, Palette.WHITE, Paint.Align.CENTER)
                ui.neon.glowText(c, s.ready, cx, cy + p.textSize * 0.36f, p, Palette.CYAN, 22f * u)
            }
        }
        ui.rings.draw(c)
        ui.particles.draw(c)
        ui.popups.draw(c)
        if (page < PAGES) {
            val text = when (page) {
                0 -> s.onboarding1
                1 -> s.onboarding2
                else -> s.onboarding3
            }
            val p = ui.style(ui.displayPaint, 20f, Palette.withAlpha(Palette.WHITE, a), Paint.Align.CENTER)
            var y = height * 0.66f
            for (line in text.split('\n')) {
                ui.fitText(c, line, cx, y, p, width - 40f * u)
                y += 30f * u
            }
            // Page dots + hint.
            for (i in 0 until PAGES) {
                ui.neon.circle(c, cx + (i - 1) * 18f * u, height * 0.82f, 4f * u, if (i == page) Palette.CYAN else Palette.MUTED)
            }
            val hp = ui.style(ui.textPaint, 14f, Palette.withAlpha(Palette.DIM, 0.6f + 0.4f * sin(ui.time * 4f)), Paint.Align.CENTER)
            c.drawText(s.tapToContinue, cx, height * 0.87f, hp)
        }
        drawButtons(c)
    }

    override fun onBack(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val PAGES = 3
    }
}
