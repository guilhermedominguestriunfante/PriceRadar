package com.dedonervoso.app.ui.fx

import android.graphics.Canvas
import android.graphics.Paint
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.core.util.Ease

/** Pooled expanding rings: tap ripples and shockwaves. */
class Rings(private val dp: Float) {
    private val cap = 64
    private val x = FloatArray(cap)
    private val y = FloatArray(cap)
    private val r0 = FloatArray(cap)
    private val r1 = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val color = IntArray(cap)
    private val width = FloatArray(cap)
    private var count = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun clear() {
        count = 0
    }

    fun add(px: Float, py: Float, fromDp: Float, toDp: Float, col: Int, lifeS: Float, widthDp: Float) {
        val k = if (count < cap) count++ else 0
        x[k] = px
        y[k] = py
        r0[k] = fromDp * dp
        r1[k] = toDp * dp
        life[k] = lifeS
        maxLife[k] = lifeS
        color[k] = col
        width[k] = widthDp * dp
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = --count
                if (i != last) {
                    x[i] = x[last]; y[i] = y[last]; r0[i] = r0[last]; r1[i] = r1[last]; life[i] = life[last]
                    maxLife[i] = maxLife[last]; color[i] = color[last]; width[i] = width[last]
                }
                continue
            }
            i++
        }
    }

    fun draw(c: Canvas) {
        for (i in 0 until count) {
            val t = 1f - life[i] / maxLife[i]
            val e = Ease.outCubic(t)
            val r = r0[i] + (r1[i] - r0[i]) * e
            paint.strokeWidth = width[i] * (1f - t * 0.7f)
            paint.color = Palette.withAlpha(color[i], (1f - t) * 0.9f)
            c.drawCircle(x[i], y[i], r, paint)
        }
    }
}
