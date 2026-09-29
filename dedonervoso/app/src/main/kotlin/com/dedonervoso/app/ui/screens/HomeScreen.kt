package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.core.util.Ease
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.NumText
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals

/**
 * Home (spec §30): level + coins on top, animated DEDO NERVOSO logo, the selected stage, a big
 * JOGAR button, shortcuts, missions/daily and the developer's LinkedIn link.
 */
class HomeScreen(app: GameApp) : Screen(app) {
    private val levelRect = RectF()
    private val coinRect = RectF()
    private val cardRect = RectF()
    private var logoY = 0f
    private var updatePill: Button? = null
    private var logoSize = 0f
    private var shownCoins = -1f
    private val coinText = NumText { app.strings.num(it) }
    private val levelText = NumText { it.toString() }
    private val stageText = NumText { "${app.strings.stage} $it" }
    private lateinit var play: Button
    private lateinit var arena: Button
    private lateinit var prevStage: Button
    private lateinit var nextStage: Button
    private lateinit var shop: Button
    private lateinit var missions: Button
    private lateinit var daily: Button
    private var duel: Button? = null

    override fun onEnter() {
        age = 0f
        app.keepScreenOn(false)
        app.progression.ensureMissions()
    }

    override fun onBackgroundUpdate() = relayout()

