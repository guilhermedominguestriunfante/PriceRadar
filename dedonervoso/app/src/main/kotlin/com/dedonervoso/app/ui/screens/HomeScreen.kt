package com.dedonervoso.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Online
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.NumText
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.app.ui.fx.Particles
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.engine.BossKind
import com.dedonervoso.core.online.OnlineBoard
import com.dedonervoso.core.online.OnlineEntry
import com.dedonervoso.core.online.OnlineMetric
import com.dedonervoso.core.online.OnlineService
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.core.progression.Achievements
import com.dedonervoso.core.progression.DailyChallenge
import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.util.Ease
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Home, the lobby (1.3.1): the mascot fills the screen and every control hugs an edge.
 *
 *  - top: avatar in a gold octagon with the level, name and XP bar, coins with "+" (shop), and
 *    small buttons for duel invites and settings;
 *  - left: live cards (today's challenge, the next boss with its illustration), shop and
 *    achievements;
 *  - right: a drawer of friends with a challenge button each;
 *  - bottom: the mission closest to its reward, the stage selector (stars, best time), the ARENA
 *    emblem, the big gold JOGAR, and the menu bar.
 *
 * Everything is sized in lobby units ([k]): a hundredth of the width, or less on short screens.
 * The pieces are drawn here; their touch areas are [Button.Style.HIDDEN] buttons.
 */
class HomeScreen(app: GameApp) : Screen(app) {
    /** Lobby unit: 1/100 of the width, capped so the whole lobby fits short screens. */
    private var k = 0f
    private var navTop = 0f

    private val avatarR = RectF()
    private val coinR = RectF()
    private val mailR = RectF()
    private val gearR = RectF()
    private val dailyR = RectF()
    private val bossR = RectF()
    private val shopR = RectF()
    private val medalR = RectF()
    private val drawerR = RectF()
    private val tabR = RectF()
    private val missionR = RectF()
    private val stageR = RectF()
    private val arenaR = RectF()
    private val playR = RectF()
    private val navR = RectF()
    private val tmp = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private class NavItem(val label: String, val icon: Icon, val rect: RectF, var badge: String?)
    private val nav = ArrayList<NavItem>()

    private var mail: Button? = null
    private lateinit var play: Button

    // Texts built on enter/relayout (never per frame).
    private val coinText = NumText { app.strings.num(it) }
    private var shownCoins = -1f
    private var xpText = ""
    private var dailyTitle = ""
    private var dailySub = ""
    private var dailyDone = false
    private var lastCountdownMinute = -1L
    private var nextBoss = StageCatalog.BOSS_EVERY
    private var nextBossKind = BossKind.FURIOSO
    private var bossName = ""
    private var bossSub = ""
    private var achievementsText = ""
    private var missionText = ""
    private var missionReward = ""
    private var missionFraction = 0f
    private var missionReady = false
    private var hasMission = false
    private var stageTitle = ""
    private var stageType = ""
    private var stageGoal = ""
    private var stageBest = ""
    private var playSub = ""
    private var arenaSub = ""
    private var friendRows: List<FriendRow> = emptyList()
    private var drawerT = if (drawerOpen) 1f else 0f
    private var emberClock = 0f

    private class FriendRow(val entry: OnlineEntry, val detail: String, val color: Int, val icon: Icon)

    override fun onEnter() {
        age = 0f
        app.keepScreenOn(false)
        app.progression.ensureMissions()
        loadFriends()
    }

    override fun onBackgroundUpdate() {
        loadFriends()
        relayout()
    }

    override fun onAppResume() = relayout()

    /** Friends for the drawer, when online; cached for a minute across visits. */
    private fun loadFriends() {
        if (app.online.status != Online.Status.READY) return
        if (friendsCache != null && System.currentTimeMillis() - friendsAt < FRIENDS_TTL_MS) return
        friendsAt = System.currentTimeMillis()
        app.online.leaderboard(OnlineBoard.FRIENDS, OnlineMetric.SCORE) { list, _ ->
            if (list != null) {
                friendsCache = list.filter { !it.isMe }
                relayout()
            }
        }
    }

    // ============================================================================================
    // Layout
    // ============================================================================================

    override fun layout() {
        val w = width
        k = min(w / 100f, safe.height() / 190f)
        val left = safe.left
        val right = safe.right
        val top = safe.top
        val p = app.progression
        buildTexts()

        // ---- top bar
        avatarR.set(left, top, left + 15f * k, top + 15f * k)
        button("", null, Button.Style.HIDDEN) { app.host.push(ProfileScreen(app)) }.rect.set(left, top, left + 55f * k, top + 16f * k)
        coinR.set(right - 27f * k, top + 3.5f * k, right, top + 11.5f * k)
        button("", null, Button.Style.HIDDEN) { app.host.push(ShopScreen(app)) }.rect.set(coinR)
        gearR.set(right - 10f * k, top + 18f * k, right, top + 28f * k)
        button("", Icon.GEAR, Button.Style.HIDDEN) { app.host.push(SettingsScreen(app)) }.rect.set(gearR)
        mail = null
        if (app.duel.available) {
            mailR.set(gearR.left - 12f * k, gearR.top, gearR.left - 2f * k, gearR.bottom)
            mail = button("", Icon.MAIL, Button.Style.HIDDEN) { app.host.push(DuelLobbyScreen(app)) }.apply { rect.set(mailR) }
        }

        // A new version is announced under the top bar.
        val release = app.updates.release
        if (app.updates.hasUpdate && release != null) {
            val required = app.updates.state == UpdateState.REQUIRED_FOR_ONLINE
            val pw = min(right - left - 26f * k, 62f * k)
            button(s.updateAvailable(release.versionName), Icon.DOWNLOAD, Button.Style.SECONDARY, if (required) Palette.ORANGE else Palette.GREEN) {
                app.showUpdate()
            }.apply {
                rect.set(left, top + 18.5f * k, left + pw, top + 27.5f * k)
                pulse = true
            }
        }

        // ---- left rail
        val railTop = top + 31f * k
        dailyR.set(left, railTop, left + 33f * k, railTop + 17f * k)
        button(s.daily, null, Button.Style.HIDDEN) { app.host.push(MissionsScreen(app, focusDaily = true)) }.rect.set(dailyR)
        bossR.set(left, dailyR.bottom + 2.4f * k, dailyR.right, dailyR.bottom + 19.4f * k)
        button("", null, Button.Style.HIDDEN) { openNextBoss() }.rect.set(bossR)
        shopR.set(left + 1f * k, bossR.bottom + 3f * k, left + 12f * k, bossR.bottom + 14f * k)
        button(s.shop, Icon.BAG, Button.Style.HIDDEN) { app.host.push(ShopScreen(app)) }.rect.set(shopR.left, shopR.top, shopR.right, shopR.bottom + 5f * k)
        medalR.set(shopR.right + 6f * k, shopR.top, shopR.right + 17f * k, shopR.bottom)
        button(s.achievements, Icon.MEDAL, Button.Style.HIDDEN) { app.host.push(AchievementsScreen(app)) }.rect.set(medalR.left, medalR.top, medalR.right, medalR.bottom + 5f * k)

        // ---- bottom, from the bottom up: menu bar, play row, stage selector, mission
        navTop = safe.bottom - 14f * k
        navR.set(0f, navTop, w, height)
        nav.clear()
        val items = listOf<Triple<String, Icon, () -> Unit>>(
            Triple(s.upgrades, Icon.UP) { app.host.push(ShopScreen(app)) },
            Triple(s.duel, Icon.BOLT) { openDuels() },
            Triple(s.missions, Icon.LIST) { app.host.push(MissionsScreen(app)) },
            Triple(s.ranking, Icon.PODIUM) { app.host.push(RankingScreen(app)) },
            Triple(s.profile, Icon.USER) { app.host.push(ProfileScreen(app)) },
        )
        val itemW = (right - left) / items.size
        for ((i, spec) in items.withIndex()) {
            val r = RectF(left + i * itemW, navTop + 1f * k, left + (i + 1) * itemW, safe.bottom)
            nav += NavItem(spec.first, spec.second, r, null)
            button(spec.first, spec.second, Button.Style.HIDDEN) { spec.third() }.rect.set(r)
        }
        playR.set(left + 25f * k, navTop - 25f * k, right, navTop - 4f * k)
        arenaR.set(left, playR.top, left + 21f * k, playR.bottom)
        button(s.arena, null, Button.Style.HIDDEN) { openArena() }.rect.set(arenaR)
        play = button(s.play, null, Button.Style.HIDDEN) { startSelected() }.apply { rect.set(playR) }
        stageR.set(left, playR.top - 21f * k, right, playR.top - 3f * k)
        button("", Icon.BACK, Button.Style.ICON, Palette.GOLD) { changeStage(-1) }.apply {
            rect.set(stageR.left + 2f * k, stageR.centerY() - 4.5f * k, stageR.left + 11f * k, stageR.centerY() + 4.5f * k)
            enabled = p.save.selectedStage > 1
        }
        button("", Icon.NEXT, Button.Style.ICON, Palette.GOLD) { changeStage(+1) }.apply {
            rect.set(stageR.right - 11f * k, stageR.centerY() - 4.5f * k, stageR.right - 2f * k, stageR.centerY() + 4.5f * k)
            enabled = p.save.selectedStage < p.save.highestUnlocked
        }
        missionR.set(right - 60f * k, stageR.top - 16f * k, right - 2f * k, stageR.top - 4f * k)
        if (hasMission) button("", null, Button.Style.HIDDEN) { app.host.push(MissionsScreen(app)) }.rect.set(missionR)

        // ---- right: the friends drawer
        val drawerTop = top + 58f * k
        // Header, one row per friend (or the empty note), the invite row; offline, a call to turn it on.
        val drawerH = if (app.online.enabled) 11f * k + max(1, friendRows.size) * 11f * k + 10f * k else 22f * k
        drawerR.set(w - 44f * k, drawerTop, w, drawerTop + drawerH)
        tabR.set(drawerR.left - 7f * k, drawerTop + drawerH / 2f - 8f * k, drawerR.left, drawerTop + drawerH / 2f + 8f * k)
        layoutDrawer()

        // The stage card body opens the stage map (the lowest unlabeled touch area, added last).
        button("", null, Button.Style.HIDDEN) { app.host.push(StageSelectScreen(app)) }.rect.set(stageR.left + 13f * k, stageR.top, stageR.right - 13f * k, stageR.bottom)
        refreshBadges()
    }

    /** Touch areas of the drawer: the tab always; rows only while it is open. */
    private fun layoutDrawer() {
        val open = drawerOpen
        val dx = if (open) 0f else DRAWER_SLIDE * k
        button("", null, Button.Style.HIDDEN) { toggleDrawer() }.rect.set(tabR.left + dx, tabR.top, tabR.right + dx, tabR.bottom)
        if (!open) return
        val x0 = drawerR.left + 2.5f * k
        var y = drawerR.top + 9f * k
        if (!app.online.enabled) {
            button(s.enable, null, Button.Style.HIDDEN) { app.host.push(RankingScreen(app)) }.rect.set(x0, y + 1f * k, drawerR.right - 3f * k, y + 10f * k)
            return
        }
        for (row in friendRows) {
            button(s.challenge, null, Button.Style.HIDDEN) { challenge(row.entry) }.rect.set(drawerR.right - 11f * k, y + 1f * k, drawerR.right - 2f * k, y + 10f * k)
            y += 11f * k
        }
        if (friendRows.isEmpty()) y += 11f * k
        button(s.invite, null, Button.Style.HIDDEN) { app.shareInvite() }.rect.set(x0, y, drawerR.right - 3f * k, y + 9f * k)
    }

    private fun buildTexts() {
        val p = app.progression
        val info = p.levelInfo
        xpText = "${s.num(info.xpIntoLevel)} / ${s.num(info.xpForNext)} XP"
        // Today's challenge.
        val (template, _) = p.daily()
        dailyTitle = s.dailyTitle(template)
        dailyDone = !p.dailyRewardAvailable
        lastCountdownMinute = -1L
        dailySub = if (dailyDone) s.dailyBackIn(countdown()) else s.dailyReward(DailyChallenge.rewardCoins(p.level, p.save.dailyStreak + 1))
        // The next boss not beaten yet.
        nextBoss = ((p.save.highestUnlocked + StageCatalog.BOSS_EVERY - 1) / StageCatalog.BOSS_EVERY) * StageCatalog.BOSS_EVERY
        nextBossKind = BossKind.of(nextBoss)
        bossName = s.bossName(nextBossKind)
        bossSub = "${s.stage} $nextBoss"
        achievementsText = "${p.save.achievements.size}/${Achievements.ALL.size}"
        // The mission closest to its reward.
        val open = p.save.missions.filter { !it.claimed }
        val m = open.firstOrNull { it.done } ?: open.filter { it.progress > 0 }.maxByOrNull { it.fraction } ?: open.firstOrNull()
        hasMission = m != null
        if (m != null) {
            missionReady = m.done
            missionText = if (m.done) s.missionReady else s.missionText(m.kind, m.target)
            missionReward = "+${s.num(m.rewardCoins)}"
            missionFraction = m.fraction
        }
        // The selected stage.
        val n = p.save.selectedStage
        val stage = p.stage(n)
        stageTitle = "${s.stage} $n"
        stageType = if (stage.isBoss) s.boss else s.typeName(stage.type)
        stageGoal = s.objectiveText(stage)
        stageBest = p.save.stageBestTime[n]?.let { "${s.bestLabel} ${s.seconds(it)}" } ?: ""
        playSub = stageTitle
        // The Arena.
        val week = OnlineService.isoWeek(System.currentTimeMillis())
        val weekBest = if (p.save.arenaWeekId == week) p.save.arenaWeekBest else 0L
        arenaSub = when {
            !p.arenaUnlocked -> "${s.stage} ${StageCatalog.BOSS_EVERY}"
            weekBest > 0 -> s.num(weekBest)
            else -> s.week
        }
        // Friends for the drawer.
        friendRows = (friendsCache ?: emptyList()).take(MAX_FRIENDS).map { e ->
            val a = e.avatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)
            val detail = if (e.score > 0) "${s.arena} ${s.num(e.score)}" else "${s.stage} ${e.stage}"
            FriendRow(e, detail, Visuals.AVATAR_COLORS[a], Visuals.AVATAR_ICONS[a])
        }
    }

    /** Time left until the next local midnight, "HH:MM". */
    private fun countdown(): String {
        val now = System.currentTimeMillis()
        val local = now + TimeZone.getDefault().getOffset(now)
        val left = DAY_MS - Math.floorMod(local, DAY_MS)
        val minutes = (left + 59_999L) / 60_000L
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    }

    private fun refreshBadges() {
        val p = app.progression
        nav[0].badge = if (p.affordableUpgrade() != null) "!" else null
        nav[1].badge = app.duel.invites.size.takeIf { it > 0 }?.toString()
        nav[2].badge = p.claimableMissions.takeIf { it > 0 }?.toString()
    }

    // ============================================================================================
    // Actions
    // ============================================================================================

    private fun changeStage(delta: Int) {
        app.progression.selectStage(app.progression.save.selectedStage + delta)
        relayout()
    }

    private fun startSelected() {
        app.host.push(PlayScreen.forStage(app, app.progression.save.selectedStage))
    }

    private fun openArena() {
        if (app.progression.arenaUnlocked) {
            app.host.push(PlayScreen.arena(app))
        } else {
            app.host.toast(s.arena, s.arenaLocked(StageCatalog.BOSS_EVERY), Icon.LOCK, Palette.ORANGE)
        }
    }

    private fun openDuels() {
        if (app.duel.available) app.host.push(DuelLobbyScreen(app)) else app.host.toast(s.duel, s.duelNeedsOnline, Icon.GLOBE, Palette.ORANGE)
    }

    private fun openNextBoss() {
        val p = app.progression
        if (nextBoss <= p.save.highestUnlocked) {
            p.selectStage(nextBoss)
            relayout()
        } else {
            app.host.toast(s.nextBoss, s.reachStage(nextBoss), Icon.SKULL, Palette.EMBER)
        }
    }

    private fun challenge(friend: OnlineEntry) {
        if (!app.duel.available) {
            app.host.toast(s.duel, s.duelNeedsOnline, Icon.GLOBE, Palette.ORANGE)
            return
        }
        app.host.push(DuelWaitScreen(app, app.duel.challenge(friend.uid, friend.nick, friend.avatar)))
    }

    private fun toggleDrawer() {
        drawerOpen = !drawerOpen
        relayout()
    }

    override fun onTouch(e: MotionEvent): Boolean {
        // Tapping the mascot: sparks and a tap sound, just for fun.
        val drawerLeft = drawerR.left + (1f - drawerT) * DRAWER_SLIDE * k - 8f * k
        if (e.actionMasked == MotionEvent.ACTION_DOWN && ui.art.hero != null && buttons.none { it.visible && it.contains(e.x, e.y) } &&
            e.y > avatarR.bottom + 4f * k && e.y < missionR.top && e.x > dailyR.right && e.x < drawerLeft
        ) {
            ui.particles.burst(e.x, e.y, 18, Palette.S_GOLD, 120f, 480f, 3f, 7f, 0.6f, Particles.SPARK)
            ui.rings.add(e.x, e.y, 6f, 70f, Palette.ORANGE, 0.4f, 3f)
            app.sfx.play(Sfx.TAP_ZONE, 0.7f)
            app.haptics.tap()
        }
        return super.onTouch(e)
    }

    // ============================================================================================
    // Update
    // ============================================================================================

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        val coins = app.progression.save.coins.toFloat()
        shownCoins = if (shownCoins < 0f) coins else shownCoins + (coins - shownCoins) * min(1f, dt * 6f)
        if (kotlin.math.abs(coins - shownCoins) < 0.5f) shownCoins = coins
        drawerT += ((if (drawerOpen) 1f else 0f) - drawerT) * min(1f, dt * 10f)
        ui.particles.update(dt)
        ui.rings.update(dt)
        // The daily countdown ticks every minute.
        if (dailyDone) {
            val minute = System.currentTimeMillis() / 60_000L
            if (minute != lastCountdownMinute) {
                lastCountdownMinute = minute
                dailySub = s.dailyBackIn(countdown())
            }
        }
        // Embers drifting up from the fingertip's glow.
        if (!app.settings.reduceEffects && ui.art.hero != null) {
            emberClock += dt
            if (emberClock > 0.22f) {
                emberClock = 0f
                val x = width * (0.35f + 0.3f * ((age * 7.13f) % 1f))
                ui.particles.burst(x, missionR.top - 2f * k, 1, Palette.S_ORANGE, 30f, 110f, 2f, 4.5f, 1.6f, Particles.SPARK, angle = -1.57f, spread = 0.9f)
            }
        }
    }

    // ============================================================================================
    // Drawing
    // ============================================================================================

    private fun appear(delay: Float): Float = Ease.outCubic(((age - delay) / 0.45f).coerceIn(0f, 1f))

    override fun draw(c: Canvas) {
        drawScene(c)
        ui.rings.draw(c)
        ui.particles.draw(c)
        drawTopBar(c)
        drawQuick(c)
        c.save()
        c.translate(-(1f - appear(0.1f)) * 30f * k, 0f)
        drawRail(c)
        c.restore()
        drawDrawer(c)
        if (hasMission) drawMission(c)
        c.save()
        c.translate(0f, (1f - appear(0.15f)) * 24f * k)
        drawStage(c)
        drawArena(c)
        drawPlay(c)
        c.restore()
        drawNav(c)
        drawButtons(c)
    }

    /** The mascot, full screen, breathing slowly, with dark bands where the controls sit. */
    private fun drawScene(c: Canvas) {
        c.drawColor(Palette.INK)
        val hero = ui.art.hero
        if (hero == null) {
            drawBackdrop(c, gridAlpha = 0.9f, horizon = 0.66f)
        } else {
            val breathe = if (app.settings.reduceEffects) 0f else 0.012f * sin(ui.time * 0.9f)
            ui.art.drawCover(c, hero, width, height, alignY = 0f, zoom = 1.02f + breathe, focusX = 0.5f, focusY = 0.3f, dy = safe.top * 0.4f, alpha = appear(0f))
        }
        tmp.set(0f, 0f, width, safe.top + 34f * k)
        c.drawRect(tmp, ui.neon.vertical(tmp, 0xEB08040A.toInt(), 0x0008040A))
        tmp.set(0f, missionR.top - 26f * k, width, stageR.top)
        c.drawRect(tmp, ui.neon.vertical(tmp, 0x0008040A, 0xE608040A.toInt()))
        ui.neon.clearGradient()
        paint.color = 0xE608040A.toInt()
        c.drawRect(0f, stageR.top, width, height, paint)
    }

    private fun drawTopBar(c: Canvas) {
        val p = app.progression
        val info = p.levelInfo
        val neon = ui.neon
        // Avatar in a gold octagon.
        val half = avatarR.width() / 2f
        val cx = avatarR.centerX()
        val cy = avatarR.centerY()
        val a = p.save.profile.avatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)
        val color = Visuals.AVATAR_COLORS[a]
        neon.glowBlob(c, cx, cy, half * 1.8f, Palette.GOLD, 0.25f)
        neon.goldFrame(c, cx, cy, half) {
            paint.color = Palette.mix(Palette.INK, color, 0.25f)
            c.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
            ui.icons.draw(c, Visuals.AVATAR_ICONS[a], cx, cy, half * 1.1f, color, Palette.INK)
        }
        // Level plate under it.
        val lp = ui.style(ui.displayPaint, 3.2f * k / ui.u, Palette.ON_GOLD, Paint.Align.CENTER)
        val lw = max(7f * k, lp.measureText(levelText(info.level)) + 3f * k)
        tmp.set(cx - lw / 2f, avatarR.bottom - 2.4f * k, cx + lw / 2f, avatarR.bottom + 2.2f * k)
        paint.color = Palette.INK
        c.drawRoundRect(tmp.left - 0.6f * k, tmp.top - 0.6f * k, tmp.right + 0.6f * k, tmp.bottom + 0.6f * k, 1.4f * k, 1.4f * k, paint)
        c.drawRoundRect(tmp, 1f * k, 1f * k, neon.vertical(tmp, Palette.GOLD_HI, Palette.GOLD))
        neon.clearGradient()
        c.drawText(levelText(info.level), cx, tmp.centerY() + lp.textSize * 0.36f, lp)
        // Name, XP bar, XP numbers.
        val x = avatarR.right + 3f * k
        val maxW = coinR.left - x - 3f * k
        val np = ui.style(ui.textPaint, 4.8f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
        ui.fitText(c, p.save.profile.nickname, x, avatarR.top + 5.4f * k, np, maxW)
        tmp.set(x, avatarR.top + 7.4f * k, x + min(maxW, 32f * k), avatarR.top + 9f * k)
        neon.bar(c, tmp, info.fraction, 0xFF46D6FF.toInt(), Palette.GREEN, 0x33FFFFFF)
        val xp = ui.style(ui.mediumPaint, 2.9f * k / ui.u, Palette.SAND, Paint.Align.LEFT)
        c.drawText(xpText, x, avatarR.top + 13f * k, xp)
        // Coins pill: coin, amount, "+".
        val ch = coinR.height()
        paint.color = 0xCC0A060C.toInt()
        c.drawRoundRect(coinR, ch / 2f, ch / 2f, paint)
        outline(coinR, ch / 2f, c)
        ui.icons.draw(c, Icon.COIN, coinR.left + ch * 0.55f, coinR.centerY(), ch * 0.72f, Palette.GOLD, Palette.GOLD_DEEP)
        val plusX = coinR.right - ch * 0.5f
        neon.circle(c, plusX, coinR.centerY(), ch * 0.34f, Palette.GOLD)
        ui.icons.draw(c, Icon.PLUS, plusX, coinR.centerY(), ch * 0.42f, Palette.ON_GOLD)
        val cp = ui.style(ui.displayPaint, 3.6f * k / ui.u, Palette.PAPER, Paint.Align.RIGHT)
        c.drawText(coinText.of(shownCoins.toLong()), plusX - ch * 0.5f, coinR.centerY() + cp.textSize * 0.36f, cp)
    }

    private val levelTexts = arrayOfNulls<String>(1000)
    private fun levelText(level: Int): String {
        val i = level.coerceIn(0, levelTexts.lastIndex)
        return levelTexts[i] ?: level.toString().also { levelTexts[i] = it }
    }

    /** A thin gold-tinted outline around a rounded rect. */
    private fun outline(r: RectF, radius: Float, c: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * ui.density
        paint.color = Palette.GLASS_EDGE
        c.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    /** Small square buttons under the coins: duel invites (with a count) and settings. */
    private fun drawQuick(c: Canvas) {
        if (mail != null) quickButton(c, mailR, Icon.MAIL, app.duel.invites.size.takeIf { it > 0 }?.toString())
        quickButton(c, gearR, Icon.GEAR, null)
    }

    private fun quickButton(c: Canvas, r: RectF, icon: Icon, badge: String?) {
        paint.color = 0xB80A060C.toInt()
        c.drawRoundRect(r, 2.4f * k, 2.4f * k, paint)
        outline(r, 2.4f * k, c)
        ui.icons.draw(c, icon, r.centerX(), r.centerY(), r.width() * 0.52f, Palette.GOLD, Palette.INK)
        if (badge != null) app.buttons.badge(c, badge, r.right - 1f * k, r.top + 1f * k)
    }

    private fun drawRail(c: Canvas) {
        val neon = ui.neon
        val cut = 2.4f * k
        // Today's challenge: red-tinted card.
        neon.glassPanel(c, dailyR, cut, Palette.mix(Palette.GLASS, Palette.RED, 0.35f), Palette.GOLD, 0.4f)
        eventTexts(c, dailyR, s.daily, dailyTitle, dailySub, if (dailyDone) Palette.GREEN else Palette.GOLD, dailyR.right - 2f * k)
        if (!dailyDone) app.buttons.badge(c, "!", dailyR.right - 1.5f * k, dailyR.top + 1.5f * k)
        // Next boss: ember card with the boss leaning out of it.
        neon.glassPanel(c, bossR, cut, Palette.mix(Palette.GLASS, Palette.EMBER, 0.3f), Palette.GOLD, 0.4f)
        ui.bossArt.illustration(nextBossKind)?.let { drawBossPeek(c, it) }
        eventTexts(c, bossR, s.nextBoss, bossName, bossSub, Palette.SAND, bossR.right - 12f * k)
        // Shop and achievements medallions.
        roundButton(c, shopR, Icon.BAG, s.shop)
        roundButton(c, medalR, Icon.MEDAL, achievementsText)
    }

    private fun drawBossPeek(c: Canvas, boss: Bitmap) {
        val size = 15f * k
        tmp.set(bossR.right - size + 1f * k, bossR.bottom - size + 1.5f * k, bossR.right + 1f * k, bossR.bottom + 1.5f * k)
        ui.neon.glowBlob(c, tmp.centerX(), tmp.centerY(), size * 0.6f, Palette.EMBER, 0.4f)
        paint.color = Palette.WHITE
        c.drawBitmap(boss, null, tmp, paint)
    }

    private fun eventTexts(c: Canvas, r: RectF, kicker: String, title: String, sub: String, subColor: Int, maxRight: Float) {
        val x = r.left + 2.2f * k
        val w = maxRight - x
        val kp = ui.style(ui.displayPaint, 2.2f * k / ui.u, Palette.GOLD, Paint.Align.LEFT)
        ui.fitText(c, kicker, x, r.top + 4.6f * k, kp, w)
        val tp = ui.style(ui.textPaint, 3.5f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
        ui.fitText(c, title, x, r.top + 10f * k, tp, w)
        val sp = ui.style(ui.mediumPaint, 2.7f * k / ui.u, subColor, Paint.Align.LEFT)
        ui.fitText(c, sub, x, r.top + 14.4f * k, sp, w)
    }

    private fun roundButton(c: Canvas, r: RectF, icon: Icon, label: String) {
        val cx = r.centerX()
        val cy = r.centerY()
        val rad = r.width() / 2f
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawCircle(cx, cy, rad, ui.neon.vertical(tmp, 0xFF3A2530.toInt(), 0xFF140B12.toInt()))
        ui.neon.clearGradient()
        ui.neon.circleStroke(c, cx, cy, rad, Palette.GLASS_EDGE, 1f * ui.density)
        ui.icons.draw(c, icon, cx, cy, rad * 1.05f, Palette.GOLD, Palette.INK)
        val lp = ui.style(ui.textPaint, 2.8f * k / ui.u, Palette.PAPER, Paint.Align.CENTER)
        ui.fitText(c, label, cx, r.bottom + 4f * k, lp, r.width() + 6f * k)
    }

    /** The friends drawer on the right edge (slides out to a tab). */
    private fun drawDrawer(c: Canvas) {
        val dx = (1f - drawerT) * DRAWER_SLIDE * k
        c.save()
        c.translate(dx, 0f)
        val neon = ui.neon
        // Tab.
        paint.color = Palette.GLASS
        c.drawRoundRect(tabR.left, tabR.top, tabR.right + 3f * k, tabR.bottom, 2f * k, 2f * k, paint)
        c.save()
        c.rotate(180f * drawerT, tabR.centerX(), tabR.centerY())
        ui.icons.draw(c, Icon.BACK, tabR.centerX(), tabR.centerY(), tabR.width() * 0.6f, Palette.GOLD)
        c.restore()
        // Panel.
        tmp.set(drawerR.left, drawerR.top, drawerR.right + 4f * k, drawerR.bottom)
        paint.color = Palette.GLASS
        c.drawRoundRect(tmp, 2.4f * k, 2.4f * k, paint)
        outline(tmp, 2.4f * k, c)
        val x0 = drawerR.left + 2.5f * k
        val hp = ui.style(ui.displayPaint, 2.4f * k / ui.u, Palette.GOLD, Paint.Align.LEFT)
        c.drawText(s.friendsTab, x0, drawerR.top + 5.4f * k, hp)
        var y = drawerR.top + 9f * k
        if (!app.online.enabled) {
            val tp = ui.style(ui.textPaint, 3f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
            ui.fitText(c, s.playOnline, x0, y - 0.6f * k, tp, drawerR.width() - 5f * k)
            tmp.set(x0, y + 1.5f * k, drawerR.right - 3f * k, y + 9.5f * k)
            neon.goldPlate(c, tmp, 0.06f, -1f)
            val bp = ui.style(ui.displayPaint, 3f * k / ui.u, Palette.ON_GOLD, Paint.Align.CENTER)
            ui.fitText(c, s.enable, tmp.centerX(), tmp.centerY() + bp.textSize * 0.36f, bp, tmp.width() - 3f * k)
            c.restore()
            return
        }
        for (row in friendRows) {
            val ax = x0 + 4.5f * k
            val ay = y + 5.5f * k
            tmp.set(ax - 4.5f * k, ay - 4.5f * k, ax + 4.5f * k, ay + 4.5f * k)
            paint.color = Palette.mix(Palette.INK, row.color, 0.2f)
            c.drawRoundRect(tmp, 1.8f * k, 1.8f * k, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f * k
            paint.color = row.color
            c.drawRoundRect(tmp, 1.8f * k, 1.8f * k, paint)
            paint.style = Paint.Style.FILL
            ui.icons.draw(c, row.icon, ax, ay, 5.4f * k, row.color, Palette.INK)
            val nx = tmp.right + 2f * k
            val nameW = drawerR.right - 12f * k - nx
            val np = ui.style(ui.textPaint, 3.4f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
            ui.fitText(c, row.entry.nick, nx, ay - 0.4f * k, np, nameW)
            val dp = ui.style(ui.mediumPaint, 2.6f * k / ui.u, Palette.SAND, Paint.Align.LEFT)
            ui.fitText(c, row.detail, nx, ay + 3.4f * k, dp, nameW)
            // Challenge button.
            tmp.set(drawerR.right - 10.5f * k, ay - 3.8f * k, drawerR.right - 3f * k, ay + 3.8f * k)
            neon.glowBlob(c, tmp.centerX(), tmp.centerY(), 5f * k, Palette.MAGENTA, 0.35f)
            c.drawRoundRect(tmp, 1.6f * k, 1.6f * k, neon.vertical(tmp, 0xFFFF4D86.toInt(), 0xFFC0165A.toInt()))
            neon.clearGradient()
            ui.icons.draw(c, Icon.BOLT, tmp.centerX(), tmp.centerY(), 4.4f * k, Palette.WHITE)
            y += 11f * k
        }
        if (friendRows.isEmpty()) {
            val tp = ui.style(ui.mediumPaint, 2.7f * k / ui.u, Palette.SAND, Paint.Align.LEFT)
            ui.wrapText(c, s.noFriendsYet, x0, y + 3f * k, tp, drawerR.width() - 5f * k, 3.4f * k)
            y += 11f * k
        }
        // Invite.
        tmp.set(x0, y, x0 + 9f * k, y + 9f * k)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * ui.density
        paint.color = Palette.SAND
        c.drawRoundRect(tmp, 1.8f * k, 1.8f * k, paint)
        paint.style = Paint.Style.FILL
        ui.icons.draw(c, Icon.PLUS, tmp.centerX(), tmp.centerY(), 4.6f * k, Palette.SAND)
        val ip = ui.style(ui.textPaint, 3f * k / ui.u, Palette.SAND, Paint.Align.LEFT)
        c.drawText(s.invite, tmp.right + 2f * k, tmp.centerY() + ip.textSize * 0.36f, ip)
        c.restore()
    }

    /** The mission closest to its reward, in a speech bubble over the stage card. */
    private fun drawMission(c: Canvas) {
        val r = missionR
        val accent = if (missionReady) Palette.GOLD else 0xFF46D6FF.toInt()
        paint.color = 0xDB0C0810.toInt()
        c.drawRoundRect(r, 2.4f * k, 2.4f * k, paint)
        // Pointer towards JOGAR.
        val px = r.right - 14f * k
        tmp.set(px - 1.8f * k, r.bottom - 1.8f * k, px + 1.8f * k, r.bottom + 1.8f * k)
        c.save()
        c.rotate(45f, px, r.bottom)
        c.drawRect(tmp, paint)
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * ui.density
        paint.color = Palette.withAlpha(accent, 0.6f)
        c.drawRoundRect(r, 2.4f * k, 2.4f * k, paint)
        paint.style = Paint.Style.FILL
        val pulse = if (missionReady && !app.settings.reduceEffects) 0.5f + 0.5f * sin(ui.time * 5f) else 1f
        ui.icons.draw(c, if (missionReady) Icon.CHECK else Icon.LIST, r.left + 4.6f * k, r.centerY(), 5.4f * k, Palette.withAlpha(accent, 0.6f + 0.4f * pulse))
        val x = r.left + 9f * k
        val rp = ui.style(ui.displayPaint, 3f * k / ui.u, Palette.GOLD, Paint.Align.RIGHT)
        c.drawText(missionReward, r.right - 2.4f * k, r.top + 5.2f * k, rp)
        val tw = r.right - 2.4f * k - rp.measureText(missionReward) - 2f * k - x
        val tp = ui.style(ui.textPaint, 3f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
        ui.fitText(c, missionText, x, r.top + 5.2f * k, tp, tw)
        tmp.set(x, r.bottom - 3.6f * k, r.right - 2.4f * k, r.bottom - 2.4f * k)
        ui.neon.bar(c, tmp, missionFraction, accent, if (missionReady) Palette.ORANGE else Palette.GREEN, 0x33FFFFFF)
    }

    /** Stage selector: number, objective family, objective, stars and best time. */
    private fun drawStage(c: Canvas) {
        val neon = ui.neon
        val p = app.progression
        val n = p.save.selectedStage
        val stage = p.stage(n)
        neon.glassPanel(c, stageR, 3f * k, 0xE6180F1A.toInt(), Palette.GOLD, 0.5f)
        val cx = stageR.centerX()
        val tp = ui.style(ui.displayPaint, 6.2f * k / ui.u, Palette.PAPER, Paint.Align.LEFT)
        val chip = ui.style(ui.displayPaint, 2.8f * k / ui.u, Visuals.typeColor(stage.type), Paint.Align.LEFT)
        val tw = tp.measureText(stageTitle)
        val cw = chip.measureText(stageType) + 4f * k
        var x = cx - (tw + 2f * k + cw) / 2f
        val ty = stageR.top + 7f * k
        c.drawText(stageTitle, x, ty, tp)
        x += tw + 2f * k
        ui.icons.draw(c, if (stage.isBoss) Icon.SKULL else Visuals.typeIcon(stage.type), x + 1.4f * k, ty - chip.textSize * 0.38f, 3f * k, Visuals.typeColor(stage.type), Palette.INK)
        c.drawText(stageType, x + 3.4f * k, ty, chip)
        val gp = ui.style(ui.textPaint, 3.2f * k / ui.u, Palette.PAPER, Paint.Align.CENTER)
        ui.fitText(c, stageGoal, cx, stageR.top + 11.6f * k, gp, stageR.width() - 28f * k)
        // Stars and best time.
        val stars = p.save.stageStars[n] ?: 0
        val sy = stageR.top + 15.2f * k
        val bp = ui.style(ui.mediumPaint, 2.6f * k / ui.u, Palette.SAND, Paint.Align.LEFT)
        val bw = if (stageBest.isEmpty()) 0f else bp.measureText(stageBest) + 2f * k
        var sx = cx - (3 * 4f * k + bw) / 2f + 2f * k
        for (i in 0 until 3) {
            ui.icons.draw(c, if (i < stars) Icon.STAR else Icon.STAR_OUTLINE, sx, sy, 3.6f * k, if (i < stars) Palette.GOLD else Palette.withAlpha(Palette.WHITE, 0.3f))
            sx += 4f * k
        }
        if (stageBest.isNotEmpty()) c.drawText(stageBest, sx, sy + bp.textSize * 0.36f, bp)
    }

    /** The ARENA hexagon: silver and locked until the first boss falls, then gold. */
    private fun drawArena(c: Canvas) {
        val unlocked = app.progression.arenaUnlocked
        val cx = arenaR.centerX()
        val cy = arenaR.centerY()
        val rad = min(arenaR.width(), arenaR.height()) / 2f
        if (unlocked) ui.neon.glowBlob(c, cx, cy, rad * 1.4f, Palette.GOLD, 0.3f + 0.1f * sin(ui.time * 2f))
        ui.neon.hexEmblem(c, cx, cy, rad, if (unlocked) Palette.GOLD_HI else 0xFFD9D2C8.toInt(), if (unlocked) Palette.GOLD_DEEP else 0xFF2B2226.toInt(), 0xFF1A0F18.toInt())
        ui.neon.clearGradient()
        ui.icons.draw(c, if (unlocked) Icon.TROPHY else Icon.LOCK, cx, cy - rad * 0.32f, rad * 0.46f, if (unlocked) Palette.GOLD else Palette.SAND)
        val lp = ui.style(ui.displayPaint, 2.8f * k / ui.u, Palette.PAPER, Paint.Align.CENTER)
        ui.fitText(c, s.arena, cx, cy + rad * 0.2f, lp, rad * 1.5f)
        val sp = ui.style(ui.mediumPaint, 2.4f * k / ui.u, if (unlocked) Palette.GOLD else Palette.SAND, Paint.Align.CENTER)
        ui.fitText(c, arenaSub, cx, cy + rad * 0.48f, sp, rad * 1.4f)
    }

    /** JOGAR: the molten gold plate, the one thing on the screen that shouts. */
    private fun drawPlay(c: Canvas) {
        val press = Ease.outQuad(play.press)
        c.save()
        c.scale(1f - 0.03f * press, 1f - 0.03f * press, playR.centerX(), playR.centerY())
        val sweep = (ui.time % SHINE_PERIOD) / SHINE_PERIOD
        val shine = if (!app.settings.reduceEffects && sweep > 0.55f) (sweep - 0.55f) / 0.45f else -1f
        ui.neon.goldPlate(c, playR, 0.07f, shine)
        val cx = playR.centerX() + playR.width() * 0.03f
        val tp = ui.style(ui.displayPaint, 9f * k / ui.u, Palette.ON_GOLD, Paint.Align.CENTER)
        tp.setShadowLayer(0.1f, 0f, 0.4f * k, Palette.withAlpha(Palette.WHITE, 0.5f))
        ui.fitText(c, s.play, cx, playR.centerY() + tp.textSize * 0.2f, tp, playR.width() * 0.8f)
        tp.clearShadowLayer()
        val sp = ui.style(ui.textPaint, 2.8f * k / ui.u, 0xFF5A2C07.toInt(), Paint.Align.CENTER)
        sp.letterSpacing = 0.14f
        c.drawText(playSub, cx, playR.centerY() + 6.4f * k, sp)
        sp.letterSpacing = 0.03f
        // A graffiti "bora!" tag on the corner.
        val gp = ui.style(ui.tagPaint, 3.8f * k / ui.u, Palette.RED, Paint.Align.CENTER)
        c.save()
        c.rotate(8f, playR.right - 7f * k, playR.top + 4f * k)
        c.drawText(s.letsGo, playR.right - 7f * k, playR.top + 5f * k, gp)
        c.restore()
        c.restore()
    }

    /** The menu bar: icons with labels, separators, badges and a gold hairline on top. */
    private fun drawNav(c: Canvas) {
        c.drawRect(navR, ui.neon.vertical(navR, 0xF2261622.toInt(), 0xFA0E080E.toInt()))
        ui.neon.clearGradient()
        paint.color = Palette.GLASS_EDGE
        c.drawRect(0f, navR.top, width, navR.top + 1f * ui.density, paint)
        tmp.set(width * 0.3f, navR.top - 1f * ui.density, width * 0.5f, navR.top + 1.5f * ui.density)
        c.drawRect(tmp, ui.neon.horizontal(tmp, 0x00FFC53D, Palette.GOLD))
        tmp.set(width * 0.5f, navR.top - 1f * ui.density, width * 0.7f, navR.top + 1.5f * ui.density)
        c.drawRect(tmp, ui.neon.horizontal(tmp, Palette.GOLD, 0x00FFC53D))
        ui.neon.clearGradient()
        val lp = ui.style(ui.textPaint, 2.8f * k / ui.u, Palette.SAND, Paint.Align.CENTER)
        for ((i, item) in nav.withIndex()) {
            val r = item.rect
            val cx = r.centerX()
            val iy = r.top + r.height() * 0.38f
            if (i > 0) {
                paint.color = 0x1AFFFFFF
                c.drawRect(r.left, r.top + r.height() * 0.24f, r.left + 1f * ui.density, r.bottom - r.height() * 0.24f, paint)
            }
            ui.icons.draw(c, item.icon, cx, iy, 5.6f * k, Palette.PAPER, Palette.INK)
            ui.fitText(c, item.label, cx, iy + 6.6f * k, lp, r.width() - 2f * k)
            item.badge?.let { app.buttons.badge(c, it, cx + 4.6f * k, iy - 3.6f * k) }
        }
    }

    companion object {
        private const val SHINE_PERIOD = 3.4f
        private const val DRAWER_SLIDE = 44f
        private const val MAX_FRIENDS = 3
        private const val FRIENDS_TTL_MS = 60_000L
        private const val DAY_MS = 86_400_000L

        /** The drawer stays as the player left it, across visits. */
        private var drawerOpen = true
        private var friendsCache: List<OnlineEntry>? = null
        private var friendsAt = 0L
    }
}
