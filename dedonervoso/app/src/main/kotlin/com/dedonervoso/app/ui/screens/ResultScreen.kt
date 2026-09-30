package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.audio.MusicMode
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.engine.FailReason
import com.dedonervoso.core.progression.Economy
import com.dedonervoso.core.progression.MatchOutcome
import com.dedonervoso.core.stage.StageType
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.core.util.Ease
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Match results (spec §26) with a progressive reveal, stars, record celebration (spec §58) and
 * one-tap replay / next stage / home.
 *
 * A stage shows the time it took first (it grades the stars), then score, taps, tap rate,
 * combo, accuracy and reflex, coins and XP, and what the next star asks for. The Arena shows the
 * score against this week's best and, online, where it stands on this week's board.
 */
class ResultScreen(app: GameApp, private val outcome: MatchOutcome, private val dailyTitle: String?) : Screen(app) {
    private val r = outcome.result
    private val arena = outcome.arena
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
    private val rows = ArrayList<Row>()
    private var standingRow: Row? = null
    private var starHint = ""
    private val xpBefore: Economy.LevelInfo
    private val xpAfter: Economy.LevelInfo
    private var hint: Button? = null

    /** One line of the panel; [value] may arrive later (the Arena's standing). */
    private class Row(val label: String, var value: String, val color: Int = Palette.WHITE, val countsUp: Boolean = false)

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
        val stage = outcome.stage
        if (!arena && r.won && stage.endOnObjective) {
            rows += Row(s.timeLabel, s.seconds(r.gradeTimeMs), Palette.GOLD)
        }
        rows += Row("SCORE", s.num(r.score), countsUp = true)
        rows += Row("TAPS", s.num(r.taps))
        rows += Row(s.avgMaxLabel, "${s.dec1(r.avgTps)} · ${s.dec1(r.maxTps)}")
        rows += Row("COMBO", s.num(r.maxCombo))
        rows += Row(s.precisionLabel, "${(r.precision * 100).roundToInt()}%")
        if (r.reflexBestMs >= 0) rows += Row(s.reflexLabel, "${r.reflexBestMs} ms")
        if (arena && app.online.enabled) {
            standingRow = Row(s.arenaWeek, "…", Palette.CYAN).also { rows += it }
            app.online.weekStanding(r.score) { standing ->
                val row = standingRow ?: return@weekStanding
                row.value = when {
                    standing == null -> "—"
                    standing.total < SMALL_BOARD -> "#${standing.above + 1} / ${standing.total}"
                    else -> s.topPercent(standing.topPercent)
                }
            }
        }
        rows += Row(s.coinsLabel, "+" + s.num(outcome.totalCoins), Palette.GOLD)
        rows += Row(s.xpLabel, "+" + s.num(outcome.xpGained), Palette.GREEN)
        starHint = when {
            arena || !r.won || !stage.endOnObjective -> ""
            stage.type == StageType.SURVIVAL -> s.survivalGrade
            r.stars == 3 || !stage.timedStars -> if (r.stopErrors > 0) s.errorsPenalty else ""
            r.stars == 2 -> s.starTime(3, s.seconds(stage.star3TimeMs))
            else -> s.starTime(2, s.seconds(stage.star2TimeMs))
        }
    }

    private val revealEnd: Float get() = ROWS_AT + rows.size * ROW_GAP + 0.3f

    override fun layout() {
        val w = width
        val side = safe.left + 8f * u
        val right = safe.right - 8f * u
        val bottom = safe.bottom
        val btnH = 60f * u
        val home = button(s.home, Icon.HOME, Button.Style.SECONDARY, Palette.DIM) { app.host.pop() }
        val canNext = r.won && !outcome.daily && !arena && r.stageNumber >= 1
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
        // Title, then the stars (or the Arena's week best), then the record banner above the panel.
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
        app.host.replace(
            when {
                arena -> PlayScreen.arena(app)
                outcome.daily -> PlayScreen.daily(app)
                else -> PlayScreen.forStage(app, r.stageNumber)
            },
        )
    }

    private fun next() {
        val n = (r.stageNumber + 1).coerceAtMost(app.progression.save.highestUnlocked)
        app.progression.selectStage(n)
        app.host.replace(PlayScreen.forStage(app, n))
    }

    override fun onExit() {
        standingRow = null
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
        if (r.won && !arena) {
            while (starsPlayed < r.stars && age >= STARS_AT + starsPlayed * STAR_GAP) {
                val i = starsPlayed++
                val cx = starX(i)
                val cy = starY()
                if (!skipped) app.sfx.play(if (i == 0) Sfx.STAR_1 else if (i == 1) Sfx.STAR_2 else Sfx.STAR_3)
                ui.particles.burst(cx, cy, 24, Palette.S_GOLD, 120f, 360f, 3f, 7f, 0.6f)
                ui.rings.add(cx, cy, 10f, 60f, Palette.GOLD, 0.5f, 4f)
            }
        }
        if (!recordPlayed && outcome.newRecord && age >= revealEnd) {
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
        val titleColor = if (arena) Palette.GOLD else if (won) Palette.GREEN else Palette.ORANGE
        // Stage label.
        val lp = ui.style(ui.textPaint, 15f, if (arena) Palette.GOLD else Visuals.typeColor(r.stageType), Paint.Align.CENTER)
        val label = when {
            arena -> s.arena
            outcome.daily -> dailyTitle ?: s.daily
            else -> "${s.stage} ${r.stageNumber}" + (if (r.isBoss) " · ${s.boss}" else "")
        }
        c.drawText(label, width / 2f, safe.top + 22f * u, lp)
        // Title slam.
        val t = Ease.outBack((age / 0.4f).coerceIn(0f, 1f), 2f)
        val tp = ui.style(ui.displayPaint, 30f, Palette.WHITE, Paint.Align.CENTER)
        tp.textSize *= 1.4f - 0.4f * t
        val title = when {
            arena -> s.timeUp
            outcome.daily && won && outcome.dailyCoins > 0 -> s.dailyDone
            won && r.isBoss -> s.bossDefeated
            won && r.endedEarly -> s.missionComplete
            won -> s.stageClear
            r.failReason == FailReason.NO_LIVES -> s.outOfLives
            else -> s.stageFailed
        }
        ui.fitSize(tp, title, safe.width() - 24f * u)
        ui.neon.glowText(c, title, width / 2f, safe.top + 64f * u, tp, titleColor, 16f * u)
        if (arena) {
            // The Arena has no stars: this week's best instead.
            val bp = ui.style(ui.displayPaint, 20f, Palette.GOLD, Paint.Align.CENTER)
            val text = "${s.bestLabel} ${s.num(outcome.arenaWeekBest)}"
            ui.fitSize(bp, text, safe.width() - 24f * u)
            ui.neon.glowText(c, text, width / 2f, starY() + bp.textSize * 0.36f, bp, Palette.ORANGE, 10f * u)
        } else {
            for (i in 0 until 3) {
                val on = i < starsPlayed
                val appear = if (on) Ease.outBack(((age - STARS_AT - i * STAR_GAP) / 0.3f).coerceIn(0f, 1f), 2.5f) else 1f
                val size = starSize * (if (on) appear else 1f)
                if (on) ui.neon.glowBlob(c, starX(i), starY(), size * 1.3f, Palette.GOLD, 0.35f)
                ui.icons.draw(c, if (on) Icon.STAR else Icon.STAR_OUTLINE, starX(i), starY(), size, if (on) Palette.GOLD else Palette.MUTED)
            }
            // What the next star asks for (or how errors cost time).
            if (starHint.isNotEmpty() && !outcome.newRecord && age >= STARS_AT + 3 * STAR_GAP) {
                val hp = ui.style(ui.mediumPaint, 13f, Palette.withAlpha(Palette.TEXT, ((age - STARS_AT - 3 * STAR_GAP) / 0.3f).coerceIn(0f, 1f)), Paint.Align.CENTER)
                ui.fitText(c, starHint, width / 2f, recordY + 4f * u, hp, safe.width() - 24f * u)
            }
        }
        // Stats panel.
        ui.neon.panel(c, panel, 18f * u, Palette.withAlpha(Palette.PANEL, 0.92f), Palette.withAlpha(titleColor, 0.7f), 0.6f)
        for ((i, row) in rows.withIndex()) {
            val appear = ((age - ROWS_AT - i * ROW_GAP) / 0.25f).coerceIn(0f, 1f)
            if (appear <= 0f) continue
            val y = rowsTop + (i + 0.7f) * rowH
            val slide = (1f - Ease.outCubic(appear)) * 30f * u
            val labelP = ui.style(ui.textPaint, labelSize, Palette.withAlpha(Palette.DIM, appear), Paint.Align.LEFT)
            val valueP = ui.style(ui.displayPaint, valueSize, Palette.withAlpha(row.color, appear), Paint.Align.RIGHT)
            c.drawText(row.label, panel.left + 18f * u - slide, y, labelP)
            val counting = row.countsUp && age < ROWS_AT + i * ROW_GAP + 0.8f
            val text = if (counting) {
                s.num((r.score * Ease.outCubic(((age - ROWS_AT - i * ROW_GAP) / 0.8f).coerceIn(0f, 1f))).toLong())
            } else {
                row.value
            }
            c.drawText(text, panel.right - 18f * u + slide, y, valueP)
            if (i == 0 && outcome.newRecord && age >= revealEnd) {
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
        if (outcome.newRecord && age >= revealEnd) {
            val k = ((age - revealEnd) / 0.45f).coerceIn(0f, 1f)
            val text = if (arena) s.arenaRecord else s.newRecord
            val bp = ui.style(ui.displayPaint, 28f, Palette.GOLD, Paint.Align.CENTER)
            ui.fitSize(bp, text, safe.width() - 24f * u)
            bp.textSize *= 0.5f + 0.5f * Ease.outBack(k, 2.5f)
            ui.neon.glowText(c, text, width / 2f, recordY, bp, Palette.GOLD, 18f * u)
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
        /** Below this many players a weekly position reads better than a percentage. */
        private const val SMALL_BOARD = 20L
    }
}