    override fun layout() {
        val w = width
        val top = safe.top
        val bottom = safe.bottom
        val side = safe.left + 6f * u
        val right = safe.right - 6f * u

        // Top bar.
        levelRect.set(side, top, side + 46f * u, top + 46f * u)
        button("", null, Button.Style.GHOST) { app.host.push(ProfileScreen(app)) }.apply {
            rect.set(side, top, side + 170f * u, top + 46f * u)
            sound = true
        }
        val gear = button("", Icon.GEAR, Button.Style.ICON, Palette.CYAN) { app.host.push(SettingsScreen(app)) }
        gear.rect.set(right - 44f * u, top + 1f * u, right, top + 45f * u)
        coinRect.set(gear.rect.left - 12f * u - 118f * u, top + 7f * u, gear.rect.left - 12f * u, top + 39f * u)
        button("", null, Button.Style.GHOST) { app.host.push(ShopScreen(app)) }.rect.set(coinRect)

        // Bottom-up: developer link, secondary row, shortcut tiles, play button, stage card.
        val linkH = 46f * u
        val link = button(s.developer, Icon.LINKEDIN, Button.Style.LINK, Palette.BLUE) { app.openDeveloperLink() }
        link.sublabel = "linkedin.com/in/guilhermekawe"
        val linkW = minOf(w - 48f * u, 300f * u)
        link.rect.set(w / 2f - linkW / 2f, bottom - linkH, w / 2f + linkW / 2f, bottom)

        val rowH = 50f * u
        val rowTop = link.rect.top - 14f * u - rowH
        val gap = 10f * u
        // Live duels join the row (between missions and daily) once they can be played.
        val slots = if (app.duel.available) 3 else 2
        val slotW = (right - side - gap * (slots - 1)) / slots
        // Three to a row, missions and daily give their icons' room to their (longer) labels.
        val rowIcons = slots == 2
        missions = button(s.missions, Icon.LIST.takeIf { rowIcons }, Button.Style.SECONDARY, Palette.GREEN) { app.host.push(MissionsScreen(app)) }
        missions.rect.set(side, rowTop, side + slotW, rowTop + rowH)
        duel = if (slots == 3) {
            button(s.duel, Icon.BOLT, Button.Style.SECONDARY, Palette.MAGENTA) { app.host.push(DuelLobbyScreen(app)) }.apply {
                rect.set(side + slotW + gap, rowTop, side + 2 * slotW + gap, rowTop + rowH)
            }
        } else {
            null
        }
        daily = button(s.daily, Icon.CALENDAR.takeIf { rowIcons }, Button.Style.SECONDARY, Palette.GOLD) { app.host.push(MissionsScreen(app, focusDaily = true)) }
        daily.rect.set(right - slotW, rowTop, right, rowTop + rowH)

        val tileH = 74f * u
        val tileTop = rowTop - 12f * u - tileH
        val tileW = (right - side - gap * 3) / 4f
        val tiles = listOf(
            Triple(s.upgrades, Icon.UP, Palette.CYAN) to { app.host.push(ShopScreen(app)) },
            Triple(s.ranking, Icon.PODIUM, Palette.MAGENTA) to { app.host.push(RankingScreen(app)) },
            Triple(s.achievements, Icon.MEDAL, Palette.GOLD) to { app.host.push(AchievementsScreen(app)) },
            Triple(s.profile, Icon.USER, Palette.GREEN) to { app.host.push(ProfileScreen(app)) },
        )
        tiles.forEachIndexed { i, (spec, action) ->
            val b = button(spec.first, spec.second, Button.Style.TILE, spec.third) { action() }
            val l = side + i * (tileW + gap)
            b.rect.set(l, tileTop, l + tileW, tileTop + tileH)
            if (i == 0) shop = b
        }

        // JOGAR, and the weekly ARENA beside it (locked until the first boss falls).
        val playH = 66f * u
        val playTop = tileTop - 18f * u - playH
        val rowW = minOf(right - side, 400f * u)
        val rowLeft = w / 2f - rowW / 2f
        val arenaW = rowW * 0.36f
        play = button(s.play, null, Button.Style.PRIMARY, Palette.CYAN) { startSelected() }
        play.rect.set(rowLeft, playTop, rowLeft + rowW - arenaW - gap, playTop + playH)
        play.pulse = true
        val unlocked = app.progression.arenaUnlocked
        arena = button(s.arena, if (unlocked) Icon.TROPHY else Icon.LOCK, Button.Style.SECONDARY, if (unlocked) Palette.GOLD else Palette.MUTED) {
            if (app.progression.arenaUnlocked) {
                app.host.push(PlayScreen.arena(app))
            } else {
                app.host.toast(s.arena, s.arenaLocked(com.dedonervoso.core.stage.StageCatalog.BOSS_EVERY), Icon.LOCK, Palette.ORANGE)
            }
        }.apply {
            rect.set(play.rect.right + gap, playTop, rowLeft + rowW, playTop + playH)
            sublabel = if (unlocked) s.arenaWeek else null
        }

        val cardH = 96f * u
        val cardTop = playTop - 16f * u - cardH
        cardRect.set(side, cardTop, right, cardTop + cardH)
        prevStage = button("", Icon.BACK, Button.Style.ICON, Palette.DIM) { changeStage(-1) }
        prevStage.rect.set(cardRect.left + 8f * u, cardRect.centerY() - 17f * u, cardRect.left + 42f * u, cardRect.centerY() + 17f * u)
        nextStage = button("", Icon.NEXT, Button.Style.ICON, Palette.DIM) { changeStage(+1) }
        nextStage.rect.set(cardRect.right - 42f * u, cardRect.centerY() - 17f * u, cardRect.right - 8f * u, cardRect.centerY() + 17f * u)
        // Tapping the card body opens the stage map.
        button("", null, Button.Style.GHOST) { app.host.push(StageSelectScreen(app)) }.rect.set(
            prevStage.rect.right + 4f * u, cardRect.top, nextStage.rect.left - 4f * u, cardRect.bottom,
        )

        // A new version is announced right above the stage card; the logo gives up the room.
        var logoBottom = cardRect.top - 10f * u
        updatePill = null
        val release = app.updates.release
        if (app.updates.hasUpdate && release != null) {
            val required = app.updates.state == UpdateState.REQUIRED_FOR_ONLINE
            val pillH = 38f * u
            val pillW = minOf(w - 60f * u, 290f * u)
            updatePill = button(s.updateAvailable(release.versionName), Icon.DOWNLOAD, Button.Style.SECONDARY, if (required) Palette.ORANGE else Palette.GREEN) {
                app.showUpdate()
            }.apply {
                rect.set(w / 2f - pillW / 2f, logoBottom - pillH, w / 2f + pillW / 2f, logoBottom)
                pulse = true
            }
            logoBottom -= pillH + 6f * u
        }
        // Logo takes the remaining space between the top bar and the card.
        val logoTop = top + 56f * u
        logoSize = minOf((logoBottom - logoTop) * 0.36f, w * 0.2f, 84f * u)
        logoY = (logoTop + logoBottom) / 2f
        refreshBadges()
    }

    private fun refreshBadges() {
        val p = app.progression
        val claim = p.claimableMissions
        missions.badge = if (claim > 0) claim.toString() else null
        daily.badge = if (p.dailyRewardAvailable) "!" else null
        duel?.badge = app.duel.invites.size.takeIf { it > 0 }?.toString()
        shop.badge = if (p.affordableUpgrade() != null) "!" else null
        val sel = p.save.selectedStage
        prevStage.enabled = sel > 1
        nextStage.enabled = sel < p.save.highestUnlocked
    }

    private fun changeStage(delta: Int) {
        app.progression.selectStage(app.progression.save.selectedStage + delta)
        refreshBadges()
    }

