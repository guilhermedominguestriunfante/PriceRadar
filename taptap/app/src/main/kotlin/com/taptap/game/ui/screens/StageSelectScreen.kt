package com.taptap.game.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import com.taptap.game.GameApp
import com.taptap.game.core.stage.StageCatalog
import com.taptap.game.ui.Button
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.ScrollArea
import com.taptap.game.ui.Screen
import com.taptap.game.ui.Visuals

/** Stage map: every unlocked stage (plus a peek at the next locked ones). One tap plays. */
class StageSelectScreen(app: GameApp) : Screen(app) {
    private lateinit var scroll: ScrollArea
    private var savedOffset = -1f
    private val tiles = ArrayList<Pair<Int, Button>>()

    override fun layout() {
        val top = header(s.stageSelect)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        tiles.clear()
        val p = app.progression.save
        val cols = 4
        val gap = 10f * u
        val tileW = (scroll.rect.width() - gap * (cols + 1)) / cols
        val tileH = tileW * 1.05f
        val count = p.highestUnlocked + 4
        for (n in 1..count) {
            val i = n - 1
            val col = i % cols
            val row = i / cols
            val locked = n > p.highestUnlocked
            val color = if (locked) Palette.MUTED else Visuals.typeColor(StageCatalog.stage(n).type)
            val b = button(n.toString(), null, Button.Style.TILE, color) {
                app.progression.selectStage(n)
                app.host.replace(PlayScreen.forStage(app, n))
            }
            b.scroll = scroll
            b.enabled = !locked
            val l = scroll.rect.left + gap + col * (tileW + gap)
            val t = scroll.rect.top + gap + row * (tileH + gap)
            b.rect.set(l, t, l + tileW, t + tileH)
            tiles += n to b
        }
        val rows = (count + cols - 1) / cols
        scroll.contentHeight = gap + rows * (tileH + gap) + 20f * u
        if (savedOffset < 0f) {
            // Start with the current stage visible.
            val row = (p.selectedStage - 1) / cols
            savedOffset = (row * (tileH + gap) - scroll.rect.height() / 3f)
        }
        scroll.scrollTo(savedOffset)
    }

    override fun onExit() {
        if (::scroll.isInitialized) savedOffset = scroll.offset
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.5f)
        savedOffset = scroll.offset
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.8f)
        drawHeader(c)
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val save = app.progression.save
        for ((n, b) in tiles) {
            if (b.rect.bottom < scroll.offset + scroll.rect.top - 10f || b.rect.top > scroll.offset + scroll.rect.bottom + 10f) continue
            val locked = !b.enabled
            val stage = StageCatalog.stage(n)
            val color = if (locked) Palette.MUTED else Visuals.typeColor(stage.type)
            val selected = n == save.selectedStage
            val r = b.rect
            ui.neon.panel(c, r, 14f * u, Palette.withAlpha(Palette.mix(Palette.PANEL, color, 0.1f + 0.2f * b.press), 0.92f),
                Palette.withAlpha(color, if (selected) 1f else 0.6f), if (selected) 1.2f else 0.4f)
            if (locked) {
                ui.icons.draw(c, Icon.LOCK, r.centerX(), r.centerY() - 4f * u, r.height() * 0.3f, Palette.MUTED)
            } else {
                ui.icons.draw(c, if (stage.isBoss) Icon.SKULL else Visuals.typeIcon(stage.type), r.left + 14f * u, r.top + 14f * u, 13f * u, color)
                val p = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.CENTER)
                c.drawText(b.label, r.centerX(), r.centerY() + 6f * u, p)
                val stars = save.stageStars[n] ?: 0
                for (k in 0 until 3) {
                    ui.icons.draw(c, if (k < stars) Icon.STAR else Icon.STAR_OUTLINE, r.centerX() + (k - 1) * 15f * u, r.bottom - 13f * u, 12f * u,
                        if (k < stars) Palette.GOLD else Palette.MUTED)
                }
            }
        }
        c.restore()
        drawButtons(c)
    }
}
