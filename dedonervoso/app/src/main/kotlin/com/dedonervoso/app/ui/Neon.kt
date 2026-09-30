package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.Color
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

    /**
     * Panel: a chamfered glass panel (see [glassPanel]) in [strokeColor]. Pill shapes (radius of
     * half the height or more: toggles, chips) stay rounded, with a softer glow.
     */
    fun panel(c: Canvas, r: RectF, radius: Float, fillColor: Int, strokeColor: Int, glow: Float = 1f, strokeW: Float = 1.5f * dp) {
        if (radius * 2f >= minOf(r.width(), r.height()) * 0.95f) {
            fill.color = fillColor
            c.drawRoundRect(r, radius, radius, fill)
            glowStroke(c, r, radius, strokeColor, glow * 0.6f, strokeW)
            return
        }
        glassPanel(c, r, minOf(radius * 0.9f, 14f * dp), fillColor, strokeColor, glow * 0.6f, strokeW)
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

    // ---- street-lobby pieces (1.3.1): chamfered glass, gold plates, hexagons -------------------

    private val vGradients = HashMap<Long, LinearGradient>()

    /** Vertical gradient fill for the next shape drawn with the returned paint, mapped onto [r]. */
    fun vertical(r: RectF, top: Int, bottom: Int, alpha: Int = 255): Paint {
        val key = (top.toLong() shl 32) xor (bottom.toLong() and 0xFFFFFFFFL)
        val g = vGradients.getOrPut(key) { LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP) }
        matrix.setScale(1f, r.height())
        matrix.postTranslate(0f, r.top)
        g.setLocalMatrix(matrix)
        gradientPaint.shader = g
        gradientPaint.alpha = alpha
        return gradientPaint
    }

    /** Horizontal gradient fill for the next shape drawn with the returned paint, mapped onto [r]. */
    fun horizontal(r: RectF, left: Int, right: Int, alpha: Int = 255): Paint {
        val g = gradient(left, right)
        matrix.setScale(r.width(), 1f)
        matrix.postTranslate(r.left, 0f)
        g.setLocalMatrix(matrix)
        gradientPaint.shader = g
        gradientPaint.alpha = alpha
        return gradientPaint
    }

    /** Drops the shader left on the shared gradient paint by [vertical]/[horizontal]. */
    fun clearGradient() {
        gradientPaint.shader = null
    }

    private val gold3 = intArrayOf(Palette.GOLD_HI, Palette.GOLD, 0xFFF2A51E.toInt(), Palette.GOLD_DEEP)
    private val gold3Stops = floatArrayOf(0f, 0.32f, 0.62f, 1f)
    private val goldShader = LinearGradient(0f, 0f, 0f, 1f, gold3, gold3Stops, Shader.TileMode.CLAMP)

    /** Rect with its top-left and bottom-right corners cut by [cut] (the lobby's panel shape). */
    fun chamfer(r: RectF, cut: Float): Path {
        val k = minOf(cut, r.width() / 2f, r.height() / 2f)
        path.reset()
        path.moveTo(r.left + k, r.top)
        path.lineTo(r.right, r.top)
        path.lineTo(r.right, r.bottom - k)
        path.lineTo(r.right - k, r.bottom)
        path.lineTo(r.left, r.bottom)
        path.lineTo(r.left, r.top + k)
        path.close()
        return path
    }

    /**
     * Chamfered glass panel: [fillColor] inside, a faint [edgeColor] outline and bright corner
     * accents on the two cut corners. The shared panel look of every menu.
     */
    fun glassPanel(c: Canvas, r: RectF, cut: Float, fillColor: Int, edgeColor: Int, glow: Float = 0.6f, strokeW: Float = 1.5f * dp) {
        val p = chamfer(r, cut)
        fill.color = fillColor
        c.drawPath(p, fill)
        // A soft sheen on the upper half.
        rect.set(r.left, r.top, r.right, r.top + r.height() * 0.5f)
        c.save()
        c.clipPath(p)
        c.drawRect(rect, vertical(rect, 0x14FFFFFF, 0x00FFFFFF))
        gradientPaint.shader = null
        c.restore()
        if (glow > 0f) {
            stroke.color = Palette.withAlpha(edgeColor, (40 * glow).toInt())
            stroke.strokeWidth = strokeW + 5f * dp * glow
            c.drawPath(p, stroke)
        }
        stroke.color = Palette.withAlpha(edgeColor, (Color.alpha(edgeColor) * 0.5f).toInt())
        stroke.strokeWidth = strokeW
        c.drawPath(p, stroke)
        // Corner accents: the cut corners and a stretch of the edges next to them, at full colour.
        val k = minOf(cut, r.width() / 2f, r.height() / 2f)
        val arm = minOf(r.width(), r.height()) * 0.35f
        stroke.color = edgeColor
        stroke.strokeWidth = strokeW * 1.6f
        c.drawLine(r.left, r.top + k + arm, r.left, r.top + k, stroke)
        c.drawLine(r.left, r.top + k, r.left + k, r.top, stroke)
        c.drawLine(r.left + k, r.top, r.left + k + arm, r.top, stroke)
        c.drawLine(r.right, r.bottom - k - arm, r.right, r.bottom - k, stroke)
        c.drawLine(r.right, r.bottom - k, r.right - k, r.bottom, stroke)
        c.drawLine(r.right - k, r.bottom, r.right - k - arm, r.bottom, stroke)
    }

    /**
     * The lobby's gold call-to-action plate: a slanted plate (left edge leaning by [slant] of
     * its width) with a molten gradient, a top highlight and a light sweep at [shine] (0..1,
     * negative = none).
     */
    fun goldPlate(c: Canvas, r: RectF, slant: Float, shine: Float, alpha: Float = 1f) {
        val lean = r.width() * slant
        path.reset()
        path.moveTo(r.left + lean, r.top)
        path.lineTo(r.right, r.top)
        path.lineTo(r.right, r.bottom)
        path.lineTo(r.left, r.bottom)
        path.close()
        glowBlob(c, r.centerX(), r.centerY() + r.height() * 0.2f, r.width() * 0.6f, Palette.ORANGE, 0.35f * alpha)
        matrix.setScale(1f, r.height())
        matrix.postTranslate(0f, r.top)
        goldShader.setLocalMatrix(matrix)
        gradientPaint.shader = goldShader
        gradientPaint.alpha = (255 * alpha).toInt()
        c.drawPath(path, gradientPaint)
        gradientPaint.shader = null
        c.save()
        c.clipPath(path)
        // Glassy top highlight.
        rect.set(r.left, r.top, r.right, r.top + r.height() * 0.45f)
        c.drawRect(rect, vertical(rect, Palette.withAlpha(Palette.WHITE, 0.45f * alpha), 0x00FFFFFF))
        gradientPaint.shader = null
        // The light sweep.
        if (shine in 0f..1f) {
            val x = r.left - r.width() * 0.3f + r.width() * 1.6f * shine
            stroke.color = Palette.withAlpha(Palette.WHITE, 0.5f * alpha)
            stroke.strokeWidth = r.width() * 0.12f
            c.drawLine(x, r.bottom + r.height() * 0.2f, x + r.height() * 0.5f, r.top - r.height() * 0.2f, stroke)
        }
        c.restore()
        stroke.color = Palette.withAlpha(Palette.GOLD_DEEP, alpha)
        stroke.strokeWidth = 1.5f * dp
        c.drawPath(path, stroke)
    }

    /** Pointy-top hexagon with a metal rim ([rim] gradient) around a dark core (the ARENA emblem). */
    fun hexEmblem(c: Canvas, cx: Float, cy: Float, radius: Float, rimTop: Int, rimBottom: Int, core: Int) {
        hexPath(cx, cy, radius)
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        c.drawPath(path, vertical(rect, rimTop, rimBottom))
        gradientPaint.shader = null
        hexPath(cx, cy, radius * 0.88f)
        fill.color = core
        c.drawPath(path, fill)
    }

    private fun hexPath(cx: Float, cy: Float, radius: Float) {
        path.reset()
        for (i in 0 until 6) {
            val a = Math.toRadians((-90 + i * 60).toDouble())
            val x = cx + (radius * Math.cos(a)).toFloat()
            val y = cy + (radius * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** Octagon outline path (avatar frames), left in the shared scratch path for clipping. */
    fun octagonPath(cx: Float, cy: Float, half: Float): Path {
        val k = half * 0.44f
        path.reset()
        path.moveTo(cx - half + k, cy - half)
        path.lineTo(cx + half - k, cy - half)
        path.lineTo(cx + half, cy - half + k)
        path.lineTo(cx + half, cy + half - k)
        path.lineTo(cx + half - k, cy + half)
        path.lineTo(cx - half + k, cy + half)
        path.lineTo(cx - half, cy + half - k)
        path.lineTo(cx - half, cy - half + k)
        path.close()
        return path
    }

    /** Gold octagon frame around whatever [content] draws (clipped to the inner octagon). */
    inline fun goldFrame(c: Canvas, cx: Float, cy: Float, half: Float, content: () -> Unit) {
        val border = maxOf(2f * dpValue, half * 0.1f)
        framePaintFor(cy, half)
        c.drawPath(octagonPath(cx, cy, half), framePaint)
        framePaint.shader = null
        c.save()
        c.clipPath(octagonPath(cx, cy, half - border))
        content()
        c.restore()
    }

    @PublishedApi internal val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    @PublishedApi internal val dpValue: Float get() = dp

    @PublishedApi internal fun framePaintFor(cy: Float, half: Float) {
        matrix.setScale(1f, half * 2f)
        matrix.postTranslate(0f, cy - half)
        goldShader.setLocalMatrix(matrix)
        framePaint.shader = goldShader
    }
}
