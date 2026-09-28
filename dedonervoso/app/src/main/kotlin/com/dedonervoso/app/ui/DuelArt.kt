package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import com.dedonervoso.core.engine.DuelItem

/** Look of the live duel items (lobby, match HUD, results). */
object DuelArt {
    fun color(item: DuelItem): Int = when (item) {
        DuelItem.SLOW -> Palette.BLUE
        DuelItem.CLOCK -> Palette.GOLD
        DuelItem.STOP -> Palette.RED
    }

    /** The item's emblem in a glowing circle of radius [r]. */
    fun badge(c: Canvas, ui: UiKit, item: DuelItem, cx: Float, cy: Float, r: Float, alpha: Float = 1f) {
        val color = color(item)
        ui.neon.glowBlob(c, cx, cy, r * 1.7f, color, 0.3f * alpha)
        ui.neon.circle(c, cx, cy, r, Palette.withAlpha(Palette.mix(Palette.BG_MID, color, 0.25f), 0.92f * alpha))
        ui.neon.circleStroke(c, cx, cy, r, Palette.withAlpha(color, alpha), 2f * ui.u)
        when (item) {
            DuelItem.SLOW -> {
                val p = ui.style(ui.displayPaint, 1f, Palette.withAlpha(Palette.WHITE, alpha), Paint.Align.CENTER)
                p.textSize = r * 0.8f
                c.drawText("÷2", cx, cy + p.textSize * 0.36f, p)
            }
            DuelItem.CLOCK -> ui.icons.draw(c, Icon.CLOCK, cx, cy, r * 1.1f, Palette.withAlpha(color, alpha))
            DuelItem.STOP -> ui.icons.draw(c, Icon.HAND, cx, cy, r * 1.05f, Palette.withAlpha(Palette.WHITE, alpha))
        }
    }
}
