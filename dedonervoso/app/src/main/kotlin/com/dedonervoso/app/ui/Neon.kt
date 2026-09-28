package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Neon drawing primitives. Glow is approximated with stacked translucent strokes — works with
 * hardware acceleration on every API level (blur mask filters are not HW-accelerated) and costs
 * only a few extra draw calls. All scratch objects are reused: nothing allocates per frame.
 */
class Neon(private val dp: Float) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val rect = RectF()
    private val path = Path()

    /** Unit horizontal gradients (0..1), mapped to any rect via a local matrix. */
    private val gradients = HashMap<Long, LinearGradient>()

    private fun gradient(a: Int, b: Int): LinearGradient {
        val key = (a.toLong() shl 32) xor (b.toLong() and 0xFFFFFFFFL)
        return gradients.getOrPut(key) { LinearGradient(0f, 0f, 1f, 0f, a, b, Shader.TileMode.CLAMP) }
    }

    /** Rounded panel: translucent fill plus a glowing outline in [strokeColor]. */
    fun panel(c: Canvas, r: RectF, radius: Float, fillColor: Int, strokeColor: Int, glow: Float = 1f, strokeW: Float = 1.5f * dp) {
        fill.color = fillColor
        c.drawRoundRect(r, radius, radius, fill)
        glowStroke(c, r, radius, strokeColor, glow, strokeW)
    }

    fun glowStroke(c: Canvas, r: RectF, radius: Float, color: Int, glow: Float = 1f, strokeW: Float = 1.5f * dp) {
        if (glow > 0f) {
            stroke.color = Palette.withAlpha(color, (28 * glow).toInt())
            stroke.strokeWidth = strokeW + 9f * dp * glow
            c.drawRoundRect(r, radius, radius, stroke)
            stroke.color = Palette.withAlpha(color, (60 * glow).toInt())
            stroke.strokeWidth = strokeW + 4f * dp * glow
            c.drawRoundRect(r, radius, radius, stroke)
        }
        stroke.color = color
        stroke.strokeWidth = strokeW
        c.drawRoundRect(r, radius, radius, stroke)
    }

    /** Horizontal gradient fill of a rounded rect. */
    fun gradientRect(c: Canvas, r: RectF, radius: Float, left: Int, right: Int, alpha: Int = 255) {
        val g = gradient(left, right)
        matrix.setScale(r.width(), 1f)
        matrix.postTranslate(r.left, 0f)
        g.setLocalMatrix(matrix)
        gradientPaint.shader = g
        gradientPaint.alpha = alpha
        c.drawRoundRect(r, radius, radius, gradientPaint)
        gradientPaint.shader = null
    }

    /** Soft radial glow blob in [color] (per-colour gradient cached, positioned by matrix). */
    fun glowBlob(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Float) {
        if (alpha <= 0.003f || radius <= 0f) return
        val g = radialFor(color)
        matrix.setScale(radius, radius)
        matrix.postTranslate(cx, cy)
        g.setLocalMatrix(matrix)
        tintPaint.shader = g
        tintPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawCircle(cx, cy, radius, tintPaint)
    }

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radials = HashMap<Int, RadialGradient>()

    private fun radialFor(color: Int): RadialGradient = radials.getOrPut(color) {
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Palette.withAlpha(color, 255), Palette.withAlpha(color, 90), Palette.withAlpha(color, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** Text with a neon halo. The paint's shadow layer is restored afterwards. */
    fun glowText(c: Canvas, text: String, x: Float, y: Float, p: Paint, glowColor: Int, glowRadius: Float) {
        if (glowRadius > 0f) {
            p.setShadowLayer(glowRadius, 0f, 0f, glowColor)
            c.drawText(text, x, y, p)
            p.clearShadowLayer()
        }
        c.drawText(text, x, y, p)
    }

    /** Progress bar with rounded ends and a gradient fill. */
    fun bar(c: Canvas, r: RectF, fraction: Float, from: Int, to: Int, track: Int = 0x33FFFFFF) {
        val radius = r.height() / 2f
        fill.color = track
        c.drawRoundRect(r, radius, radius, fill)
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        rect.set(r.left, r.top, r.left + maxOf(r.height(), r.width() * f), r.bottom)
        gradientRect(c, rect, radius, from, to)
    }

    /** Arc ring (e.g. timers): [fraction] of a full circle starting at 12 o'clock. */
    fun ring(c: Canvas, cx: Float, cy: Float, radius: Float, fraction: Float, color: Int, width: Float, trackColor: Int = 0) {
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        stroke.strokeWidth = width
        if (trackColor != 0) {
            stroke.color = trackColor
            c.drawCircle(cx, cy, radius, stroke)
        }
        if (fraction <= 0f) return
        stroke.color = color
        c.drawArc(rect, -90f, 360f * fraction.coerceIn(0f, 1f), false, stroke)
    }

    fun circle(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawCircle(cx, cy, radius, fill)
    }

    fun circleStroke(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawCircle(cx, cy, radius, stroke)
    }

    /** Five-point star, filled or outlined. */
    fun star(c: Canvas, cx: Float, cy: Float, radius: Float, color: Int, filled: Boolean) {
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) radius else radius * 0.45f
            val a = Math.toRadians((-90 + i * 36).toDouble())
            val x = cx + (r * Math.cos(a)).toFloat()
            val y = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        if (filled) {
            fill.color = color
            c.drawPath(path, fill)
        } else {
            stroke.color = color
            stroke.strokeWidth = radius * 0.12f
            c.drawPath(path, stroke)
        }
    }

    /** Regular octagon (STOP sign). */
    fun octagon(c: Canvas, cx: Float, cy: Float, radius: Float, fillColor: Int, strokeColor: Int, strokeW: Float) {
        path.reset()
        for (i in 0 until 8) {
            val a = Math.toRadians((22.5 + i * 45).toDouble())
            val x = cx + (radius * Math.cos(a)).toFloat()
            val y = cy + (radius * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        fill.color = fillColor
        c.drawPath(path, fill)
        stroke.color = strokeColor
        stroke.strokeWidth = strokeW
        c.drawPath(path, stroke)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(x0, y0, x1, y1, stroke)
    }
}
