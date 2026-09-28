package com.dedonervoso.app.ui.fx

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.dedonervoso.app.ui.Neon
import com.dedonervoso.app.ui.Palette
import kotlin.math.sin

/**
 * Animated synthwave backdrop: deep gradient sky, twinkling stars and a perspective grid
 * flowing toward the viewer. [tint] recolours it (STOP red, frenzy rainbow, boss purple).
 */
class Background(private val dp: Float, private val neon: Neon) {
    private var w = 0f
    private var h = 0f
    private var sky: LinearGradient? = null
    private val skyPaint = Paint()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starX = FloatArray(STARS)
    private val starY = FloatArray(STARS)
    private val starPhase = FloatArray(STARS)
    private val starSize = FloatArray(STARS)
    private var time = 0f
    private var scroll = 0f

    /** Grid colour (defaults to cyan/magenta mix). */
    var tint: Int = Palette.MAGENTA
    var tint2: Int = Palette.CYAN
    /** Grid speed multiplier (frenzy speeds it up, STOP freezes it). */
    var speed = 1f
    /** 0..1 fraction of the height where the horizon sits. */
    var horizon = 0.62f
    var gridAlpha = 1f

    init {
        var s = 0x2468ACE
        fun r(): Float {
            s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
            return (s and 0xFFFFFF) / 16777216f
        }
        for (i in 0 until STARS) {
            starX[i] = r()
            starY[i] = r()
            starPhase[i] = r() * 6.28f
            starSize[i] = 0.6f + r() * 1.4f
        }
    }

    fun resize(width: Float, height: Float) {
        if (width == w && height == h) return
        w = width
        h = height
        sky = LinearGradient(0f, 0f, 0f, height, intArrayOf(Palette.BG_TOP, Palette.BG_MID, Palette.BG_BOTTOM), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    fun update(dt: Float) {
        time += dt
        scroll = (scroll + dt * 0.35f * speed) % 1f
    }

    fun draw(c: Canvas) {
        skyPaint.shader = sky
        c.drawRect(0f, 0f, w, h, skyPaint)
        val hy = h * horizon
        // Stars (upper sky only).
        for (i in 0 until STARS) {
            val sy = starY[i] * hy * 0.95f
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * 1.7f + starPhase[i]))
            starPaint.color = Palette.withAlpha(Palette.WHITE, tw * 0.8f)
            c.drawCircle(starX[i] * w, sy, starSize[i] * dp * 0.7f, starPaint)
        }
        // Horizon glow.
        neon.glowBlob(c, w / 2f, hy, w * 0.75f, tint, 0.28f * gridAlpha)
        neon.glowBlob(c, w / 2f, hy, w * 0.3f, tint2, 0.22f * gridAlpha)
        // Perspective grid.
        val bottom = h
        val depth = bottom - hy
        gridPaint.strokeWidth = 1.2f * dp
        for (i in 0 until H_LINES) {
            val t = ((i + scroll) / H_LINES)
            val yy = hy + depth * t * t
            gridPaint.color = Palette.withAlpha(Palette.mix(tint, tint2, t), (t * 0.75f + 0.05f) * gridAlpha)
            c.drawLine(0f, yy, w, yy, gridPaint)
        }
        val cx = w / 2f
        for (k in -V_LINES..V_LINES) {
            val xb = cx + k * w * 0.16f
            val xt = cx + k * w * 0.012f
            gridPaint.color = Palette.withAlpha(tint2, 0.35f * gridAlpha)
            c.drawLine(xt, hy, xb, bottom, gridPaint)
        }
        gridPaint.color = Palette.withAlpha(tint, 0.9f * gridAlpha)
        gridPaint.strokeWidth = 2f * dp
        c.drawLine(0f, hy, w, hy, gridPaint)
    }

    companion object {
        private const val STARS = 70
        private const val H_LINES = 14
        private const val V_LINES = 9
    }
}
