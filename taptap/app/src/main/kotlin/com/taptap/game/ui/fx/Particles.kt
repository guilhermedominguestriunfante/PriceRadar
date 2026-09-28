package com.taptap.game.ui.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.taptap.game.ui.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pooled particle system (spec §48): structure-of-arrays storage, swap-remove compaction and
 * sprite drawing with additive blending — hundreds of particles at no allocation cost.
 */
class Particles(private val sprites: Sprites, private val dp: Float) {
    private val cap = 720
    private val x = FloatArray(cap)
    private val y = FloatArray(cap)
    private val vx = FloatArray(cap)
    private val vy = FloatArray(cap)
    private val life = FloatArray(cap)
    private val maxLife = FloatArray(cap)
    private val size = FloatArray(cap)
    private val gravity = FloatArray(cap)
    private val drag = FloatArray(cap)
    private val spin = FloatArray(cap)
    private val color = IntArray(cap)
    private val kind = ByteArray(cap)
    var count = 0
        private set

    /** 1 = full effects; lowered by "reduce effects". */
    var density = 1f

    private val additive = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private var seed = 0x13579BDF

    private fun rnd(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed and 0xFFFFFF) / 16777216f
    }

    fun clear() {
        count = 0
    }

    /**
     * Emits [n] particles at ([px], [py]). Speeds/sizes in dp units; [angle]/[spread] in radians
     * (spread = 2π for a full burst).
     */
    fun burst(
        px: Float, py: Float, n: Int, sprite: Int, speedMin: Float, speedMax: Float, sizeMin: Float, sizeMax: Float,
        lifeS: Float, type: Int = GLOW, gravityDp: Float = 0f, angle: Float = 0f, spread: Float = 6.2832f, dragK: Float = 2.2f,
    ) {
        val total = (n * density + 0.5f).toInt().coerceAtLeast(if (n > 0) 1 else 0)
        for (i in 0 until total) {
            if (count >= cap) return
            val k = count++
            val a = angle + (rnd() - 0.5f) * spread
            val sp = (speedMin + (speedMax - speedMin) * rnd()) * dp
            x[k] = px
            y[k] = py
            vx[k] = cos(a) * sp
            vy[k] = sin(a) * sp
            maxLife[k] = lifeS * (0.7f + 0.6f * rnd())
            life[k] = maxLife[k]
            size[k] = (sizeMin + (sizeMax - sizeMin) * rnd()) * dp
            gravity[k] = gravityDp * dp
            drag[k] = dragK
            spin[k] = rnd() * 6.2832f
            color[k] = sprite
            kind[k] = type.toByte()
        }
    }

    /** Multi-coloured confetti rain for records/celebrations. */
    fun confetti(width: Float, top: Float, n: Int) {
        val colors = intArrayOf(Palette.S_CYAN, Palette.S_MAGENTA, Palette.S_GOLD, Palette.S_GREEN, Palette.S_PURPLE)
        for (i in 0 until (n * density).toInt()) {
            if (count >= cap) return
            val k = count++
            x[k] = rnd() * width
            y[k] = top - rnd() * 80f * dp
            vx[k] = (rnd() - 0.5f) * 60f * dp
            vy[k] = (60f + rnd() * 120f) * dp
            maxLife[k] = 2.2f + rnd() * 1.2f
            life[k] = maxLife[k]
            size[k] = (4f + rnd() * 4f) * dp
            gravity[k] = 90f * dp
            drag[k] = 0.8f
            spin[k] = rnd() * 6.2832f
            color[k] = colors[i % colors.size]
            kind[k] = CONFETTI.toByte()
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            life[i] -= dt
            if (life[i] <= 0f) {
                val last = --count
                if (i != last) copy(last, i)
                continue
            }
            val d = 1f / (1f + drag[i] * dt)
            vx[i] *= d
            vy[i] = vy[i] * d + gravity[i] * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            spin[i] += dt * 9f
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; vx[to] = vx[from]; vy[to] = vy[from]
        life[to] = life[from]; maxLife[to] = maxLife[from]; size[to] = size[from]
        gravity[to] = gravity[from]; drag[to] = drag[from]; spin[to] = spin[from]
        color[to] = color[from]; kind[to] = kind[from]
    }

    fun draw(c: Canvas) {
        for (i in 0 until count) {
            val t = life[i] / maxLife[i]
            val alpha = (if (t < 0.3f) t / 0.3f else 1f)
            when (kind[i].toInt()) {
                GLOW -> {
                    val s = size[i] * (0.5f + 0.5f * t)
                    dst.set(x[i] - s, y[i] - s, x[i] + s, y[i] + s)
                    additive.alpha = (alpha * 255).toInt()
                    c.drawBitmap(sprites.glow[color[i]], null, dst, additive)
                }
                SPARK -> {
                    line.color = Palette.SPRITES[color[i]]
                    line.alpha = (alpha * 255).toInt()
                    line.strokeWidth = size[i] * 0.35f
                    c.drawLine(x[i], y[i], x[i] - vx[i] * 0.045f, y[i] - vy[i] * 0.045f, line)
                }
                CONFETTI -> {
                    solid.color = Palette.SPRITES[color[i]]
                    solid.alpha = (alpha * 255).toInt()
                    val w = size[i] * (0.25f + 0.75f * abs(cos(spin[i])))
                    val h = size[i] * 0.6f
                    c.drawRect(x[i] - w / 2f, y[i] - h / 2f, x[i] + w / 2f, y[i] + h / 2f, solid)
                }
            }
        }
    }

    companion object {
        const val GLOW = 0
        const val SPARK = 1
        const val CONFETTI = 2
    }
}
