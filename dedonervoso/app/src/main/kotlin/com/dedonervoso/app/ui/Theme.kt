package com.dedonervoso.app.ui

import android.content.res.AssetManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Colour hierarchy (spec §29): deep navy base, cyan primary, magenta secondary, red STOP, gold PERFECT. */
object Palette {
    const val BG_TOP = 0xFF02030A.toInt()
    const val BG_MID = 0xFF060A22.toInt()
    const val BG_BOTTOM = 0xFF0C1240.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val BLUE = 0xFF3D7BFF.toInt()
    const val MAGENTA = 0xFFFF2BD6.toInt()
    const val PURPLE = 0xFF8B5CFF.toInt()
    const val RED = 0xFFFF2D55.toInt()
    const val GOLD = 0xFFFFD54A.toInt()
    const val ORANGE = 0xFFFF9F1C.toInt()
    const val GREEN = 0xFF3DFF9A.toInt()
    const val WHITE = 0xFFF4F7FF.toInt()
    const val TEXT = 0xFFDDE4FF.toInt()
    const val DIM = 0xFF8A94B8.toInt()
    const val MUTED = 0xFF4A5378.toInt()
    const val PANEL = 0xD90B1030.toInt()
    const val PANEL_LIGHT = 0xE6141B45.toInt()
    const val SHADOW = 0xAA000000.toInt()

    /** Sprite palette for particles/glows (indices used by the FX systems). */
    val SPRITES = intArrayOf(WHITE, CYAN, MAGENTA, GOLD, RED, GREEN, PURPLE, ORANGE, BLUE)
    const val S_WHITE = 0
    const val S_CYAN = 1
    const val S_MAGENTA = 2
    const val S_GOLD = 3
    const val S_RED = 4
    const val S_GREEN = 5
    const val S_PURPLE = 6
    const val S_ORANGE = 7
    const val S_BLUE = 8

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun withAlpha(color: Int, alpha: Float): Int = withAlpha(color, (alpha * 255).toInt())

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
        )
    }

    private val hsv = FloatArray(3)

    /** Neon rainbow for FRENZY (UI thread only). */
    fun rainbow(phase: Float, saturation: Float = 0.85f): Int {
        hsv[0] = ((phase % 1f + 1f) % 1f) * 360f
        hsv[1] = saturation
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }
}

/** Typefaces bundled in assets/fonts (see assets/licenses). */
class Fonts(assets: AssetManager) {
    /** Futuristic display face for logos, numbers and big words. */
    val display: Typeface = load(assets, "fonts/display_black.ttf", Typeface.DEFAULT_BOLD)
    val displayBold: Typeface = load(assets, "fonts/display_bold.ttf", Typeface.DEFAULT_BOLD)
    /** Condensed techy face for labels and body text. */
    val text: Typeface = load(assets, "fonts/rajdhani_bold.ttf", Typeface.DEFAULT_BOLD)
    val textSemi: Typeface = load(assets, "fonts/rajdhani_semibold.ttf", Typeface.DEFAULT)
    val textMedium: Typeface = load(assets, "fonts/rajdhani_medium.ttf", Typeface.DEFAULT)

    private fun load(assets: AssetManager, path: String, fallback: Typeface): Typeface = try {
        Typeface.createFromAsset(assets, path)
    } catch (t: Throwable) {
        fallback
    }
}

/** Creates configured text paints (allocate once, reuse every frame). */
fun textPaint(face: Typeface, sizePx: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = face
        textSize = sizePx
        this.color = color
        textAlign = align
        letterSpacing = spacing
    }

/**
 * Caches the string form of a changing number so drawing it every frame doesn't allocate:
 * the string is rebuilt only when the value changes.
 */
class NumText(private val format: (Long) -> String) {
    private var last = Long.MIN_VALUE
    private var cached = ""

    fun of(value: Long): String {
        if (value != last) {
            last = value
            cached = format(value)
        }
        return cached
    }

    fun of(value: Int): String = of(value.toLong())
}

/** Pre-built "+N" labels for floating score popups (no allocation after warm-up). */
object PlusCache {
    private const val SIZE = 4096
    private val cache = arrayOfNulls<String>(SIZE)

    fun get(n: Int): String {
        if (n in 0 until SIZE) {
            return cache[n] ?: ("+$n").also { cache[n] = it }
        }
        return "+$n"
    }
}
