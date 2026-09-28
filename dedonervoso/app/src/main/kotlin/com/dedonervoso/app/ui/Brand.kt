package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.sin

/**
 * The game's identity drawn in code, shared by the Home logo and the opening: the two-line
 * DEDO / NERVOSO wordmark with its nervous tremor, and the glove hand of the launcher icon.
 */
class Brand(private val ui: UiKit) {
    private val line1 = arrayOf("D", "E", "D", "O")
    private val line2 = arrayOf("N", "E", "R", "V", "O", "S", "O")
    private val widths1 = FloatArray(line1.size)
    private val widths2 = FloatArray(line2.size)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Glove hand in launcher-icon units (108 × 108, fingertip at 51.5, 77): see ic_launcher_foreground.xml.
    private val hand = Path()
    private val cuff = Path()
    private val nail = Path()

    init {
        hand.addRoundRect(41f, 8f, 68f, 45f, 11f, 11f, Path.Direction.CW)
        hand.addRoundRect(45.5f, 28f, 57.5f, 77f, 6f, 6f, Path.Direction.CW)
        hand.addCircle(61.2f, 47.2f, 4.9f, Path.Direction.CW)
        hand.addCircle(66f, 44.6f, 4.5f, Path.Direction.CW)
        hand.addCircle(69.2f, 40.2f, 3.9f, Path.Direction.CW)
        val thumb = Path().apply { addRoundRect(34.5f, 20f, 45.5f, 43f, 5.5f, 5.5f, Path.Direction.CW) }
        thumb.transform(Matrix().apply { setRotate(24f, 44f, 24f) })
        hand.addPath(thumb)
        cuff.addRoundRect(38.5f, -4f, 70.5f, 13f, 5f, 5f, Path.Direction.CW)
        nail.addRoundRect(48f, 65.5f, 55f, 75f, 3.5f, 3.5f, Path.Direction.CW)
    }

    /** Tremor strength 0..1 at [time]: a faint constant shiver plus a sharp twitch every ~1.9 s. */
    fun tremor(time: Float): Float {
        val phase = time % TWITCH_PERIOD_S
        val twitch = if (phase < TWITCH_S) sin(PI.toFloat() * phase / TWITCH_S) else 0f
        return 0.16f + 0.84f * twitch
    }

    /**
     * Draws the wordmark centred on [cx] with line 1's baseline at [y1]. [size] is line 1's text
     * size in px; line 2 shrinks to [maxWidth] if needed. [shake] scales the tremor (0 = still,
     * for reduced effects) and [alpha] fades it. Returns line 2's baseline.
     */
    fun wordmark(c: Canvas, cx: Float, y1: Float, size: Float, maxWidth: Float, alpha: Float, time: Float, shake: Float): Float {
        val p = ui.displayPaint
        p.textAlign = Paint.Align.LEFT
        p.textSize = size
        val width1 = measure(p, line1, widths1)
        var size2 = size * 0.74f
        p.textSize = size2
        var width2 = measure(p, line2, widths2)
        if (width2 > maxWidth) {
            size2 *= maxWidth / width2
            p.textSize = size2
            width2 = measure(p, line2, widths2)
        }
        val amp = size * 0.045f * shake * tremor(time)
        val frame = (time * 24f).toInt()
        val y2 = y1 + size2 * 1.14f
        p.textSize = size
        drawLine(c, p, line1, widths1, cx - width1 / 2f, y1, amp, frame, 0, Palette.CYAN, Palette.MAGENTA, alpha, size)
        p.textSize = size2
        drawLine(c, p, line2, widths2, cx - width2 / 2f, y2, amp, frame, 17, Palette.MAGENTA, Palette.CYAN, alpha, size2)
        p.textAlign = Paint.Align.CENTER
        return y2
    }

    private fun measure(p: Paint, letters: Array<String>, out: FloatArray): Float {
        var total = 0f
        for (i in letters.indices) {
            out[i] = p.measureText(letters[i])
            total += out[i]
        }
        return total
    }

    private fun drawLine(
        c: Canvas, p: Paint, letters: Array<String>, widths: FloatArray, left: Float, y: Float, amp: Float,
        frame: Int, seed: Int, main: Int, echo: Int, alpha: Float, size: Float,
    ) {
        val split = size * 0.035f + amp * 0.7f
        var x = left
        for (i in letters.indices) {
            val dx = amp * noise(seed + i, frame)
            val dy = amp * noise(seed + i + 101, frame)
            p.color = Palette.withAlpha(echo, 0.55f * alpha)
            c.drawText(letters[i], x + dx + split, y + dy + split * 0.6f, p)
            p.color = Palette.withAlpha(main, alpha)
            ui.neon.glowText(c, letters[i], x + dx, y + dy, p, Palette.withAlpha(main, 0.85f * alpha), size * 0.22f)
            x += widths[i]
        }
    }

    /**
     * Draws the glove hand with its fingertip at ([tipX], [tipY]). [scale] maps icon units to px
     * (the icon is 108 units wide); [split] sets the colour-echo offset (0 = none).
     */
    fun hand(c: Canvas, tipX: Float, tipY: Float, scale: Float, alpha: Float, split: Float) {
        c.save()
        c.translate(tipX - 51.5f * scale, tipY - 77f * scale)
        c.scale(scale, scale)
        if (split > 0f) {
            fill.color = Palette.withAlpha(Palette.MAGENTA, 0.9f * alpha)
            c.save()
            c.translate(2.6f * split, 1.4f * split)
            c.drawPath(hand, fill)
            c.restore()
            fill.color = Palette.withAlpha(Palette.CYAN, 0.8f * alpha)
            c.save()
            c.translate(-2.2f * split, -1f * split)
            c.drawPath(hand, fill)
            c.restore()
        }
        fill.color = Palette.withAlpha(Palette.MAGENTA, alpha)
        c.drawPath(cuff, fill)
        fill.color = Palette.withAlpha(Palette.WHITE, alpha)
        c.drawPath(hand, fill)
        fill.color = Palette.withAlpha(NAIL, alpha)
        c.drawPath(nail, fill)
        c.restore()
    }

    /** Deterministic per-letter jitter in [-1, 1], changing every animation frame. */
    private fun noise(i: Int, frame: Int): Float {
        var h = i * 374_761_393 + frame * 668_265_263
        h = (h xor (h ushr 13)) * 1_274_126_177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32_767.5f - 1f
    }

    companion object {
        private const val TWITCH_PERIOD_S = 1.9f
        private const val TWITCH_S = 0.28f
        private const val NAIL = 0xFFB8F6FF.toInt()
    }
}