    private fun startSelected() {
        app.host.push(PlayScreen.forStage(app, app.progression.save.selectedStage))
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        val coins = app.progression.save.coins.toFloat()
        shownCoins = if (shownCoins < 0f) coins else shownCoins + (coins - shownCoins) * minOf(1f, dt * 6f)
        if (kotlin.math.abs(coins - shownCoins) < 0.5f) shownCoins = coins
        ui.background.update(dt)
        ui.particles.update(dt)
        ui.rings.update(dt)
        // Logo "impact": a shockwave every couple of seconds.
        if (((age - 0.6f) % 2.4f) < dt && age > 0.6f) {
            ui.rings.add(width / 2f, logoY, logoSize * 0.6f / ui.density, logoSize * 2.6f / ui.density, Palette.CYAN, 1.1f, 3f)
        }
    }

    private fun appear(delay: Float): Float = Ease.outCubic(((age - delay) / 0.45f).coerceIn(0f, 1f))

    override fun draw(c: Canvas) {
        val hero = ui.art.hero
        if (hero != null) drawHero(c, hero) else drawBackdrop(c, gridAlpha = 0.9f, horizon = 0.66f)
        ui.rings.draw(c)
        if (hero == null) drawLogo(c)
        drawTopBar(c)
        drawStageCard(c)
        c.save()
        val a = appear(0.25f)
        c.translate(0f, (1f - a) * 40f * u)
        drawButtons(c)
        c.restore()
        ui.particles.draw(c)
    }

    /**
     * The mascot fills the top of Home, breathing slowly; it fades into the dark behind the menu.
     * Touching it throws sparks (see [onTouch]).
     */
    private fun drawHero(c: Canvas, hero: android.graphics.Bitmap) {
        c.drawColor(0xFF050208.toInt())
        val breathe = if (app.settings.reduceEffects) 0f else 0.012f * kotlin.math.sin(ui.time * 0.9f)
        val a = appear(0f)
        ui.art.drawCover(c, hero, width, cardRect.top + 40f * u, alignY = 0.28f, zoom = 1.02f + breathe, focusX = 0.5f, focusY = 0.35f, alpha = a)
        if (fade == null || fadeTop != cardRect.top) {
            fadeTop = cardRect.top
            fade = android.graphics.LinearGradient(
                0f, cardRect.top - 150f * u, 0f, cardRect.top + 40f * u, 0x00050208, 0xFF050208.toInt(), android.graphics.Shader.TileMode.CLAMP,
            )
            fadePaint.shader = fade
        }
        c.drawRect(0f, cardRect.top - 150f * u, width, height, fadePaint)
        // A soft top shade keeps the level and coins readable.
        topShade.color = 0x99050208.toInt()
        c.drawRect(0f, 0f, width, safe.top + 56f * u, topShade)
    }

    private var fade: android.graphics.LinearGradient? = null
    private var fadeTop = 0f
    private val fadePaint = android.graphics.Paint()
    private val topShade = android.graphics.Paint()

