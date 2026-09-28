package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.progression.BuyCheck
import com.dedonervoso.core.progression.UpgradeDef
import com.dedonervoso.core.progression.Upgrades
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.NumText
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScrollArea
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import kotlin.math.sin

/** Upgrade shop (spec §17): permanent upgrades with rising prices; buying is one tap. */
class ShopScreen(app: GameApp) : Screen(app) {
    private lateinit var scroll: ScrollArea
    private var savedOffset = 0f
    private val cards = ArrayList<Pair<UpgradeDef, RectF>>()
    private val buyButtons = HashMap<UpgradeDef, Button>()
    private val coinRect = RectF()
    private var shownCoins = -1f
    private val coinText = NumText { app.strings.num(it) }
    private val flashAge = HashMap<UpgradeDef, Float>()
    private val shakeAge = HashMap<UpgradeDef, Float>()

    override fun layout() {
        val top = header(s.upgrades, rightReserve = 130f * u)
        coinRect.set(safe.right - 120f * u, safe.top + 6f * u, safe.right, safe.top + 38f * u)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        cards.clear()
        buyButtons.clear()
        val cardH = 104f * u
        val gap = 12f * u
        var y = scroll.rect.top + 4f * u
        for (def in Upgrades.ALL) {
            val r = RectF(scroll.rect.left + 4f * u, y, scroll.rect.right - 4f * u, y + cardH)
            cards += def to r
            val b = button("", null, Button.Style.SECONDARY, Palette.GOLD) { buy(def) }
            b.scroll = scroll
            b.sound = false
            b.rect.set(r.right - 108f * u, r.bottom - 50f * u, r.right - 12f * u, r.bottom - 12f * u)
            buyButtons[def] = b
            y += cardH + gap
        }
        scroll.contentHeight = y - scroll.rect.top + 16f * u
        scroll.scrollTo(savedOffset)
        refresh()
    }

    private fun refresh() {
        val p = app.progression
        for ((def, b) in buyButtons) {
            val check = p.check(def.id)
            b.visible = check != BuyCheck.MAXED && check != BuyCheck.LOCKED_STAGE && check != BuyCheck.LOCKED_LEVEL && check != BuyCheck.REQUIRES_OTHER
            b.accent = if (check == BuyCheck.OK) Palette.GOLD else Palette.MUTED
            val cost = Upgrades.nextCost(def.id, p.upgradeLevel(def.id))
            b.label = if (cost != null) s.num(cost) else ""
        }
    }

