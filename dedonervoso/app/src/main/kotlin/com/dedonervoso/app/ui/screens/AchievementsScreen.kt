package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.progression.Achievements
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScrollArea
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals

/** Achievement list (spec §23) with progress bars for the locked ones. */
class AchievementsScreen(app: GameApp) : Screen(app) {
    private lateinit var scroll: ScrollArea
    private var savedOffset = 0f
    private val bar = RectF()
    private val card = RectF()

    /** Snapshot of every achievement's texts and progress (built on enter, not per frame). */
    private class Item(
        val icon: String, val title: String, val text: String, val coins: String, val unlocked: Boolean,
        val fraction: Float, val progress: String,
    )

    private var items: List<Item> = emptyList()
    private var countText = ""

    override fun onEnter() {
        val save = app.progression.save
        val ctx = app.progression.achievementContext()
        val s = app.strings
        items = Achievements.ALL.map { a ->
            val pr = a.progress(ctx)
            Item(
                a.icon, s.achievementTitle(a.id), s.achievementText(a.id), s.num(a.coins), a.id in save.achievements,
                pr.current.toFloat() / pr.target, "${s.num(pr.current)}/${s.num(pr.target)}",
            )
        }
        countText = "${save.achievements.size}/${Achievements.ALL.size}"
    }

    override fun layout() {
        val top = header(s.achievements, rightReserve = 56f * u)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        scroll.contentHeight = Achievements.ALL.size * ROW * u + 16f * u
        scroll.scrollTo(savedOffset)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        savedOffset = scroll.offset
        ui.background.update(dt * 0.5f)
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        val cp = ui.style(ui.displayBoldPaint, 14f, Palette.GOLD, Paint.Align.RIGHT)
        c.drawText(countText, safe.right - 8f * u, safe.top + 28f * u, cp)
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val rowH = ROW * u
        for ((i, a) in items.withIndex()) {
            val top = scroll.rect.top + 4f * u + i * rowH
            if (top + rowH < scroll.offset + scroll.rect.top || top > scroll.offset + scroll.rect.bottom) continue
            val unlocked = a.unlocked
            val color = if (unlocked) Palette.GOLD else Palette.MUTED
            card.set(scroll.rect.left + 4f * u, top, scroll.rect.right - 4f * u, top + rowH - 10f * u)
            ui.neon.panel(c, card, 16f * u, Palette.withAlpha(Palette.PANEL, 0.9f), Palette.withAlpha(color, if (unlocked) 0.8f else 0.3f), if (unlocked) 0.6f else 0.1f)
            val ix = card.left + 34f * u
            ui.neon.circle(c, ix, card.centerY(), 22f * u, Palette.withAlpha(color, 0.15f))
            ui.icons.draw(c, Visuals.achievementIcon(a.icon), ix, card.centerY(), 24f * u, if (unlocked) Palette.GOLD else Palette.DIM, Palette.BG_TOP)
            val tx = card.left + 66f * u
            val tp = ui.style(ui.displayBoldPaint, 14f, if (unlocked) Palette.WHITE else Palette.DIM, Paint.Align.LEFT)
            ui.fitText(c, a.title, tx, card.top + 24f * u, tp, card.width() - 150f * u)
            val dp = ui.style(ui.semiPaint, 13f, Palette.TEXT, Paint.Align.LEFT)
            ui.fitText(c, a.text, tx, card.top + 43f * u, dp, card.width() - 90f * u)
            val rp = ui.style(ui.displayBoldPaint, 12f, Palette.GOLD, Paint.Align.RIGHT)
            ui.icons.draw(c, Icon.COIN, card.right - 16f * u, card.top + 20f * u, 14f * u, Palette.GOLD)
            c.drawText(a.coins, card.right - 28f * u, card.top + 25f * u, rp)
            if (unlocked) {
                ui.icons.draw(c, Icon.CHECK, card.right - 20f * u, card.bottom - 18f * u, 16f * u, Palette.GREEN)
            } else {
                val pp = ui.style(ui.mediumPaint, 11f, Palette.DIM, Paint.Align.RIGHT)
                val textLeft = card.right - 14f * u - pp.measureText(a.progress)
                bar.set(tx, card.bottom - 16f * u, textLeft - 10f * u, card.bottom - 10f * u)
                ui.neon.bar(c, bar, a.fraction, Palette.PURPLE, Palette.CYAN)
                c.drawText(a.progress, card.right - 14f * u, card.bottom - 9f * u, pp)
            }
        }
        c.restore()
        drawButtons(c)
    }

    companion object {
        private const val ROW = 82f
    }
}
