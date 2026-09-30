package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Online
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.DuelArt
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScrollArea
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.core.engine.DuelItem
import com.dedonervoso.core.online.DuelInvite
import com.dedonervoso.core.online.OnlineBoard
import com.dedonervoso.core.online.OnlineEntry
import com.dedonervoso.core.online.OnlineMetric
import com.dedonervoso.core.stage.StageCatalog
import kotlin.math.min

/**
 * Live duels: how they work (the three items and the pace each one asks for), the challenges
 * received (accept / decline) and the friends to challenge. Friends come from the online friends
 * board; duels need online play connected and the Realtime Database in the build.
 */
class DuelLobbyScreen(app: GameApp) : Screen(app) {
    private enum class Load { IDLE, LOADING, LOADED, FAILED }

    private lateinit var scroll: ScrollArea
    private var savedOffset = 0f
    private var load = Load.IDLE
    private var request = 0
    private var friends: List<OnlineEntry> = emptyList()
    private var shownAvailable = false
    private var shownFriendCount = -1

    private var intro = ""
    private var introY = 0f
    private val itemsCard = RectF()
    private var invitesTitleY = -1f
    private val inviteRows = ArrayList<Pair<DuelInvite, RectF>>()
    private var friendsTitleY = -1f
    private val friendRows = ArrayList<Pair<OnlineEntry, RectF>>()
    private var bestTexts: List<String> = emptyList()

    /** Text shown instead of the friends (switched off, loading, no friends yet…). */
    private var message: String? = null
    private var messageTitle: String? = null
    private var messageY = 0f

    /** Exposed for UI tests. */
    internal val friendNicksForTest: List<String> get() = friendRows.map { it.first.nick }
    internal val messageForTest: String? get() = message

    override fun onEnter() {
        intro = s.duelIntro((StageCatalog.duel().durationMs / 1000).toInt())
        loadFriends()
    }

    private fun loadFriends() {
        shownAvailable = app.duel.available
        shownFriendCount = app.online.account.friends.size
        if (!shownAvailable) {
            load = Load.IDLE
            return
        }
        load = Load.LOADING
        val req = ++request
        app.online.leaderboard(OnlineBoard.FRIENDS, OnlineMetric.SCORE) { list, _ ->
            if (req != request) return@leaderboard
            if (list == null) {
                load = Load.FAILED
            } else {
                load = Load.LOADED
                friends = list.filter { !it.isMe }
                // Arena best once they have one; until then how far they got in the campaign.
                bestTexts = friends.map { if (it.score > 0) "${s.arena} ${s.num(it.score)}" else "${s.stage} ${it.stage}" }
            }
            relayout()
        }
    }

    override fun onBackgroundUpdate() {
        // Online connected or dropped, a friend was added, or a challenge arrived or went away.
        val changed = app.duel.available != shownAvailable || app.online.account.friends.size != shownFriendCount
        if (changed && load != Load.LOADING) loadFriends()
        relayout()
    }

    override fun layout() {
        val top = header(s.duel)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        val left = scroll.rect.left + 8f * u
        val right = scroll.rect.right - 8f * u
        var y = scroll.rect.top + 16f * u
        introY = y
        y += lines(intro, introPaint(), right - left - 16f * u) * INTRO_LINE * u + 4f * u
        itemsCard.set(left, y, right, y + 42f * u + DuelItem.values().size * ITEM_ROW * u)
        y = itemsCard.bottom + 34f * u
        message = null
        messageTitle = null
        inviteRows.clear()
        friendRows.clear()
        invitesTitleY = -1f
        friendsTitleY = -1f
        if (!app.duel.available) {
            y = layoutUnavailable(y)
        } else {
            val invites = app.duel.invites
            if (invites.isNotEmpty()) {
                invitesTitleY = y
                y += 14f * u
                for (inv in invites) {
                    val r = RectF(left, y, right, y + ROW * u)
                    inviteRows += inv to r
                    val bw = 96f * u
                    scrolled(button(s.accept, null, Button.Style.SECONDARY, Palette.GREEN) { app.acceptInvite(inv) })
                        .rect.set(r.right - 10f * u - bw, r.centerY() - 21f * u, r.right - 10f * u, r.centerY() + 21f * u)
                    scrolled(button(s.decline, null, Button.Style.SECONDARY, Palette.DIM) { app.duel.decline(inv) })
                        .rect.set(r.right - 18f * u - 2 * bw, r.centerY() - 21f * u, r.right - 18f * u - bw, r.centerY() + 21f * u)
                    y += (ROW + 8f) * u
                }
                y += 26f * u
            }
            friendsTitleY = y
            y = layoutFriends(y + 14f * u, left, right)
        }
        scroll.contentHeight = y - scroll.rect.top + 16f * u
        scroll.scrollTo(savedOffset)
    }

