package com.taptap.game.ui.fx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.taptap.game.ui.Palette

/** Pre-rendered soft glow dots, one per palette colour (drawn scaled, additively). */
class Sprites {
    val glow: Array<Bitmap> = Array(Palette.SPRITES.size) { i -> render(Palette.SPRITES[i]) }

    private fun render(color: Int): Bitmap {
        val size = SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.shader = RadialGradient(
            r, r, r,
            intArrayOf(0xFFFFFFFF.toInt(), Palette.withAlpha(color, 230), Palette.withAlpha(color, 70), Palette.withAlpha(color, 0)),
            floatArrayOf(0f, 0.18f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawCircle(r, r, r, p)
        return bmp
    }

    fun recycle() = glow.forEach { it.recycle() }

    companion object {
        const val SIZE = 64
    }
}
