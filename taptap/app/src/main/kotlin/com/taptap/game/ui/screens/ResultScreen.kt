package com.taptap.game.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.taptap.game.GameApp
import com.taptap.game.core.audio.MusicMode
import com.taptap.game.core.audio.Sfx
import com.taptap.game.core.engine.FailReason
import com.taptap.game.core.progression.Economy
import com.taptap.game.core.progression.MatchOutcome
import com.taptap.game.ui.Button
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.Screen
import com.taptap.game.ui.Visuals
import com.taptap.game.core.util.Ease
import kotlin.math.max
import kotlin.math.min

/**
 * Match results (spec §26): progressive reveal of SCORE, TAPS, MAX TPS, COMBO, PERFECT, COINS
 * and XP, stars, record celebration (spec §58) and one-tap replay / next stage / home.
 */
class ResultScreen(app: GameApp, private val outcome: MatchOutcome, private val dailyTitle: String?) : Screen(app) {
    private val r = outcome.result
    private val panel = RectF()
    private val xpRect = RectF()
    private var rowsTop = 0f
    private var rowH = 0f
    private var labelSize = 15f
    private var valueSize = 19f
    private var starCenterY = 0f
    private var starSize = 0f
    private var recordY = 0f
    private var starsPlayed = 0
    private var recordPlayed = false
    private var levelPlayed = false
    private var announced = false
    private var skipped = false
    private val rows = ArrayList<Pair<String, String>>()
    private val xpBefore: Economy.LevelInfo
    private val xpAfter: Economy.LevelInfo
    private var hint: Button? = null

    init {
        val totalAfter = app.progression.save.totalXp
        xpAfter = Economy.levelFor(totalAfter)
        xpBefore = Economy.levelFor(totalAfter - outcome.xpGained)
    }

    override val musicMode: MusicMode get() = MusicMode.RESULT

    override fun onEnter() {
        age = 0f
        app.keepScreenOn(false)
        val s = app.strings
        rows.clear()
        rows += "SCORE" to s.num(r.score)
        rows += "TAPS" to s.num(r.taps)
        rows += "MAX TPS" to s.dec1(r.maxTps)
        rows += "COMBO" to s.num(r.maxCombo)
        rows += "PERFECT" to s.num(r.perfects)
        rows += s.coinsLabel to "+" + s.num(outcome.totalCoins)
        rows += s.xpLabel to "+" + s.num(outcome.xpGained)
    }

    private val revealEnd: Float get() = ROWS_AT + rows.size * ROW_GAP + 0.3f

    override fun layout() {
        val w = width
        val side = safe.left + 8f * u
        val right = safe.right - 8f * u
        val bottom = safe.bottom
        val btnH = 60f * u
        val home = button(s.home, Icon.HOME, Button.Style.SECONDARY, Palette.DIM) { app.host.pop() }
        val canNext = r.won && !outcome.daily && r.stageNumber >= 1
        val primary = if (canNext) {
            button(s.nextStage, null, Button.Style.PRIMARY, Palette.CYAN) { next() }
        } else {
            button(s.playAgain, null, Button.Style.PRIMARY, if (r.won) Palette.CYAN else Palette.MAGENTA) { replay() }
        }
        primary.pulse = true
        val secondaryH = 50f * u
        primary.rect.set(side, bottom - secondaryH - 12f * u - btnH, right, bottom - secondaryH - 12f * u)
        if (canNext) {
            val again = button(s.playAgain, Icon.RETRY, Button.Style.SECONDARY, Palette.MAGENTA) { replay() }
            val half = (right - side - 10f * u) / 2f
            again.rect.set(side, bottom - secondaryH, side + half, bottom)
            home.rect.set(side + half + 10f * u, bottom - secondaryH, right, bottom)
        } else {
            home.rect.set(side, bottom - secondaryH, right, bottom)
        }
        // Title, then the stars, then the record banner between the stars and the panel.
        val compact = safe.height() < 660f * u
        starSize = (if (compact) 38f else 46f) * u
        starCenterY = safe.top + (if (compact) 98f else 112f) * u
        recordY = starCenterY + starSize / 2f + (if (compact) 30f else 36f) * u
        val panelTop = recordY + 12f * u
        val upgrade = app.progression.affordableUpgrade()
        // The upgrade hint is dropped rather than squeezing the stats below readability.
        val hintFits = primary.rect.top - 70f * u - panelTop >= rows.size * 26f * u + 60f * u
        hint = if (upgrade != null && hintFits) {
            button(s.canBuy(s.upgradeName(upgrade)), Icon.UP, Button.Style.SECONDARY, Palette.GOLD) { app.host.replace(ShopScreen(app)) }.also {
                it.rect.set(side, primary.rect.top - 12f * u - 44f * u, right, primary.rect.top - 12f * u)
            }
        } else {
            null
        }
        val panelBottom = (hint?.rect?.top ?: primary.rect.top) - 14f * u
        panel.set(side, panelTop, right, panelBottom)
        val xpBlock = 52f * u
        val n = rows.size.coerceAtLeast(1)
        rowH = min(46f * u, (panel.height() - xpBlock - 16f * u) / n)
        rowsTop = panel.top + max(12f * u, (panel.height() - xpBlock - n * rowH) / 2f)
        labelSize = min(16f, rowH / u * 0.44f)
        valueSize = min(24f, rowH / u * 0.56f)
        xpRect.set(panel.left + 18f * u, panel.bottom - 26f * u, panel.right - 18f * u, panel.bottom - 16f * u)
        for (b in buttons) b.enabled = true
        if (w <= 0f) return
    }

