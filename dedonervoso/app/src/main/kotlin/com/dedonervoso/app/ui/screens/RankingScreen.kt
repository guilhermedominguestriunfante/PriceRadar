package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Online
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScrollArea
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.core.online.OnlineBoard
import com.dedonervoso.core.online.OnlineEntry
import com.dedonervoso.core.online.OnlineMetric
import com.dedonervoso.core.progression.RankEntry
import com.dedonervoso.core.progression.RankScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rankings (spec §22). ONLINE: global, this week and friends (by points or taps) from the online
 * service, opt-in. THIS DEVICE: the local all-time / week / today results, which work offline.
 */
class RankingScreen(app: GameApp) : Screen(app) {
    private enum class Mode { ONLINE, LOCAL }
    private enum class Load { IDLE, LOADING, LOADED, FAILED }

    // Opens on ONLINE (its call to action invites players to turn it on) unless they chose otherwise.
    private var mode = lastMode ?: Mode.ONLINE
    private lateinit var scroll: ScrollArea
    private val rowRect = RectF()
    private val dateFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    // This device.
    private var scope = RankScope.ALL_TIME
    private var entries: List<RankEntry> = emptyList()
    private var latest: RankEntry? = null
    private class LocalRow(val entry: RankEntry, val score: String, val detail: String, val date: String)
    private var localRows: List<LocalRow> = emptyList()

    // Online.
    private var board = OnlineBoard.GLOBAL
    private var metric = OnlineMetric.SCORE
    private var load = Load.IDLE
    private var request = 0
    private class OnlineRow(val entry: OnlineEntry, val value: String, val detail: String, val color: Int)
    private var onlineRows: List<OnlineRow> = emptyList()
    private var shownStatus: Online.Status? = null

    /** Message shown instead of rows (empty board, switched off, errors…). */
    private var message: String? = null
    private var messageTitle: String? = null

    /** Exposed for UI tests: online rows shown and the message shown instead of rows. */
    internal val onlineNicksForTest: List<String> get() = if (mode == Mode.ONLINE && message == null && load == Load.LOADED) onlineRows.map { it.entry.nick } else emptyList()
    internal val messageForTest: String? get() = message

    override fun onEnter() {
        loadLocal()
        if (mode == Mode.ONLINE) loadOnline()
    }

    private fun loadLocal() {
        val p = app.progression
        entries = p.leaderboard.top(scope, p.today, 50)
        latest = p.save.ranking.maxByOrNull { it.timestamp }
        val s = app.strings
        localRows = entries.map { e ->
            val stageText = if (e.daily) s.daily else "${s.stage} ${e.stage}"
            LocalRow(e, s.num(e.score), "$stageText · COMBO ${s.num(e.maxCombo)} · ${s.dec1(e.maxTps)} ${s.tpsUnit}", dateFormat.format(Date(e.timestamp)))
        }
    }

    private fun loadOnline() {
        shownStatus = app.online.status
        if (!app.online.enabled || shownStatus == Online.Status.UNAVAILABLE || shownStatus == Online.Status.UPDATE_REQUIRED) {
            load = Load.IDLE
            return
        }
        load = Load.LOADING
        val req = ++request
        val b = board
        val m = metric
        app.online.leaderboard(b, m) { list, _ ->
            if (req != request) return@leaderboard
            if (list == null) {
                load = Load.FAILED
            } else {
                load = Load.LOADED
                onlineRows = list.map { e -> onlineRow(e, m) }
            }
            relayout()
        }
    }

