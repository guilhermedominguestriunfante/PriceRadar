package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.fx.Particles
import com.dedonervoso.app.ui.fx.Shake
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.util.Ease
import kotlin.math.sin

/**
 * Opening (≈3 s, any touch skips), on the mascot illustration: the scene rises out of the dark
 * with a riser, the finger slams the phone on the beat (flash, shake, sparks, a shockwave on the
 * glowing TAP!), the "CLICA! CLICA! VAI!" shouts pulse along, then [next] shows the game. With
 * reduced effects there is no flash or shake and it is shorter.
 */
class IntroScreen(app: GameApp, private val next: () -> Unit) : Screen(app) {
    private val shake = Shake(app.ui.density)
    private var slammed = false
    private var beats = 0
    private var done = false
    private var flash = 0f
    private val reduce get() = app.settings.reduceEffects
    private val total get() = if (reduce) 2f else TOTAL_S
    private val fadePaint = Paint()
    private val vignettePaint = Paint()

    override fun onEnter() {
        age = 0f
        shake.enabled = !reduce
        app.sfx.play(Sfx.INTRO_SLAM)
    }

    override fun layout() {
        vignettePaint.shader = LinearGradient(0f, height * 0.55f, 0f, height, 0x00000000, 0xF0050208.toInt(), Shader.TileMode.CLAMP)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.particles.update(dt)
        ui.rings.update(dt)
        shake.update(dt)
        flash = (flash - dt * 3.5f).coerceAtLeast(0f)
        val art = ui.art
        val hero = art.hero
        if (!slammed && age >= SLAM_S) {
            slammed = true
            app.haptics.celebrate()
            if (!reduce) flash = 1f
            shake.add(0.7f)
            if (hero != null) {
                val x = art.lastX(art.heroTapX)
                val y = art.lastY(art.heroTapY)
                ui.rings.add(x, y, 10f, 220f, Palette.GOLD, 0.7f, 6f)
                ui.rings.add(x, y, 6f, 140f, Palette.ORANGE, 0.5f, 4f)
                ui.particles.burst(x, y, 60, Palette.S_GOLD, 200f, 900f, 3f, 9f, 0.9f, Particles.SPARK)
                ui.particles.burst(x, y, 30, Palette.S_ORANGE, 120f, 600f, 3f, 8f, 0.7f, Particles.SPARK, angle = -1.57f, spread = 2.6f)
            }
        }
        // The shouts in the picture pulse on the beats after the slam.
        if (hero != null && beats < SHOUT_X.size && age >= SLAM_S + 0.3f + beats * BEAT_S) {
            val x = art.lastX(SHOUT_X[beats])
            val y = art.lastY(SHOUT_Y[beats])
            val red = beats == SHOUT_X.lastIndex
            ui.rings.add(x, y, 12f, 90f, if (red) Palette.RED else Palette.WHITE, 0.45f, 3f)
            ui.particles.burst(x, y, 14, if (red) Palette.S_RED else Palette.S_WHITE, 80f, 300f, 2f, 6f, 0.5f, Particles.SPARK)
            shake.add(0.12f)
            app.haptics.click()
            beats++
        }
        // Embers keep drifting up from the impact.
        if (hero != null && slammed && !reduce && (age * 20f).toInt() % 3 == 0) {
            ui.particles.burst(
                art.lastX(art.heroTapX), art.lastY(art.heroTapY), 1, Palette.S_ORANGE, 40f, 160f, 2f, 5f, 1.2f,
                Particles.SPARK, angle = -1.57f, spread = 1.2f,
            )
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
        c.drawColor(BLACK)
        val art = ui.art
        val hero = art.hero
        c.save()
        c.translate(shake.offsetX, shake.offsetY)
        if (hero != null) {
            // Rises out of the dark zoomed on the fingertip; a punch on the slam, then a slow push.
            val zoom = if (age < SLAM_S) {
                1.32f - 0.2f * Ease.outCubic(age / SLAM_S)
            } else {
                val t = age - SLAM_S
                val punch = 0.06f * (1f - t / 0.35f).coerceIn(0f, 1f) * sin(t * 30f).coerceAtLeast(0f)
                1.0f + 0.05f * t / total - punch
            }
            val alpha = (age / 0.9f).coerceIn(0f, 1f)
            art.drawCover(c, hero, width, height, alignY = 0.45f, zoom = zoom, focusX = art.heroTapX, focusY = art.heroTapY, alpha = alpha)
            // The TAP! on the phone glows after the slam.
            if (slammed) {
                val pulse = if (reduce) 0.6f else 0.75f + 0.25f * sin(age * 12f)
                val glow = (1f - (age - SLAM_S) / 1.4f).coerceIn(0.25f, 1f) * pulse
                ui.neon.glowBlob(c, art.lastX(0.47f), art.lastY(0.8f), width * 0.45f, Palette.ORANGE, 0.5f * glow)
            }
            c.drawRect(0f, 0f, width, height, vignettePaint)
        }
        ui.rings.draw(c)
        ui.particles.draw(c)
        c.restore()

        // Tagline under the scene once it has landed.
        val tagA = ((age - SLAM_S - 0.5f) / 0.4f).coerceIn(0f, 1f) * ((total - age) / 0.3f).coerceIn(0f, 1f)
        if (tagA > 0f) {
            val tp = ui.style(ui.displayBoldPaint, 14f, Palette.withAlpha(Palette.WHITE, tagA), Paint.Align.CENTER)
            tp.letterSpacing = 0.3f
            ui.neon.glowText(c, s.tagline.uppercase(), width / 2f, safe.bottom - 40f * u, tp, Palette.withAlpha(Palette.ORANGE, tagA), 10f * u)
            tp.letterSpacing = 0f
        }
        if (flash > 0f) {
            fadePaint.color = Palette.withAlpha(0xFFFFF4E0.toInt(), flash * 0.8f)
            c.drawRect(0f, 0f, width, height, fadePaint)
        }
        // Fade to the game.
        val out = ((age - (total - 0.3f)) / 0.3f).coerceIn(0f, 1f)
        if (out > 0f) {
            fadePaint.color = Palette.withAlpha(BLACK, out)
            c.drawRect(0f, 0f, width, height, fadePaint)
        }
    }

    companion object {
        /** When the finger hits the phone: the impact of Sfx.INTRO_SLAM. */
        private const val SLAM_S = 0.9f
        private const val BEAT_S = 0.35f
        private const val TOTAL_S = 2.9f
        private const val BLACK = 0xFF050208.toInt()

        /** "CLICA!", "CLICA!", "VAI!" in the illustration (fractions of the image). */
        private val SHOUT_X = floatArrayOf(0.19f, 0.25f, 0.24f)
        private val SHOUT_Y = floatArrayOf(0.21f, 0.31f, 0.4f)
    }
}
