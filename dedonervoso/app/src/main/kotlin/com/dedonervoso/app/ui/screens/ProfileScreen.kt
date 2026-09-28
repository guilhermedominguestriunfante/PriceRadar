package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.progression.Achievements
import com.dedonervoso.core.save.SaveCodec
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScrollArea
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals

/** Local profile (spec §20) with the full statistics sheet (spec §24). No account needed. */
class ProfileScreen(app: GameApp) : Screen(app) {
    private lateinit var scroll: ScrollArea
    private var savedOffset = 0f
    private val avatarRect = RectF()
    private val levelBar = RectF()
    private var nameY = 0f
    private var statsTop = 0f
    private val stats = ArrayList<Pair<String, String>>()
    private var levelText = ""
    private var levelFraction = 0f
    private var xpText = ""
    private var achText = ""
    private var avatarHint = ""

    override fun onEnter() {
        buildStats()
    }

    private fun buildStats() {
        val st = app.progression.save.stats
        val save = app.progression.save
        val s = app.strings
        stats.clear()
        stats += s.statTotalTaps to s.num(st.totalTaps)
        stats += s.statBestScore to s.num(st.bestScore)
        stats += s.statMaxCombo to s.num(st.maxCombo)
        stats += s.statMaxTps to "${s.dec1(st.maxTps)} ${s.tpsUnit}"
        stats += s.statAvgTps to "${s.dec1(st.avgTps)} ${s.tpsUnit}"
        stats += s.statPerfects to s.num(st.perfects)
        stats += s.statStopErrors to s.num(st.stopErrors)
        stats += s.statStopsSurvived to s.num(st.stopsSurvived)
        stats += s.statZoneHits to s.num(st.zoneHits)
        stats += s.statCoins to s.num(st.coinsEarned)
        stats += s.statPlayTime to s.duration(st.playTimeMs)
        stats += s.statStages to s.num(save.highestCleared)
        stats += s.statStars to s.num(save.totalStars)
        stats += s.statMatches to s.num(st.matches)
        stats += s.statWins to s.num(st.wins)
        stats += s.statFrenzies to s.num(st.frenzies)
        stats += s.statBosses to s.num(st.bossesDefeated)
        stats += s.statReflex to if (st.reflexBestMs >= 0) "${st.reflexBestMs} ms" else "—"
        val info = app.progression.levelInfo
        levelText = "${s.level} ${info.level}"
        levelFraction = info.fraction
        xpText = "${s.num(info.xpIntoLevel)} / ${s.num(info.xpForNext)} XP"
        achText = "${save.achievements.size}/${Achievements.ALL.size}"
        avatarHint = s.avatar + " ↻"
    }

    override fun layout() {
        val top = header(s.profile)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        val cx = width / 2f
        var y = scroll.rect.top + 8f * u
        val ar = 48f * u
        avatarRect.set(cx - ar, y, cx + ar, y + ar * 2f)
        val avatar = button("", null, Button.Style.GHOST) {
            app.progression.setAvatar((app.progression.save.profile.avatar + 1) % SaveCodec.AVATAR_COUNT)
        }
        avatar.scroll = scroll
        avatar.rect.set(avatarRect)
        y = avatarRect.bottom + 44f * u
        nameY = y
        val edit = button(s.editName, Icon.USER, Button.Style.SECONDARY, Palette.CYAN) { app.editNickname { } }
        edit.scroll = scroll
        edit.rect.set(cx - 90f * u, y + 12f * u, cx + 90f * u, y + 52f * u)
        y += 76f * u
        levelBar.set(scroll.rect.left + 20f * u, y + 22f * u, scroll.rect.right - 20f * u, y + 34f * u)
        y += 56f * u
        val ach = button(s.achievements, Icon.MEDAL, Button.Style.SECONDARY, Palette.GOLD) { app.host.push(AchievementsScreen(app)) }
        ach.scroll = scroll
        ach.rect.set(scroll.rect.left + 16f * u, y, scroll.rect.right - 16f * u, y + 50f * u)
        y += 88f * u
        statsTop = y
        y += 30f * u + stats.size * 34f * u
        scroll.contentHeight = y - scroll.rect.top + 20f * u
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
        val save = app.progression.save
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val cx = avatarRect.centerX()
        val cy = avatarRect.centerY()
        val r = avatarRect.width() / 2f
        val color = Visuals.AVATAR_COLORS[save.profile.avatar]
        ui.neon.glowBlob(c, cx, cy, r * 1.8f, color, 0.35f)
        ui.neon.circle(c, cx, cy, r, Palette.PANEL)
        ui.neon.circleStroke(c, cx, cy, r, color, 3f * u)
        ui.icons.draw(c, Visuals.AVATAR_ICONS[save.profile.avatar], cx, cy, r * 1.05f, color, Palette.BG_TOP)
        val hint = ui.style(ui.mediumPaint, 11f, Palette.DIM, Paint.Align.CENTER)
        c.drawText(avatarHint, cx, avatarRect.bottom + 16f * u, hint)
        val np = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, save.profile.nickname, cx, nameY, np, width - 40f * u)
        drawButtons(c, scroll)
        // Level.
        val lp = ui.style(ui.displayBoldPaint, 15f, Palette.GREEN, Paint.Align.LEFT)
        c.drawText(levelText, levelBar.left, levelBar.top - 8f * u, lp)
        val xp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.RIGHT)
        c.drawText(xpText, levelBar.right, levelBar.top - 8f * u, xp)
        ui.neon.bar(c, levelBar, levelFraction, Palette.GREEN, Palette.CYAN)
        // Achievements count on the button.
        val cp = ui.style(ui.displayBoldPaint, 14f, Palette.GOLD, Paint.Align.RIGHT)
        val achButton = buttons.last { it.scroll === scroll }
        c.drawText(achText, achButton.rect.right - 16f * u, achButton.rect.centerY() + 5f * u, cp)
        // Statistics sheet.
        val hp = ui.style(ui.displayPaint, 17f, Palette.CYAN, Paint.Align.LEFT)
        c.drawText(s.stats, scroll.rect.left + 16f * u, statsTop, hp)
        val lbl = ui.style(ui.semiPaint, 15f, Palette.TEXT, Paint.Align.LEFT)
        val v = ui.style(ui.displayBoldPaint, 15f, Palette.WHITE, Paint.Align.RIGHT)
        var y = statsTop + 30f * u
        for ((i, row) in stats.withIndex()) {
            if (i % 2 == 0) {
                ui.neon.fill.color = Palette.withAlpha(Palette.WHITE, 0.035f)
                c.drawRect(scroll.rect.left + 8f * u, y - 22f * u, scroll.rect.right - 8f * u, y + 10f * u, ui.neon.fill)
            }
            c.drawText(row.first, scroll.rect.left + 18f * u, y, lbl)
            c.drawText(row.second, scroll.rect.right - 18f * u, y, v)
            y += 34f * u
        }
        c.restore()
        drawButtons(c)
    }
}
