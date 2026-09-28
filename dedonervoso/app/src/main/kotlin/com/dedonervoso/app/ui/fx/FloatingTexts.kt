package com.dedonervoso.app.ui.fx

import android.graphics.Canvas
import android.graphics.Paint
import com.dedonervoso.app.ui.Palette

/** Pooled rising text popups ("+12", "PERFECT!", "x2"...). */
class FloatingTexts(private val dp: Float, private val paints: Array<Paint>) {
    private val cap = 56
    private val text = arrayOfNulls<String>(cap)
    private val x = FloatArray(cap)
    private val y = FloatArray(cap)
    private val vy = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val color = IntArray(cap)
    private val size = FloatArray(cap)
    private val font = IntArray(cap)
    private val pop = FloatArray(cap)
    private val bump = FloatArray(cap)
    private val age = FloatArray(cap)
    private val key = IntArray(cap)
    private val total = IntArray(cap)
    private val anchorX = FloatArray(cap)
    private val anchorY = FloatArray(cap)
    private var count = 0

    /** Text of a merged popup for its running total. */
    fun interface Label {
        fun of(total: Int): String
    }

    fun clear() {
        count = 0
    }

    /** Texts currently shown, oldest first (UI tests). */
    internal fun textsForTest(): List<String> = (0 until count).map { text[it]!! }

    /** [fontIndex] selects one of [paints]; [sizeDp] overrides its size. */
    fun add(s: String, px: Float, py: Float, col: Int, sizeDp: Float, lifeS: Float = 0.7f, riseDp: Float = 70f, fontIndex: Int = 0): Int {
        val k = if (count < cap) count++ else oldest()
        text[k] = s
        x[k] = px
        y[k] = py
        vy[k] = -riseDp * dp
        life[k] = lifeS
        maxLife[k] = lifeS
        color[k] = col
        size[k] = sizeDp * dp
        font[k] = fontIndex
        pop[k] = 0f
        bump[k] = 0f
        age[k] = 0f
        key[k] = 0
        total[k] = 0
        anchorX[k] = px
        anchorY[k] = py
        return k
    }

    /**
     * Popup for rapid-fire events: while a popup with the same non-zero [mergeKey] was refreshed
     * less than [MERGE_WINDOW_S] ago close to this spot, it absorbs [amount] into its running total
     * and bumps instead of stacking another copy on top — a burst of taps on one zone would
     * otherwise pile up into unreadable text.
     */
    fun addMerged(
        mergeKey: Int, amount: Int, label: Label, px: Float, py: Float, col: Int, sizeDp: Float,
        lifeS: Float = 0.7f, riseDp: Float = 70f, fontIndex: Int = 0,
    ) {
        val r = MERGE_RADIUS_DP * dp
        for (i in 0 until count) {
            if (key[i] != mergeKey || maxLife[i] - life[i] > MERGE_WINDOW_S || age[i] > MERGE_MAX_AGE_S) continue
            val dx = anchorX[i] - px
            val dy = anchorY[i] - py
            if (dx * dx + dy * dy > r * r) continue
            total[i] += amount
            text[i] = label.of(total[i])
            life[i] = maxLife[i]
            color[i] = col
            size[i] = maxOf(size[i], sizeDp * dp)
            bump[i] = 1f
            return
        }
        val k = add(label.of(amount), px, py, col, sizeDp, lifeS, riseDp, fontIndex)
        key[k] = mergeKey
        total[k] = amount
    }

    private fun oldest(): Int {
        var best = 0
        for (i in 1 until count) if (life[i] < life[best]) best = i
        return best
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = --count
                if (i != last) {
                    text[i] = text[last]; x[i] = x[last]; y[i] = y[last]; vy[i] = vy[last]; life[i] = life[last]
                    maxLife[i] = maxLife[last]; color[i] = color[last]; size[i] = size[last]; font[i] = font[last]; pop[i] = pop[last]
                    bump[i] = bump[last]; age[i] = age[last]; key[i] = key[last]; total[i] = total[last]
                    anchorX[i] = anchorX[last]; anchorY[i] = anchorY[last]
                }
                text[last] = null
                continue
            }
            y[i] += vy[i] * dt
            vy[i] *= 1f / (1f + 3f * dt)
            pop[i] = minOf(1f, pop[i] + dt * 8f)
            bump[i] = maxOf(0f, bump[i] - dt * 7f)
            age[i] += dt
            i++
        }
    }

    fun draw(c: Canvas) {
        for (i in 0 until count) {
            val p = paints[font[i]]
            val t = life[i] / maxLife[i]
            val alpha = if (t < 0.35f) t / 0.35f else 1f
            val scale = (0.6f + 0.4f * pop[i] + (if (pop[i] < 1f) 0.25f * (1f - pop[i]) else 0f)) * (1f + 0.2f * bump[i])
            p.textSize = size[i] * scale
            p.color = Palette.withAlpha(color[i], alpha)
            p.setShadowLayer(6f * dp, 0f, 0f, Palette.withAlpha(color[i], alpha * 0.8f))
            c.drawText(text[i]!!, x[i], y[i], p)
            p.clearShadowLayer()
        }
    }

    companion object {
        private const val MERGE_WINDOW_S = 0.3f
        private const val MERGE_MAX_AGE_S = 1.1f
        private const val MERGE_RADIUS_DP = 48f
    }
}