    private fun buy(def: UpgradeDef) {
        val card = cards.first { it.first == def }.second
        when (app.progression.buy(def.id)) {
            BuyCheck.OK -> {
                app.sfx.play(Sfx.BUY)
                app.haptics.celebrate()
                flashAge[def] = 0f
                val cx = card.centerX()
                val cy = card.centerY() - scroll.offset
                ui.particles.burst(cx, cy, 40, Palette.S_GOLD, 120f, 420f, 3f, 8f, 0.7f)
                ui.rings.add(cx, cy, 20f, 180f, Visuals.upgradeColor(def.id), 0.6f, 5f)
                app.announce(app.progression.unlockAchievements())
            }
            BuyCheck.NOT_ENOUGH_COINS -> {
                app.sfx.play(Sfx.FAULT, 0.6f)
                app.haptics.error()
                shakeAge[def] = 0f
            }
            else -> Unit
        }
        refresh()
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        savedOffset = scroll.offset
        ui.background.update(dt * 0.5f)
        ui.particles.update(dt)
        ui.rings.update(dt)
        val coins = app.progression.save.coins.toFloat()
        shownCoins = if (shownCoins < 0f) coins else shownCoins + (coins - shownCoins) * kotlin.math.min(1f, dt * 8f)
        if (kotlin.math.abs(coins - shownCoins) < 0.5f) shownCoins = coins
        for (k in flashAge.keys.toList()) flashAge[k] = flashAge[k]!! + dt
        for (k in shakeAge.keys.toList()) shakeAge[k] = shakeAge[k]!! + dt
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        ui.neon.panel(c, coinRect, coinRect.height() / 2f, Palette.PANEL, Palette.GOLD, 0.6f)
        ui.icons.draw(c, Icon.COIN, coinRect.left + coinRect.height() / 2f + 2f * u, coinRect.centerY(), coinRect.height() * 0.62f, Palette.GOLD)
        val cp = ui.style(ui.displayBoldPaint, 15f, Palette.GOLD, Paint.Align.RIGHT)
        c.drawText(coinText.of(shownCoins.toLong()), coinRect.right - 12f * u, coinRect.centerY() + cp.textSize * 0.36f, cp)

        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val p = app.progression
        for ((def, r) in cards) {
            val color = Visuals.upgradeColor(def.id)
            val level = p.upgradeLevel(def.id)
            val check = p.check(def.id)
            val locked = check == BuyCheck.LOCKED_STAGE || check == BuyCheck.LOCKED_LEVEL || check == BuyCheck.REQUIRES_OTHER
            val shake = shakeAge[def]?.let { if (it < 0.35f) sin(it * 70f) * 6f * u * (1f - it / 0.35f) else 0f } ?: 0f
            val flash = flashAge[def]?.let { if (it < 0.6f) 1f - it / 0.6f else 0f } ?: 0f
            c.save()
            c.translate(shake, 0f)
            ui.neon.panel(c, r, 18f * u, Palette.withAlpha(Palette.mix(Palette.PANEL, color, 0.06f + 0.3f * flash), 0.94f),
                Palette.withAlpha(if (locked) Palette.MUTED else color, 0.75f), 0.4f + flash)
            val iconX = r.left + 38f * u
            val iconY = r.top + 38f * u
            ui.neon.circle(c, iconX, iconY, 24f * u, Palette.withAlpha(color, if (locked) 0.08f else 0.18f))
            ui.icons.draw(c, if (locked) Icon.LOCK else Visuals.upgradeIcon(def.id), iconX, iconY, 26f * u, if (locked) Palette.MUTED else color)
            val tx = r.left + 74f * u
            val np = ui.style(ui.displayBoldPaint, 15f, if (locked) Palette.DIM else Palette.WHITE, Paint.Align.LEFT)
            ui.fitText(c, s.upgradeName(def.id), tx, r.top + 28f * u, np, r.right - tx - 120f * u)
            // Level pips.
            for (i in 0 until def.maxLevel) {
                val px = tx + 6f * u + i * 16f * u
                if (i < level) ui.neon.circle(c, px, r.top + 44f * u, 5f * u, color)
                else ui.neon.circleStroke(c, px, r.top + 44f * u, 4.5f * u, Palette.MUTED, 1.5f * u)
            }
            val dp = ui.style(ui.semiPaint, 13.5f, Palette.TEXT, Paint.Align.LEFT)
            ui.fitText(c, s.upgradeDescription(def.id), r.left + 18f * u, r.bottom - 42f * u, dp, r.width() - 140f * u)
            val sp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.LEFT)
            val status = when (check) {
                BuyCheck.MAXED -> s.upgradeLevel(level, def.maxLevel)
                BuyCheck.LOCKED_STAGE -> s.unlocksAtStage(def.unlockStage)
                BuyCheck.LOCKED_LEVEL -> s.requiresLevel(def.requiresLevel)
                BuyCheck.REQUIRES_OTHER -> s.requiresUpgrade(s.upgradeName(def.requires!!))
                else -> s.upgradeLevel(level, def.maxLevel)
            }
            ui.fitText(c, status, r.left + 18f * u, r.bottom - 20f * u, sp, r.width() - 140f * u)
            if (check == BuyCheck.MAXED) {
                val mp = ui.style(ui.displayPaint, 18f, color, Paint.Align.CENTER)
                ui.neon.glowText(c, s.max, r.right - 60f * u, r.bottom - 24f * u, mp, color, 8f * u)
            }
            c.restore()
            val b = buyButtons[def]!!
            if (b.visible) drawBuyButton(c, b, check == BuyCheck.OK)
        }
        c.restore()
        drawButtons(c)
        ui.rings.draw(c)
        ui.particles.draw(c)
    }

    private fun drawBuyButton(c: Canvas, b: Button, affordable: Boolean) {
        val r = b.rect
        val press = b.press
        val color = if (affordable) Palette.GOLD else Palette.MUTED
        ui.neon.panel(c, r, r.height() / 2f, Palette.withAlpha(Palette.mix(Palette.PANEL, color, 0.15f + 0.25f * press), 0.95f), color, if (affordable) 0.8f else 0.2f)
        ui.icons.draw(c, Icon.COIN, r.left + r.height() / 2f + 2f * u, r.centerY(), r.height() * 0.5f, color)
        val p = ui.style(ui.displayBoldPaint, 14f, if (affordable) Palette.WHITE else Palette.DIM, Paint.Align.CENTER)
        c.drawText(b.label, r.centerX() + 10f * u, r.centerY() + p.textSize * 0.36f, p)
    }
}