    private fun onlineRow(e: OnlineEntry, m: OnlineMetric): OnlineRow {
        val s = app.strings
        val value = if (m == OnlineMetric.TAPS) "${s.num(e.taps)} TAPS" else s.num(e.score)
        val detail = "${s.stage} ${e.stage} · ${s.dec1(e.tps)} ${s.tpsUnit}"
        val color = Visuals.AVATAR_COLORS[e.avatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)]
        return OnlineRow(e, value, detail, color)
    }

    override fun onBackgroundUpdate() {
        // Online switched on/off, connected or needs an update: reload what the tab shows.
        if (mode == Mode.ONLINE && app.online.status != shownStatus && load != Load.LOADING) loadOnline()
        relayout()
    }

    override fun layout() {
        val top = header(s.ranking)
        // ONLINE | THIS DEVICE
        val half = (safe.width() - 8f * u) / 2f
        for ((i, m) in Mode.values().withIndex()) {
            val label = if (m == Mode.ONLINE) s.onlineTab else s.deviceTab
            val b = button(label, if (m == Mode.ONLINE) Icon.GLOBE else Icon.USER, Button.Style.SECONDARY, if (m == mode) Palette.CYAN else Palette.MUTED) {
                if (mode != m) {
                    mode = m
                    lastMode = m
                    if (m == Mode.ONLINE) loadOnline()
                    relayout()
                }
            }
            b.rect.set(safe.left + i * (half + 8f * u), top, safe.left + i * (half + 8f * u) + half, top + 40f * u)
        }
        val tabsTop = top + 48f * u
        if (mode == Mode.LOCAL) layoutLocalTabs(tabsTop) else layoutOnlineTabs(tabsTop)
        val contentTop = tabsTop + 50f * u
        var contentBottom = safe.bottom
        if (mode == Mode.ONLINE && board == OnlineBoard.FRIENDS && app.online.status == Online.Status.READY) {
            // Footer: add a friend / invite one.
            val h = 46f * u
            val add = button(s.addFriend, Icon.USER, Button.Style.SECONDARY, Palette.GREEN) { app.addFriendDialog() }
            add.rect.set(safe.left, safe.bottom - h, safe.left + half, safe.bottom)
            val inv = button(s.invite, Icon.NEXT, Button.Style.SECONDARY, Palette.MAGENTA) { app.shareInvite() }
            inv.rect.set(safe.left + half + 8f * u, safe.bottom - h, safe.right, safe.bottom)
            contentBottom = add.rect.top - 10f * u
        }
        scroll = scrollArea()
        scroll.rect.set(safe.left, contentTop, safe.right, contentBottom)
        message = null
        messageTitle = null
        if (mode == Mode.LOCAL) layoutLocalContent() else layoutOnlineContent()
    }

    private fun layoutLocalTabs(y: Float) {
        val tabW = (safe.width() - 16f * u) / 3f
        for ((i, sc) in listOf(RankScope.ALL_TIME, RankScope.WEEK, RankScope.TODAY).withIndex()) {
            val label = when (sc) {
                RankScope.ALL_TIME -> s.allTime
                RankScope.WEEK -> s.week
                RankScope.TODAY -> s.today
            }
            val b = button(label, null, Button.Style.SECONDARY, if (sc == scope) Palette.MAGENTA else Palette.MUTED) {
                scope = sc
                loadLocal()
                relayout()
            }
            b.rect.set(safe.left + i * (tabW + 8f * u), y, safe.left + i * (tabW + 8f * u) + tabW, y + 40f * u)
        }
    }

    private fun layoutOnlineTabs(y: Float) {
        val metricW = 84f * u
        val tabW = (safe.width() - metricW - 24f * u) / 3f
        for ((i, b) in OnlineBoard.values().withIndex()) {
            val label = when (b) {
                OnlineBoard.GLOBAL -> s.globalTab
                OnlineBoard.WEEK -> s.week
                OnlineBoard.FRIENDS -> s.friendsTab
            }
            val btn = button(label, null, Button.Style.SECONDARY, if (b == board) Palette.MAGENTA else Palette.MUTED) {
                if (board != b) {
                    board = b
                    loadOnline()
                    relayout()
                }
            }
            btn.rect.set(safe.left + i * (tabW + 8f * u), y, safe.left + i * (tabW + 8f * u) + tabW, y + 40f * u)
        }
        val m = button(if (metric == OnlineMetric.TAPS) s.metricTaps else s.metricPoints, null, Button.Style.SECONDARY, Palette.GOLD) {
            metric = if (metric == OnlineMetric.SCORE) OnlineMetric.TAPS else OnlineMetric.SCORE
            loadOnline()
            relayout()
        }
        m.rect.set(safe.right - metricW, y, safe.right, y + 40f * u)
    }

    private fun layoutLocalContent() {
        scroll.contentHeight = entries.size * ROW * u + 12f * u
        if (entries.isEmpty()) {
            message = s.noResults
            centerButton(s.play, Palette.CYAN, Button.Style.PRIMARY) {
                app.host.replace(PlayScreen.forStage(app, app.progression.save.selectedStage))
            }
        }
    }

    private fun layoutOnlineContent() {
        val online = app.online
        scroll.contentHeight = if (load == Load.LOADED) onlineRows.size * ROW * u + 12f * u else 0f
        when {
            !online.enabled -> {
                messageTitle = s.onlineOffTitle
                message = s.onlineOffText
                centerButton(s.enableOnline, Palette.GREEN, Button.Style.PRIMARY) { app.enableOnlineWithConsent() }
            }
            online.status == Online.Status.UNAVAILABLE -> message = s.onlineUnavailable
            online.status == Online.Status.UPDATE_REQUIRED -> {
                message = s.updateRequiredOnline
                centerButton(s.download, Palette.ORANGE, Button.Style.PRIMARY) { app.showUpdate() }
            }
            load == Load.LOADING || load == Load.IDLE -> message = if (online.status == Online.Status.CONNECTING) s.connecting else s.loading
            load == Load.FAILED -> {
                message = s.onlineError
                centerButton(s.retry, Palette.CYAN, Button.Style.SECONDARY) {
                    online.connect { loadOnline() }
                    loadOnline()
                    relayout()
                }
            }
            onlineRows.isEmpty() || (board == OnlineBoard.FRIENDS && onlineRows.size <= 1) -> {
                if (board == OnlineBoard.FRIENDS) {
                    messageTitle = "${s.friendCode}: ${online.friendCode}"
                    message = s.noFriendsYet
                } else {
                    message = s.noResults
                }
            }
        }
    }

    private fun centerButton(label: String, color: Int, style: Button.Style, action: () -> Unit) {
        val w = kotlin.math.min(300f * u, width * 0.8f)
        val y = scroll.rect.top + 170f * u
        button(label, null, style, color, action).rect.set(width / 2f - w / 2f, y, width / 2f + w / 2f, y + 58f * u)
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        ui.background.update(dt * 0.5f)
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        message?.let { msg ->
            var y = scroll.rect.top + 70f * u
            messageTitle?.let {
                val tp = ui.style(ui.displayPaint, 17f, Palette.WHITE, Paint.Align.CENTER)
                ui.fitText(c, it, width / 2f, y - 26f * u, tp, width - 48f * u)
                y += 8f * u
            }
            val p = ui.style(ui.semiPaint, 16f, Palette.DIM, Paint.Align.CENTER)
            ui.wrapText(c, msg, width / 2f, y, p, width - 60f * u, 23f * u)
        }
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        if (mode == Mode.LOCAL) drawLocalRows(c) else if (message == null && load == Load.LOADED) drawOnlineRows(c)
        c.restore()
        drawButtons(c)
    }

    private fun medalColor(i: Int) = when (i) {
        0 -> Palette.GOLD
        1 -> 0xFFCFD8E8.toInt()
        2 -> 0xFFE09A5A.toInt()
        else -> Palette.MUTED
    }

    private fun drawRank(c: Canvas, r: RectF, i: Int) {
        val medal = medalColor(i)
        val rp = ui.style(ui.displayPaint, 18f, medal, Paint.Align.CENTER)
        if (i < 3) ui.icons.draw(c, Icon.CROWN, r.left + 28f * u, r.centerY() - 10f * u, 16f * u, medal)
        c.drawText(RANKS[i.coerceAtMost(RANKS.lastIndex)], r.left + 28f * u, r.centerY() + (if (i < 3) 14f else 7f) * u, rp)
    }

    private fun rowPanel(c: Canvas, r: RectF, i: Int, mine: Boolean) {
        val medal = medalColor(i)
        ui.neon.panel(
            c, r, 14f * u, Palette.withAlpha(if (mine) Palette.mix(Palette.PANEL, Palette.CYAN, 0.18f) else Palette.PANEL, 0.9f),
            Palette.withAlpha(if (i < 3) medal else Palette.CYAN, if (mine) 0.9f else 0.35f), if (mine) 0.8f else 0.2f,
        )
    }

    private fun drawLocalRows(c: Canvas) {
        val rowH = ROW * u
        for ((i, row) in localRows.withIndex()) {
            val e = row.entry
            val top = scroll.rect.top + 6f * u + i * rowH
            if (top + rowH < scroll.offset + scroll.rect.top || top > scroll.offset + scroll.rect.bottom) continue
            val mine = e === latest
            val r = rowRect
            r.set(scroll.rect.left + 4f * u, top, scroll.rect.right - 4f * u, top + rowH - 8f * u)
            rowPanel(c, r, i, mine)
            drawRank(c, r, i)
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
    }

    private fun drawOnlineRows(c: Canvas) {
        val rowH = ROW * u
        for ((i, row) in onlineRows.withIndex()) {
            val e = row.entry
            val top = scroll.rect.top + 6f * u + i * rowH
            if (top + rowH < scroll.offset + scroll.rect.top || top > scroll.offset + scroll.rect.bottom) continue
            val r = rowRect
            r.set(scroll.rect.left + 4f * u, top, scroll.rect.right - 4f * u, top + rowH - 8f * u)
            rowPanel(c, r, i, e.isMe)
            drawRank(c, r, i)
            ui.neon.circle(c, r.left + 66f * u, r.top + 24f * u, 7f * u, row.color)
            val np = ui.style(ui.textPaint, 16f, if (e.isMe) Palette.CYAN else Palette.WHITE, Paint.Align.LEFT)
            ui.fitText(c, e.nick, r.left + 80f * u, r.top + 30f * u, np, r.width() - 230f * u)
            val dp = ui.style(ui.mediumPaint, 12.5f, Palette.DIM, Paint.Align.LEFT)
            ui.fitText(c, row.detail, r.left + 58f * u, r.top + 50f * u, dp, r.width() - 150f * u)
            val vp = ui.style(ui.displayPaint, 19f, Palette.WHITE, Paint.Align.RIGHT)
            c.drawText(row.value, r.right - 14f * u, r.top + 32f * u, vp)
            if (e.isMe) {
                val tp = ui.style(ui.mediumPaint, 12f, Palette.CYAN, Paint.Align.RIGHT)
                c.drawText(s.you, r.right - 14f * u, r.top + 50f * u, tp)
            }
        }
    }

    private val flaggedText by lazy { "⚠ " + app.strings.flagged }

    companion object {
        private const val ROW = 72f
        private val RANKS = Array(60) { (it + 1).toString() }

        /** The tab the player last looked at (kept while the app runs). */
        private var lastMode: Mode? = null
    }
}
