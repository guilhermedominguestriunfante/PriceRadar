package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Duel
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.core.audio.Sfx
import kotlin.math.min
import kotlin.math.sin

/**
 * Between the challenge and the match. The host waits for the friend's answer (a minute, with a
 * nudge over WhatsApp); the guest waits for the start. Opens the match once its start time is
 * set, or says why it won't happen.
 */
class DuelWaitScreen(app: GameApp, private val match: Duel.Match) : Screen(app) {
    private var opened = false
    private var failureSounded = false
    private val secondsText = com.dedonervoso.app.ui.NumText { it.toString() }

    /** Exposed for UI tests. */
    internal val matchForTest: Duel.Match get() = match

    override fun layout() {
        val w = min(width - 48f * u, 330f * u)
        val cx = width / 2f
        val h = 50f * u
        val over = match.stage == Duel.Stage.OVER
        val leave = button(if (over) s.back else s.cancel, if (over) Icon.BACK else Icon.CLOSE, Button.Style.SECONDARY, Palette.DIM) { leave() }
        leave.backSound = true
        leave.rect.set(cx - w / 2f, safe.bottom - h, cx + w / 2f, safe.bottom)
        var y = leave.rect.top - 12f * u - h
        if (match.host && match.stage == Duel.Stage.WAITING) {
            button(s.callOnWhatsApp, Icon.NEXT, Button.Style.SECONDARY, Palette.GREEN) { app.shareInvite() }
                .rect.set(cx - w / 2f, y, cx + w / 2f, y + h)
            y -= 12f * u + h
        }
        val failure = match.failure
        if (over && (failure == Duel.Failure.DECLINED || failure == Duel.Failure.NO_ANSWER) && app.duel.available) {
            button(s.challenge, Icon.BOLT, Button.Style.PRIMARY, Palette.MAGENTA) {
                app.host.replace(DuelWaitScreen(app, app.duel.challenge(match.rivalUid, match.rivalNick, match.rivalAvatar)))
            }.rect.set(cx - w / 2f, y - 8f * u, cx + w / 2f, y + h)
        }
    }

    private fun leave() {
        match.cancel()
        app.host.pop()
    }

    override fun onBack(): Boolean {
        leave()
        return true
    }

    override fun onBackgroundUpdate() {
        if (match.stage == Duel.Stage.OVER && match.failure != null && !failureSounded) {
            failureSounded = true
            app.sfx.play(Sfx.MISS)
            app.haptics.error()
        }
        relayout()
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.6f)
        ui.rings.update(dt)
        if (!opened && match.stage == Duel.Stage.PLAYING && app.duel.current === match) {
            opened = true
            app.sfx.play(Sfx.GO)
            app.host.replace(PlayScreen.duel(app, match))
        }
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.5f, 0.78f)
        ui.rings.draw(c)
        val cx = width / 2f
        val cy = safe.top + (safe.height() * 0.24f).coerceAtLeast(110f * u)
        val r = 52f * u
        val a = match.rivalAvatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)
        val color = Visuals.AVATAR_COLORS[a]
        val waiting = match.active
        val beat = (ui.time % 1.2f) / 1.2f
        if (waiting) ui.neon.circleStroke(c, cx, cy, r * (1f + beat * 0.7f), Palette.withAlpha(color, 1f - beat), 3f * u)
        ui.neon.glowBlob(c, cx, cy, r * 1.9f, color, 0.35f)
        ui.neon.circle(c, cx, cy, r, Palette.PANEL)
        ui.neon.circleStroke(c, cx, cy, r, color, 3f * u)
        ui.icons.draw(c, Visuals.AVATAR_ICONS[a], cx, cy, r * 1.05f, color, Palette.BG_TOP)
        val np = ui.style(ui.displayPaint, 24f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, match.rivalNick, cx, cy + r + 40f * u, np, width - 40f * u)

        val sp = ui.style(ui.semiPaint, 18f, if (match.failure != null) Palette.ORANGE else Palette.TEXT, Paint.Align.CENTER)
        ui.fitText(c, status(), cx, cy + r + 84f * u, sp, width - 40f * u)
        if (match.stage == Duel.Stage.WAITING) {
            val ty = cy + r + 150f * u
            val left = match.secondsLeft(SystemClock.uptimeMillis())
            ui.neon.ring(c, cx, ty, 30f * u, left / 60f, Palette.MAGENTA, 4f * u, Palette.withAlpha(Palette.WHITE, 0.1f))
            val tp = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.CENTER)
            c.drawText(secondsText.of(left), cx, ty + tp.textSize * 0.36f, tp)
            val hp = ui.style(ui.mediumPaint, 14f, Palette.DIM, Paint.Align.CENTER)
            ui.wrapText(c, s.waitingHint, cx, ty + 62f * u, hp, width - 64f * u, 19f * u)
        } else if (waiting) {
            // Joining / about to start: three dots.
            for (i in 0 until 3) {
                val k = 0.5f + 0.5f * sin(ui.time * 6f - i * 0.8f)
                ui.neon.circle(c, cx + (i - 1) * 22f * u, cy + r + 130f * u, 5f * u, Palette.withAlpha(Palette.MAGENTA, 0.3f + 0.7f * k))
            }
        }
        drawButtons(c)
    }

    private fun status(): String = when (match.stage) {
        Duel.Stage.CONNECTING -> if (match.host) s.connecting else s.joiningDuel
        Duel.Stage.WAITING -> s.waitingFor(match.rivalNick)
        Duel.Stage.STARTING -> s.joiningDuel
        Duel.Stage.PLAYING, Duel.Stage.FINISHED -> s.getReady
        Duel.Stage.OVER -> when (match.failure) {
            Duel.Failure.DECLINED -> s.declined(match.rivalNick)
            Duel.Failure.NO_ANSWER -> s.noAnswer(match.rivalNick)
            Duel.Failure.GONE -> s.challengeGone
            Duel.Failure.ERROR -> s.duelFailed
            null -> ""
        }
    }
}
