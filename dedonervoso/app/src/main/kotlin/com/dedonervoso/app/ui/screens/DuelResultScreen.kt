package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Duel
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.core.audio.MusicMode
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.util.Ease
import kotlin.math.min
import kotlin.math.sin

/**
 * End of a live duel: waits for the rival's final score (a walkover after 15 s), then VICTORY,
 * DEFEAT or DRAW with both scores and the items thrown and received. REMATCH challenges the
 * same friend again.
 */
class DuelResultScreen(app: GameApp, private val match: Duel.Match) : Screen(app) {
    private val mine = RectF()
    private val theirs = RectF()
    private var revealedAt = -1f
    private var myScore = ""
    private var rivalScore = ""
    private var items = ""

    /** Exposed for UI tests. */
    internal val matchForTest: Duel.Match get() = match

    override val musicMode: MusicMode get() = MusicMode.RESULT

    override fun onEnter() {
        age = 0f
        app.keepScreenOn(false)
        refreshTexts()
    }

    private fun refreshTexts() {
        myScore = s.num(match.myScore)
        rivalScore = s.num(match.rivalScore)
        items = s.itemsSummary(match.itemsThrown, match.itemsReceived)
    }

    override fun onBackgroundUpdate() {
        refreshTexts()
        relayout()
    }