    override fun onTouch(e: android.view.MotionEvent): Boolean {
        // Tapping the mascot: sparks and a tap sound, just for fun.
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN && ui.art.hero != null &&
            e.y > levelRect.bottom + 8f * u && e.y < cardRect.top - 8f * u && buttons.none { it.visible && it.contains(e.x, e.y) }
        ) {
            ui.particles.burst(e.x, e.y, 18, Palette.S_GOLD, 120f, 480f, 3f, 7f, 0.6f, com.dedonervoso.app.ui.fx.Particles.SPARK)
            ui.rings.add(e.x, e.y, 6f, 70f, Palette.ORANGE, 0.4f, 3f)
            app.sfx.play(com.dedonervoso.core.audio.Sfx.TAP_ZONE, 0.7f)
            app.haptics.tap()
        }
        return super.onTouch(e)
    }

    private fun drawLogo(c: Canvas) {
        val a = appear(0f)
        val slam = Ease.outBack(a, 2.2f)
        val cx = width / 2f
        val size = logoSize * 0.95f * (1.25f - 0.25f * slam)
        ui.neon.glowBlob(c, cx, logoY + logoSize * 0.3f, logoSize * 3.2f, Palette.PURPLE, 0.3f * a)
        val shake = if (app.settings.reduceEffects) 0f else 1f
        val y2 = ui.brand.wordmark(c, cx, logoY - size * 0.06f, size, width * 0.9f, a, ui.time, shake)
        if (updatePill != null) return
        val tp = ui.style(ui.textPaint, 14f, Palette.withAlpha(Palette.DIM, a), Paint.Align.CENTER)
        tp.letterSpacing = 0.25f
        c.drawText(s.tagline.uppercase(), cx, y2 + logoSize * 0.55f, tp)
        tp.letterSpacing = 0.03f
    }

    private fun drawTopBar(c: Canvas) {
        val p = app.progression
        val info = p.levelInfo
        val cx = levelRect.centerX()
        val cy = levelRect.centerY()
        val r = levelRect.width() / 2f
        val avatarColor = Visuals.AVATAR_COLORS[p.save.profile.avatar]
        ui.neon.circle(c, cx, cy, r, Palette.PANEL)
        ui.neon.ring(c, cx, cy, r - 2f * u, info.fraction, avatarColor, 3.5f * u, Palette.withAlpha(Palette.WHITE, 0.12f))
        val lp = ui.style(ui.displayPaint, 17f, Palette.WHITE, Paint.Align.CENTER)
        c.drawText(levelText.of(info.level), cx, cy + lp.textSize * 0.36f, lp)
        val np = ui.style(ui.textPaint, 16f, Palette.WHITE, Paint.Align.LEFT)
        ui.fitText(c, p.save.profile.nickname, levelRect.right + 10f * u, cy - 2f * u, np, coinRect.left - levelRect.right - 20f * u)
        val sp = ui.style(ui.mediumPaint, 12f, Palette.DIM, Paint.Align.LEFT)
        c.drawText("${s.level} ${info.level}", levelRect.right + 10f * u, cy + 14f * u, sp)

        ui.neon.panel(c, coinRect, coinRect.height() / 2f, Palette.PANEL, Palette.withAlpha(Palette.GOLD, 0.8f), 0.5f)
        ui.icons.draw(c, Icon.COIN, coinRect.left + coinRect.height() / 2f + 2f * u, coinRect.centerY(), coinRect.height() * 0.62f, Palette.GOLD)
        val cp = ui.style(ui.displayBoldPaint, 15f, Palette.GOLD, Paint.Align.RIGHT)
        c.drawText(coinText.of(shownCoins.toLong()), coinRect.right - 12f * u, coinRect.centerY() + cp.textSize * 0.36f, cp)
    }

    private fun drawStageCard(c: Canvas) {
        val a = appear(0.15f)
        val p = app.progression
        val n = p.save.selectedStage
        val stage = p.stage(n)
        val color = Visuals.typeColor(stage.type)
        c.save()
        c.translate(0f, (1f - a) * 30f * u)
        ui.neon.panel(c, cardRect, 18f * u, Palette.withAlpha(Palette.PANEL, 0.9f * a), Palette.withAlpha(color, 0.85f * a), 0.8f)
        val left = prevStage.rect.right + 10f * u
        val maxW = nextStage.rect.left - left - 10f * u
        val title = ui.style(ui.displayPaint, 22f, Palette.withAlpha(Palette.WHITE, a), Paint.Align.LEFT)
        c.drawText(stageText.of(n), left, cardRect.top + 34f * u, title)
        val titleW = title.measureText(stageText.of(n))
        // Type chip.
        val chipP = ui.style(ui.textPaint, 12f, Palette.withAlpha(color, a), Paint.Align.LEFT)
        val chipText = if (stage.isBoss) s.boss else s.typeName(stage.type)
        val chipX = left + titleW + 10f * u
        ui.icons.draw(c, Visuals.typeIcon(stage.type), chipX + 8f * u, cardRect.top + 28f * u, 16f * u, Palette.withAlpha(color, a))
        c.drawText(chipText, chipX + 19f * u, cardRect.top + 33f * u, chipP)
        val op = ui.style(ui.semiPaint, 15f, Palette.withAlpha(Palette.TEXT, a), Paint.Align.LEFT)
        ui.fitText(c, s.objectiveText(stage), left, cardRect.top + 60f * u, op, maxW)
        // Stars + best.
        val stars = p.save.stageStars[n] ?: 0
        for (i in 0 until 3) {
            ui.icons.draw(c, if (i < stars) Icon.STAR else Icon.STAR_OUTLINE, left + 9f * u + i * 20f * u, cardRect.top + 80f * u, 17f * u,
                Palette.withAlpha(if (i < stars) Palette.GOLD else Palette.MUTED, a))
        }
        // The stage's record is its best time.
        val best = p.save.stageBestTime[n]
        if (best != null && best > 0) {
            val bp = ui.style(ui.mediumPaint, 12f, Palette.withAlpha(Palette.DIM, a), Paint.Align.RIGHT)
            c.drawText("${s.bestLabel} ${s.seconds(best)}", nextStage.rect.left - 8f * u, cardRect.top + 85f * u, bp)
        }
        c.restore()
    }

    override fun onAppResume() {
        refreshBadges()
    }
}
