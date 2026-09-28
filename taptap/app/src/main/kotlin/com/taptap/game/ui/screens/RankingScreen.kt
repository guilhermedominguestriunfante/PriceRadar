package com.taptap.game.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import com.taptap.game.GameApp
import com.taptap.game.core.progression.RankEntry
import com.taptap.game.core.progression.RankScope
import com.taptap.game.ui.Button
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.ScrollArea
import com.taptap.game.ui.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local ranking (spec §22) with all-time / week / today views. Reads through
 * [com.taptap.game.core.progression.LeaderboardSource], ready for online sources later.
 */
class RankingScreen(app: GameApp) : Screen(app) {
    private var scope = RankScope.ALL_TIME
    private lateinit var scroll: ScrollArea
    private var entries: List<RankEntry> = emptyList()
    private val tabs = ArrayList<Pair<RankScope, Button>>()
    private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private var latest: RankEntry? = null
    private val rowRect = android.graphics.RectF()

    /** Pre-formatted texts per row (built on load, not per frame). */
    private class Row(val entry: RankEntry, val score: String, val detail: String, val date: String)
    private var rows: List<Row> = emptyList()

    override fun onEnter() {
        load()
    }

    private fun load() {
        val p = app.progression
        entries = p.leaderboard.top(scope, p.today, 50)
        latest = p.save.ranking.maxByOrNull { it.timestamp }
        val s = app.strings
        rows = entries.map { e ->
            val stageText = if (e.daily) s.daily else "${s.stage} ${e.stage}"
            Row(e, s.num(e.score), "$stageText · COMBO ${s.num(e.maxCombo)} · ${s.dec1(e.maxTps)} ${s.tpsUnit}", dateFormat.format(Date(e.timestamp)))
        }
    }

    override fun layout() {
        val top = header(s.ranking)
        val tabW = (safe.width() - 16f * u) / 3f
        tabs.clear()
        for ((i, sc) in listOf(RankScope.ALL_TIME, RankScope.WEEK, RankScope.TODAY).withIndex()) {
            val label = when (sc) {
                RankScope.ALL_TIME -> s.allTime
                RankScope.WEEK -> s.week
                RankScope.TODAY -> s.today
            }
            val b = button(label, null, Button.Style.SECONDARY, if (sc == scope) Palette.MAGENTA else Palette.MUTED) {
                scope = sc
                load()
                relayout()
            }
            b.rect.set(safe.left + i * (tabW + 8f * u), top, safe.left + i * (tabW + 8f * u) + tabW, top + 40f * u)
            tabs += sc to b
        }
        scroll = scrollArea()
        scroll.rect.set(safe.left, top + 52f * u, safe.right, safe.bottom)
        scroll.contentHeight = entries.size * ROW * u + 12f * u
        if (entries.isEmpty()) {
            val play = button(s.play, null, Button.Style.PRIMARY, Palette.CYAN) {
                app.host.replace(PlayScreen.forStage(app, app.progression.save.selectedStage))
            }
            val w = kotlin.math.min(260f * u, width * 0.7f)
            play.rect.set(width / 2f - w / 2f, scroll.rect.top + 150f * u, width / 2f + w / 2f, scroll.rect.top + 210f * u)
        }
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.5f)
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        if (entries.isEmpty()) {
            val p = ui.style(ui.semiPaint, 17f, Palette.DIM, Paint.Align.CENTER)
            ui.wrapText(c, s.noResults, width / 2f, scroll.rect.top + 80f * u, p, width - 60f * u, 24f * u)
        }
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val rowH = ROW * u
        for ((i, row) in rows.withIndex()) {
            val e = row.entry
            val top = scroll.rect.top + 6f * u + i * rowH
            if (top + rowH < scroll.offset + scroll.rect.top || top > scroll.offset + scroll.rect.bottom) continue
            val mine = e === latest
            val medal = when (i) {
                0 -> Palette.GOLD
                1 -> 0xFFCFD8E8.toInt()
                2 -> 0xFFE09A5A.toInt()
                else -> Palette.MUTED
            }
            val r = rowRect
            r.set(scroll.rect.left + 4f * u, top, scroll.rect.right - 4f * u, top + rowH - 8f * u)
            ui.neon.panel(c, r, 14f * u, Palette.withAlpha(if (mine) Palette.mix(Palette.PANEL, Palette.CYAN, 0.18f) else Palette.PANEL, 0.9f),
                Palette.withAlpha(if (i < 3) medal else Palette.CYAN, if (mine) 0.9f else 0.35f), if (mine) 0.8f else 0.2f)
            val rp = ui.style(ui.displayPaint, 18f, medal, Paint.Align.CENTER)
            if (i < 3) ui.icons.draw(c, Icon.CROWN, r.left + 28f * u, r.centerY() - 10f * u, 16f * u, medal)
            c.drawText(RANKS[i.coerceAtMost(RANKS.lastIndex)], r.left + 28f * u, r.centerY() + (if (i < 3) 14f else 7f) * u, rp)
            val sp = ui.style(ui.displayPaint, 20f, Palette.WHITE, Paint.Align.LEFT)
            c.drawText(row.score, r.left + 58f * u, r.top + 30f * u, sp)
            val dp = ui.style(ui.mediumPaint, 12.5f, Palette.DIM, Paint.Align.LEFT)
            ui.fitText(c, row.detail, r.left + 58f * u, r.top + 50f * u, dp, r.width() - 150f * u)
            val tp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.RIGHT)
            c.drawText(row.date, r.right - 14f * u, r.top + 28f * u, tp)
            if (e.suspicious) {
                tp.color = Palette.ORANGE
                c.drawText(flaggedText, r.right - 14f * u, r.top + 48f * u, tp)
            } else if (mine) {
                tp.color = Palette.CYAN
                c.drawText(app.progression.save.profile.nickname, r.right - 14f * u, r.top + 48f * u, tp)
            }
        }
        c.restore()
        drawButtons(c)
    }

    private val flaggedText by lazy { "⚠ " + app.strings.flagged }

    companion object {
        private const val ROW = 72f
        private val RANKS = Array(60) { (it + 1).toString() }
    }
}