    override fun layout() {
        val w = min(safe.width() - 16f * u, 380f * u)
        val cx = width / 2f
        val back = button(s.back, Icon.BACK, Button.Style.SECONDARY, Palette.DIM) { app.host.pop() }
        back.backSound = true
        back.rect.set(cx - w / 2f, safe.bottom - 50f * u, cx + w / 2f, safe.bottom)
        if (match.outcome != null && app.duel.available) {
            button(s.rematch, Icon.RETRY, Button.Style.PRIMARY, Palette.MAGENTA) {
                app.host.replace(DuelWaitScreen(app, app.duel.challenge(match.rivalUid, match.rivalNick, match.rivalAvatar)))
            }.apply {
                rect.set(cx - w / 2f, back.rect.top - 14f * u - 60f * u, cx + w / 2f, back.rect.top - 14f * u)
                pulse = true
            }
        }
        val top = safe.top + 150f * u
        val half = (w - 44f * u) / 2f
        mine.set(cx - w / 2f, top, cx - w / 2f + half, top + 170f * u)
        theirs.set(cx + w / 2f - half, top, cx + w / 2f, top + 170f * u)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.4f)
        ui.particles.update(dt)
        ui.rings.update(dt)
        val outcome = match.outcome
        if (revealedAt < 0f && outcome != null) {
            revealedAt = age
            refreshTexts()
            relayout()
            when (outcome) {
                Duel.Outcome.WIN -> {
                    app.sfx.play(Sfx.WIN)
                    app.haptics.celebrate()
                    ui.particles.confetti(width, 0f, 120)
                }
                Duel.Outcome.LOSS -> app.sfx.play(Sfx.LOSE)
                Duel.Outcome.DRAW -> app.sfx.play(Sfx.STAR_2)
            }
        }
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, gridAlpha = 0.45f, horizon = 0.78f)
        val cx = width / 2f
        val lp = ui.style(ui.textPaint, 15f, Palette.MAGENTA, Paint.Align.CENTER)
        c.drawText(s.duel, cx, safe.top + 22f * u, lp)
        val outcome = match.outcome
        if (outcome == null) {
            val wp = ui.style(ui.semiPaint, 18f, Palette.TEXT, Paint.Align.CENTER)
            ui.fitText(c, s.waitingScore(match.rivalNick), cx, safe.top + 74f * u, wp, safe.width() - 24f * u)
            for (i in 0 until 3) {
                val k = 0.5f + 0.5f * sin(ui.time * 6f - i * 0.8f)
                ui.neon.circle(c, cx + (i - 1) * 22f * u, safe.top + 104f * u, 5f * u, Palette.withAlpha(Palette.MAGENTA, 0.3f + 0.7f * k))
            }
        } else {
            val (title, color) = when (outcome) {
                Duel.Outcome.WIN -> s.victory to Palette.GREEN
                Duel.Outcome.LOSS -> s.defeat to Palette.RED
                Duel.Outcome.DRAW -> s.draw to Palette.GOLD
            }
            val t = Ease.outBack(((age - revealedAt) / 0.4f).coerceIn(0f, 1f), 2f)
            val tp = ui.style(ui.displayPaint, 36f, Palette.WHITE, Paint.Align.CENTER)
            tp.textSize *= 1.4f - 0.4f * t
            ui.fitSize(tp, title, safe.width() - 24f * u)
            ui.neon.glowText(c, title, cx, safe.top + 82f * u, tp, color, 18f * u)
            val why = when (match.reason) {
                Duel.Reason.WALKOVER -> s.walkover
                Duel.Reason.RIVAL_GAVE_UP -> s.rivalGaveUp
                Duel.Reason.GAVE_UP -> s.youGaveUp
                Duel.Reason.SCORE -> ""
            }
            if (why.isNotEmpty()) {
                val rp = ui.style(ui.semiPaint, 16f, Palette.DIM, Paint.Align.CENTER)
                ui.fitText(c, why, cx, safe.top + 118f * u, rp, safe.width() - 24f * u)
            }
        }
        val winner = when (outcome) {
            Duel.Outcome.WIN -> mine
            Duel.Outcome.LOSS -> theirs
            else -> null
        }
        val me = app.progression.save.profile
        card(c, mine, s.you, me.nickname, me.avatar, myScore, winner === mine)
        card(c, theirs, "", match.rivalNick, match.rivalAvatar, rivalScore, winner === theirs, dim = outcome == null)
        val vp = ui.style(ui.displayPaint, 20f, Palette.MAGENTA, Paint.Align.CENTER)
        ui.neon.glowText(c, "VS", cx, mine.centerY() + vp.textSize * 0.36f, vp, Palette.MAGENTA, 10f * u)
        val ip = ui.style(ui.mediumPaint, 15f, Palette.DIM, Paint.Align.CENTER)
        ui.fitText(c, items, cx, mine.bottom + 34f * u, ip, safe.width() - 24f * u)
        drawButtons(c)
        ui.rings.draw(c)
        ui.particles.draw(c)
    }

    private fun card(c: Canvas, r: RectF, label: String, nick: String, avatar: Int, score: String, won: Boolean, dim: Boolean = false) {
        val a = avatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)
        val color = Visuals.AVATAR_COLORS[a]
        ui.neon.panel(
            c, r, 18f * u, Palette.withAlpha(Palette.PANEL, 0.92f),
            if (won) Palette.GOLD else Palette.withAlpha(color, 0.6f), if (won) 1.2f else 0.4f,
        )
        val cx = r.centerX()
        val cy = r.top + 44f * u
        ui.neon.circle(c, cx, cy, 26f * u, Palette.PANEL)
        ui.neon.circleStroke(c, cx, cy, 26f * u, color, 2.5f * u)
        ui.icons.draw(c, Visuals.AVATAR_ICONS[a], cx, cy, 28f * u, color, Palette.BG_TOP)
        if (won) ui.icons.draw(c, Icon.CROWN, cx, cy - 38f * u, 22f * u, Palette.GOLD)
        val np = ui.style(ui.textPaint, 16f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, nick, cx, cy + 50f * u, np, r.width() - 16f * u)
        if (label.isNotEmpty()) {
            val lp = ui.style(ui.mediumPaint, 12f, Palette.CYAN, Paint.Align.CENTER)
            c.drawText(label, cx, cy + 68f * u, lp)
        }
        val sp = ui.style(ui.displayPaint, 26f, if (dim) Palette.DIM else Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, score, cx, r.bottom - 18f * u, sp, r.width() - 16f * u)
    }
}