    private fun layoutFriends(top: Float, left: Float, right: Float): Float {
        var y = top
        when {
            load == Load.LOADED && friends.isNotEmpty() -> for (f in friends) {
                val r = RectF(left, y, right, y + ROW * u)
                friendRows += f to r
                val bw = 124f * u
                scrolled(button(s.challenge, Icon.BOLT, Button.Style.SECONDARY, Palette.MAGENTA) { challenge(f) })
                    .rect.set(r.right - 10f * u - bw, r.centerY() - 21f * u, r.right - 10f * u, r.centerY() + 21f * u)
                y += (ROW + 8f) * u
            }
            load == Load.LOADED -> {
                messageTitle = "${s.friendCode}: ${app.online.friendCode}"
                message = s.noFriendsYet
                messageY = y + 48f * u
                val by = messageY + lines(s.noFriendsYet, messagePaint(), width - 60f * u) * MESSAGE_LINE * u + 8f * u
                val bw = (right - left - 10f * u) / 2f
                scrolled(button(s.addFriend, Icon.USER, Button.Style.SECONDARY, Palette.GREEN) { app.addFriendDialog() })
                    .rect.set(left, by, left + bw, by + 46f * u)
                scrolled(button(s.invite, Icon.NEXT, Button.Style.SECONDARY, Palette.MAGENTA) { app.shareInvite() })
                    .rect.set(right - bw, by, right, by + 46f * u)
                y = by + 46f * u
            }
            load == Load.FAILED -> {
                message = s.onlineError
                messageY = y + 28f * u
                y = centerButton(s.retry, Palette.CYAN, Button.Style.SECONDARY, messageY + 24f * u) {
                    loadFriends()
                    relayout()
                }
            }
            else -> {
                message = s.loading
                messageY = y + 28f * u
                y = messageY + 20f * u
            }
        }
        return y
    }

    private fun layoutUnavailable(top: Float): Float {
        val online = app.online
        messageY = top + 22f * u
        val text: String
        var action: (() -> Unit)? = null
        var label = ""
        var color = Palette.CYAN
        var style = Button.Style.SECONDARY
        when {
            !online.enabled -> {
                messageTitle = s.onlineOffTitle
                messageY += 26f * u
                text = s.onlineOffText
                label = s.enableOnline
                color = Palette.GREEN
                style = Button.Style.PRIMARY
                action = { app.enableOnlineWithConsent() }
            }
            !online.config.duelsConfigured -> text = s.duelsUnavailable
            online.status == Online.Status.UPDATE_REQUIRED -> {
                text = s.updateRequiredOnline
                label = s.download
                color = Palette.ORANGE
                style = Button.Style.PRIMARY
                action = { app.showUpdate() }
            }
            online.status == Online.Status.CONNECTING -> text = s.connecting
            else -> {
                text = s.onlineError
                label = s.retry
                action = { online.connect() }
            }
        }
        message = text
        val below = messageY + lines(text, messagePaint(), width - 60f * u) * MESSAGE_LINE * u + 6f * u
        val act = action ?: return below
        return centerButton(label, color, style, below) {
            act()
            relayout()
        }
    }

    private fun challenge(f: OnlineEntry) {
        app.host.push(DuelWaitScreen(app, app.duel.challenge(f.uid, f.nick, f.avatar)))
    }

    private fun centerButton(label: String, color: Int, style: Button.Style, y: Float, action: () -> Unit): Float {
        val w = min(300f * u, width * 0.8f)
        val h = (if (style == Button.Style.PRIMARY) 58f else 48f) * u
        scrolled(button(label, null, style, color, action)).rect.set(width / 2f - w / 2f, y, width / 2f + w / 2f, y + h)
        return y + h
    }

    private fun scrolled(b: Button): Button = b.also { it.scroll = scroll }

    private fun introPaint(): Paint = ui.style(ui.semiPaint, 15f, Palette.TEXT, Paint.Align.CENTER)
    private fun messagePaint(): Paint = ui.style(ui.semiPaint, 16f, Palette.DIM, Paint.Align.CENTER)

