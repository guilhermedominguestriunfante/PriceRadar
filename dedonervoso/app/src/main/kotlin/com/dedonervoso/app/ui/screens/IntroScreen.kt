package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.fx.Shake
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.util.Ease
import kotlin.math.min

/**
 * Opening (≈1.7 s, any touch skips): the glove finger drops and taps, the ripple and the "Dedo
 * Nervoso" name land with the nervous tremor, then [next] shows the game. With reduced effects
 * there is no shake or flicker and it is shorter.
 */
class IntroScreen(app: GameApp, private val next: () -> Unit) : Screen(app) {
    private val shake = Shake(app.ui.density)
    private var tapped = false
    private var done = false
    private var tapY = 0f
    private var nameY = 0f
    private var nameSize = 0f
    private var handScale = 0f
    private val reduce get() = app.settings.reduceEffects
    private val total get() = if (reduce) 1.2f else TOTAL_S

    override fun onEnter() {
        age = 0f
        shake.enabled = !reduce
    }

    override fun layout() {
        val w = width
        nameSize = min(w * 0.2f, 96f * u)
        nameY = height * 0.36f
        tapY = height * 0.64f
        handScale = min(w, height) * 0.0042f
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.5f)
        ui.particles.update(dt)
        ui.rings.update(dt)
        shake.update(dt)
        if (!tapped && age >= TAP_AT_S) {
            tapped = true
            app.sfx.play(Sfx.INTRO)
            app.haptics.click()
            ui.rings.add(width / 2f, tapY, 10f, 150f, Palette.MAGENTA, 0.6f, 5f)
            ui.rings.add(width / 2f, tapY, 6f, 90f, Palette.CYAN, 0.45f, 3f)
            ui.particles.burst(width / 2f, tapY, 36, Palette.S_CYAN, 140f, 520f, 3f, 8f, 0.6f)
            shake.add(0.45f)
        }
        if (age >= total) finish()
    }

    override fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) finish()
        return true
    }

    override fun onBack(): Boolean {
        finish()
        return true
    }

    private fun finish() {
        if (done) return
        done = true
        next()
    }

    override fun draw(c: Canvas) {
        c.save()
        c.translate(shake.offsetX, shake.offsetY)
        drawBackdrop(c, gridAlpha = 0.5f * Ease.outCubic((age / 0.6f).coerceIn(0f, 1f)), horizon = 0.72f)
        val fadeOut = ((total - age) / 0.3f).coerceIn(0f, 1f)
        val cx = width / 2f

        // The finger falls and taps at TAP_AT_S.
        val handAlpha: Float
        val tipY: Float
        if (age < TAP_AT_S) {
            val t = age / TAP_AT_S
            tipY = -height * 0.1f + (tapY - -height * 0.1f) * t * t
            handAlpha = 1f
        } else {
            // A small recoil, then it fades before the name settles above it.
            val t = ((age - TAP_AT_S) / 0.28f).coerceIn(0f, 1f)
            tipY = tapY - Ease.outCubic(t) * 14f * u
            handAlpha = (1f - t) * fadeOut
        }
        if (handAlpha > 0f) {
            ui.neon.glowBlob(c, cx, tapY, 120f * u, Palette.PURPLE, 0.35f * handAlpha)
            ui.brand.hand(c, cx, tipY, handScale, handAlpha, if (reduce) 0f else 1f)
        }

        // The name slams in right after the tap.
        val nameT = ((age - TAP_AT_S - 0.05f) / 0.3f).coerceIn(0f, 1f)
        if (nameT > 0f) {
            val slam = Ease.outBack(nameT, 2.4f)
            val size = nameSize * (1.5f - 0.5f * slam)
            val alpha = nameT * fadeOut
            ui.neon.glowBlob(c, cx, nameY, nameSize * 2.8f, Palette.MAGENTA, 0.22f * alpha)
            val y2 = ui.brand.wordmark(c, cx, nameY, size, width * 0.9f, alpha, ui.time + 1.3f, if (reduce) 0f else 1.4f)
            val tp = ui.style(ui.textPaint, 14f, Palette.withAlpha(Palette.DIM, alpha * ((age - 0.9f) / 0.3f).coerceIn(0f, 1f)), Paint.Align.CENTER)
            tp.letterSpacing = 0.25f
            c.drawText(s.tagline.uppercase(), cx, y2 + nameSize * 0.6f, tp)
            tp.letterSpacing = 0.03f
        }
        ui.rings.draw(c)
        ui.particles.draw(c)
        c.restore()
    }

    companion object {
        private const val TAP_AT_S = 0.38f
        private const val TOTAL_S = 1.75f
    }
}
