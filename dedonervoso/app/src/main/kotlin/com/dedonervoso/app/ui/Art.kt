package com.dedonervoso.app.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Illustrations shipped in assets/art: the Dedo Nervoso mascot scene ([hero], opening and Home)
 * and a small blurred, darkened copy of it behind the menus ([backdrop]). Loaded once, on first use;
 * a missing file just means the screen falls back to the neon backdrop.
 */
class Art(private val assets: AssetManager) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var heroLoaded = false
    private var backdropLoaded = false

    var hero: Bitmap? = null
        get() {
            if (!heroLoaded) {
                heroLoaded = true
                field = load("art/hero.webp", Bitmap.Config.RGB_565)
            }
            return field
        }
        private set

    var backdrop: Bitmap? = null
        get() {
            if (!backdropLoaded) {
                backdropLoaded = true
                field = load("art/backdrop.webp", Bitmap.Config.RGB_565)
            }
            return field
        }
        private set

    /** Where the fingertip touches the phone in [hero], as fractions of its size. */
    val heroTapX = 0.53f
    val heroTapY = 0.72f

    private fun load(path: String, config: Bitmap.Config): Bitmap? = try {
        com.dedonervoso.app.platform.Endpoints.openArt(assets, path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = config }) }
    } catch (e: java.io.IOException) {
        null
    }

    /**
     * Draws [bmp] covering a [w]×[h] screen, scaled by [zoom] around the image point
     * ([focusX], [focusY]) (fractions), which lands at the same place it would unzoomed.
     * [alignY] 0 = top-aligned, 0.5 = centred. Returns the scale used (screen px per image px).
     */
    fun drawCover(
        c: Canvas, bmp: Bitmap, w: Float, h: Float, alignY: Float = 0.5f, zoom: Float = 1f,
        focusX: Float = 0.5f, focusY: Float = 0.5f, dx: Float = 0f, dy: Float = 0f, alpha: Float = 1f,
    ): Float {
        val base = maxOf(w / bmp.width, h / bmp.height)
        val bw = bmp.width * base
        val bh = bmp.height * base
        val left = (w - bw) / 2f
        val top = (h - bh) * alignY
        // The focus point on screen without zoom stays put while zooming.
        val fx = left + bw * focusX
        val fy = top + bh * focusY
        dst.set(fx - bw * focusX * zoom + dx, fy - bh * focusY * zoom + dy, fx + bw * (1f - focusX) * zoom + dx, fy + bh * (1f - focusY) * zoom + dy)
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawBitmap(bmp, null, dst, paint)
        return base * zoom
    }

    /** Screen position of the image point ([fx], [fy]) after the last [drawCover]. */
    fun lastX(fx: Float): Float = dst.left + dst.width() * fx
    fun lastY(fy: Float): Float = dst.top + dst.height() * fy
}