    /** Lines [text] takes when wrapped like [com.dedonervoso.app.ui.UiKit.wrapText] does. */
    private fun lines(text: String, p: Paint, maxWidth: Float): Int {
        var n = 0
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    n++
                    line = word
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) n++
        }
        return n
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        savedOffset = scroll.offset
        ui.background.update(dt * 0.5f)
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        ui.wrapText(c, intro, width / 2f, introY + 12f * u, introPaint(), itemsCard.width() - 16f * u, INTRO_LINE * u)
        drawItems(c)
        if (invitesTitleY >= 0f) sectionTitle(c, s.duelInvites, invitesTitleY, Palette.MAGENTA)
        for ((inv, r) in inviteRows) drawRow(c, r, inv.nick, inv.avatar, s.challengeText, Palette.MAGENTA, 2 * 96f + 36f)
        if (friendsTitleY >= 0f) sectionTitle(c, s.friendsTab, friendsTitleY, Palette.CYAN)
        for ((i, row) in friendRows.withIndex()) {
            val (f, r) = row
            drawRow(c, r, f.nick, f.avatar, bestTexts.getOrElse(i) { "" }, Palette.CYAN, 124f + 20f)
        }
        message?.let { msg ->
            messageTitle?.let {
                val tp = ui.style(ui.displayPaint, 17f, Palette.WHITE, Paint.Align.CENTER)
                ui.fitText(c, it, width / 2f, messageY - 30f * u, tp, width - 48f * u)
            }
            ui.wrapText(c, msg, width / 2f, messageY, messagePaint(), width - 60f * u, MESSAGE_LINE * u)
        }
        drawButtons(c, scroll)
        c.restore()
        drawButtons(c)
    }

    private fun sectionTitle(c: Canvas, text: String, y: Float, color: Int) {
        val p = ui.style(ui.displayPaint, 16f, color, Paint.Align.LEFT)
        c.drawText(text, itemsCard.left + 6f * u, y, p)
    }

    private fun drawItems(c: Canvas) {
        val r = itemsCard
        ui.neon.panel(c, r, 16f * u, Palette.withAlpha(Palette.PANEL, 0.9f), Palette.withAlpha(Palette.MAGENTA, 0.5f), 0.3f)
        sectionTitle(c, s.duelItems, r.top + 28f * u, Palette.MAGENTA)
        for ((i, item) in DuelItem.values().withIndex()) {
            val top = r.top + 42f * u + i * ITEM_ROW * u
            val cy = top + ITEM_ROW * u / 2f
            DuelArt.badge(c, ui, item, r.left + 32f * u, cy, 17f * u)
            val np = ui.style(ui.displayBoldPaint, 15f, Palette.WHITE, Paint.Align.LEFT)
            c.drawText(s.itemName(item), r.left + 62f * u, cy - 3f * u, np)
            val pp = ui.style(ui.textPaint, 15f, DuelArt.color(item), Paint.Align.RIGHT)
            c.drawText(s.itemPace(item), r.right - 16f * u, cy - 3f * u, pp)
            val ep = ui.style(ui.mediumPaint, 13f, Palette.DIM, Paint.Align.LEFT)
            ui.fitText(c, s.itemEffect(item), r.left + 62f * u, cy + 16f * u, ep, r.width() - 78f * u)
        }
    }

    private fun drawRow(c: Canvas, r: RectF, nick: String, avatar: Int, detail: String, accent: Int, buttonsW: Float) {
        ui.neon.panel(c, r, 14f * u, Palette.withAlpha(Palette.PANEL, 0.9f), Palette.withAlpha(accent, 0.45f), 0.25f)
        val a = avatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)
        val color = Visuals.AVATAR_COLORS[a]
        val cx = r.left + 30f * u
        ui.neon.circle(c, cx, r.centerY(), 18f * u, Palette.PANEL)
        ui.neon.circleStroke(c, cx, r.centerY(), 18f * u, color, 2f * u)
        ui.icons.draw(c, Visuals.AVATAR_ICONS[a], cx, r.centerY(), 20f * u, color, Palette.BG_TOP)
        val maxW = r.width() - 64f * u - buttonsW * u
        val np = ui.style(ui.textPaint, 17f, Palette.WHITE, Paint.Align.LEFT)
        ui.fitText(c, nick, r.left + 58f * u, r.centerY() - 2f * u, np, maxW)
        val dp = ui.style(ui.mediumPaint, 12.5f, Palette.DIM, Paint.Align.LEFT)
        ui.fitText(c, detail, r.left + 58f * u, r.centerY() + 17f * u, dp, maxW)
    }

    companion object {
        private const val ROW = 64f
        private const val ITEM_ROW = 56f
        private const val INTRO_LINE = 20f
        private const val MESSAGE_LINE = 23f
    }
}
