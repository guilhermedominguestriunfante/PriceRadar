package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.progression.Mission
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals

/** Daily challenge (spec §52) and the three rotating missions (spec §53). */
class MissionsScreen(app: GameApp, @Suppress("unused") private val focusDaily: Boolean = false) : Screen(app) {
    private val dailyRect = RectF()
    private val cards = ArrayList<Pair<Mission, RectF>>()
    private val bar = RectF()
    private var dailyTitle = ""
    private var dailyObjective = ""
    private var dailyReward = ""
    private var dailyStreak = ""

    override fun onEnter() {
        prepare()
    }

    private fun prepare() {
        val p = app.progression
        val (template, config) = p.daily()
        dailyTitle = s.dailyTitle(template)
        dailyObjective = s.objectiveText(config)
        dailyReward = if (p.dailyRewardAvailable) s.dailyReward(com.dedonervoso.core.progression.DailyChallenge.rewardCoins(p.level, p.save.dailyStreak + 1))
            else s.dailyCompletedToday
        dailyStreak = s.dailyStreak(p.save.dailyStreak)
    }

    override fun layout() {
        val top = header(s.missions)
        dailyRect.set(safe.left + 4f * u, top, safe.right - 4f * u, top + 150f * u)
        val play = button(s.play, Icon.PLAY, Button.Style.SECONDARY, Palette.GOLD) { app.host.replace(PlayScreen.daily(app)) }
        play.rect.set(dailyRect.right - 128f * u, dailyRect.bottom - 56f * u, dailyRect.right - 14f * u, dailyRect.bottom - 14f * u)
        cards.clear()
        var y = dailyRect.bottom + 22f * u
        for (m in app.progression.save.missions.filter { !it.claimed }) {
            val r = RectF(safe.left + 4f * u, y, safe.right - 4f * u, y + 104f * u)
            cards += m to r
            if (m.done) {
                val claim = button(s.claim, Icon.CHECK, Button.Style.SECONDARY, Palette.GREEN) { claim(m, r) }
                claim.rect.set(r.right - 132f * u, r.bottom - 50f * u, r.right - 12f * u, r.bottom - 12f * u)
                claim.pulse = true
            }
            y += 116f * u
        }
    }

    private fun claim(m: Mission, r: RectF) {
        if (app.progression.claimMission(m.id)) {
            app.sfx.play(Sfx.BUY)
            app.haptics.celebrate()
            ui.particles.burst(r.centerX(), r.centerY(), 40, Palette.S_GOLD, 120f, 400f, 3f, 8f, 0.7f)
            ui.popups.add("+${s.num(m.rewardCoins)}", r.centerX(), r.top, Palette.GOLD, 22f, 1.2f, 50f, 0)
            app.announce(app.progression.unlockAchievements())
            prepare()
            relayout()
        }
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.5f)
        ui.particles.update(dt)
        ui.popups.update(dt)
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        // Daily challenge card.
        ui.neon.panel(c, dailyRect, 18f * u, Palette.withAlpha(Palette.mix(Palette.PANEL, Palette.GOLD, 0.06f), 0.94f), Palette.GOLD, 0.8f)
        ui.icons.draw(c, Icon.CALENDAR, dailyRect.left + 30f * u, dailyRect.top + 30f * u, 26f * u, Palette.GOLD)
        val hp = ui.style(ui.textPaint, 13f, Palette.GOLD, Paint.Align.LEFT)
        c.drawText(s.daily, dailyRect.left + 56f * u, dailyRect.top + 26f * u, hp)
        val tp = ui.style(ui.displayBoldPaint, 17f, Palette.WHITE, Paint.Align.LEFT)
        ui.fitText(c, dailyTitle, dailyRect.left + 56f * u, dailyRect.top + 48f * u, tp, dailyRect.width() - 70f * u)
        val op = ui.style(ui.semiPaint, 14f, Palette.TEXT, Paint.Align.LEFT)
        ui.fitText(c, dailyObjective, dailyRect.left + 18f * u, dailyRect.top + 78f * u, op, dailyRect.width() - 36f * u)
        val rp = ui.style(ui.textPaint, 14f, if (app.progression.dailyRewardAvailable) Palette.GOLD else Palette.GREEN, Paint.Align.LEFT)
        ui.fitText(c, dailyReward, dailyRect.left + 18f * u, dailyRect.bottom - 40f * u, rp, dailyRect.width() - 170f * u)
        val sp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.LEFT)
        c.drawText(dailyStreak, dailyRect.left + 18f * u, dailyRect.bottom - 20f * u, sp)
        // Missions.
        for ((m, r) in cards) {
            val color = if (m.done) Palette.GREEN else Palette.CYAN
            ui.neon.panel(c, r, 16f * u, Palette.withAlpha(Palette.PANEL, 0.92f), Palette.withAlpha(color, 0.7f), if (m.done) 0.8f else 0.3f)
            ui.neon.circle(c, r.left + 32f * u, r.top + 32f * u, 20f * u, Palette.withAlpha(color, 0.15f))
            ui.icons.draw(c, Visuals.missionIcon(m.kind), r.left + 32f * u, r.top + 32f * u, 22f * u, color)
            val mp = ui.style(ui.textPaint, 16f, Palette.WHITE, Paint.Align.LEFT)
            ui.fitText(c, s.missionText(m.kind, m.target), r.left + 62f * u, r.top + 30f * u, mp, r.width() - 80f * u)
            val rw = ui.style(ui.mediumPaint, 12.5f, Palette.GOLD, Paint.Align.LEFT)
            c.drawText("+${s.num(m.rewardCoins)} ${s.coinsLabel}  +${s.num(m.rewardXp)} XP", r.left + 62f * u, r.top + 50f * u, rw)
            bar.set(r.left + 18f * u, r.bottom - 30f * u, r.right - (if (m.done) 150f else 18f) * u, r.bottom - 22f * u)
            ui.neon.bar(c, bar, m.fraction, color, Palette.PURPLE)
            val pp = ui.style(ui.mediumPaint, 11f, Palette.DIM, Paint.Align.LEFT)
            c.drawText("${s.num(m.progress.coerceAtMost(m.target))}/${s.num(m.target)}", bar.left, bar.top - 5f * u, pp)
        }
        drawButtons(c)
        ui.particles.draw(c)
        ui.popups.draw(c)
    }
}