    private fun replay() {
        app.host.replace(if (outcome.daily) PlayScreen.daily(app) else PlayScreen.forStage(app, r.stageNumber))
    }

    private fun next() {
        val n = (r.stageNumber + 1).coerceAtMost(app.progression.save.highestUnlocked)
        app.progression.selectStage(n)
        app.host.replace(PlayScreen.forStage(app, n))
    }

    override fun onTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && age < revealEnd && buttons.none { it.contains(e.x, e.y) }) {
            skipped = true
            age = revealEnd
            return true
        }
        return super.onTouch(e)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.4f)
        ui.particles.update(dt)
        ui.rings.update(dt)
        ui.popups.update(dt)
        // Stars, one by one.
        if (r.won) {
            while (starsPlayed < r.stars && age >= STARS_AT + starsPlayed * STAR_GAP) {
                val i = starsPlayed++
                val cx = starX(i)
                val cy = starY()
                if (!skipped) app.sfx.play(if (i == 0) Sfx.STAR_1 else if (i == 1) Sfx.STAR_2 else Sfx.STAR_3)
                ui.particles.burst(cx, cy, 24, Palette.S_GOLD, 120f, 360f, 3f, 7f, 0.6f)
                ui.rings.add(cx, cy, 10f, 60f, Palette.GOLD, 0.5f, 4f)
            }
        }
        if (!recordPlayed && outcome.newBestScore && age >= revealEnd) {
            recordPlayed = true
            app.sfx.play(Sfx.RECORD)
            app.haptics.celebrate()
            ui.particles.confetti(width, 0f, 140)
        }
        if (!levelPlayed && outcome.levelAfter > outcome.levelBefore && age >= revealEnd + 0.6f) {
            levelPlayed = true
            app.sfx.play(Sfx.LEVEL_UP)
            ui.popups.add(s.levelUp(outcome.levelAfter), width / 2f, xpRect.top - 16f * u, Palette.GREEN, 22f, 1.6f, 30f, 0)
        }
        if (!announced && age >= revealEnd + 1f) {
            announced = true
            app.announce(outcome.achievements)
        }
    }

    private fun starX(i: Int) = width / 2f + (i - 1) * starSize * 1.4f
    private fun starY() = starCenterY

    override fun draw(c: Canvas) {
        drawBackdrop(c, gridAlpha = 0.45f, horizon = 0.78f)
        val won = r.won
        val titleColor = if (won) Palette.GREEN else Palette.ORANGE
        // Stage label.
        val lp = ui.style(ui.textPaint, 15f, Visuals.typeColor(r.stageType), Paint.Align.CENTER)
        val label = if (outcome.daily) (dailyTitle ?: s.daily) else "${s.stage} ${r.stageNumber}" + (if (r.isBoss) " · ${s.boss}" else "")
        c.drawText(label, width / 2f, safe.top + 22f * u, lp)
        // Title slam.
        val t = Ease.outBack((age / 0.4f).coerceIn(0f, 1f), 2f)
        val tp = ui.style(ui.displayPaint, 30f, Palette.WHITE, Paint.Align.CENTER)
        tp.textSize *= 1.4f - 0.4f * t
        val title = when {
            outcome.daily && won && outcome.dailyCoins > 0 -> s.dailyDone
            won -> s.stageClear
            r.failReason == FailReason.NO_LIVES -> s.outOfLives
            else -> s.stageFailed
        }
        ui.fitSize(tp, title, safe.width() - 24f * u)
        ui.neon.glowText(c, title, width / 2f, safe.top + 64f * u, tp, titleColor, 16f * u)
        // Stars.
        for (i in 0 until 3) {
            val on = i < starsPlayed
            val appear = if (on) Ease.outBack(((age - STARS_AT - i * STAR_GAP) / 0.3f).coerceIn(0f, 1f), 2.5f) else 1f
            val size = starSize * (if (on) appear else 1f)
            if (on) ui.neon.glowBlob(c, starX(i), starY(), size * 1.3f, Palette.GOLD, 0.35f)
            ui.icons.draw(c, if (on) Icon.STAR else Icon.STAR_OUTLINE, starX(i), starY(), size, if (on) Palette.GOLD else Palette.MUTED)
        }
        // Stats panel.
        ui.neon.panel(c, panel, 18f * u, Palette.withAlpha(Palette.PANEL, 0.92f), Palette.withAlpha(titleColor, 0.7f), 0.6f)
        for ((i, row) in rows.withIndex()) {
            val appear = ((age - ROWS_AT - i * ROW_GAP) / 0.25f).coerceIn(0f, 1f)
            if (appear <= 0f) continue
            val y = rowsTop + (i + 0.7f) * rowH
            val slide = (1f - Ease.outCubic(appear)) * 30f * u
            val labelP = ui.style(ui.textPaint, labelSize, Palette.withAlpha(Palette.DIM, appear), Paint.Align.LEFT)
            val valueP = ui.style(ui.displayPaint, valueSize, Palette.WHITE, Paint.Align.RIGHT)
            c.drawText(row.first, panel.left + 18f * u - slide, y, labelP)
            valueP.color = Palette.withAlpha(if (i == 5) Palette.GOLD else if (i == 6) Palette.GREEN else Palette.WHITE, appear)
            val text = if (i == 0 && appear < 1f || i == 0 && age < ROWS_AT + 0.8f) {
                s.num((r.score * Ease.outCubic(((age - ROWS_AT) / 0.8f).coerceIn(0f, 1f))).toLong())
            } else {
                row.second
            }
            c.drawText(text, panel.right - 18f * u + slide, y, valueP)
            if (i == 0 && outcome.newBestScore && age >= revealEnd) {
                val bp = ui.style(ui.mediumPaint, 12f, Palette.GOLD, Paint.Align.RIGHT)
                c.drawText(s.newRecord, panel.right - 18f * u, y - valueSize * u - 4f * u, bp)
            }
        }
        // XP bar (before → after).
        if (age >= ROWS_AT + rows.size * ROW_GAP) {
            val fill = Ease.outCubic(((age - revealEnd) / 0.8f).coerceIn(0f, 1f))
            val frac = if (xpAfter.level > xpBefore.level) {
                if (fill < 0.5f) xpBefore.fraction + (1f - xpBefore.fraction) * fill * 2f else xpAfter.fraction * (fill - 0.5f) * 2f
            } else {
                xpBefore.fraction + (xpAfter.fraction - xpBefore.fraction) * fill
            }
            ui.neon.bar(c, xpRect, frac, Palette.GREEN, Palette.CYAN)
            val xp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.LEFT)
            c.drawText("${s.level} ${if (fill >= 0.5f) xpAfter.level else xpBefore.level}", xpRect.left, xpRect.top - 6f * u, xp)
        }
        // Record banner.
        if (outcome.newBestScore && age >= revealEnd) {
            val k = ((age - revealEnd) / 0.45f).coerceIn(0f, 1f)
            val bp = ui.style(ui.displayPaint, 28f, Palette.GOLD, Paint.Align.CENTER)
            ui.fitSize(bp, s.newRecord, safe.width() - 24f * u)
            bp.textSize *= 0.5f + 0.5f * Ease.outBack(k, 2.5f)
            ui.neon.glowText(c, s.newRecord, width / 2f, recordY, bp, Palette.GOLD, 18f * u)
        }
        ui.rings.draw(c)
        drawButtons(c)
        ui.particles.draw(c)
        ui.popups.draw(c)
    }

    companion object {
        private const val STARS_AT = 0.45f
        private const val STAR_GAP = 0.32f
        private const val ROWS_AT = 1.1f
        private const val ROW_GAP = 0.14f
    }
}
