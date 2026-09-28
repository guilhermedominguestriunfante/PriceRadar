package com.dedonervoso.app.platform

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dedonervoso.core.engine.DuelItem
import com.dedonervoso.core.engine.GameBalance
import com.dedonervoso.core.engine.GameSession
import com.dedonervoso.core.engine.GameState
import com.dedonervoso.core.online.AuthSession
import com.dedonervoso.core.online.DuelInvite
import com.dedonervoso.core.online.DuelReport
import com.dedonervoso.core.online.DuelRoom
import com.dedonervoso.core.online.DuelService
import com.dedonervoso.core.online.FirebaseClient
import com.dedonervoso.core.online.Http
import com.dedonervoso.core.online.OnlineProfile
import com.dedonervoso.core.online.OnlineService
import com.dedonervoso.core.online.RealtimeDb
import java.util.concurrent.Executor

/**
 * Live duels for the UI, on top of [DuelService]: the challenges sent to this player (followed
 * while the app is in front and online) and one [Match] at a time, from the challenge to the
 * result. Lives on the UI thread; network calls run on [net] and stream callbacks are posted back.
 */
class Duel(private val online: Online, http: Http, private val net: Executor) {
    private val main = Handler(Looper.getMainLooper())
    private val client = FirebaseClient(online.config, http)
    private val service = DuelService(RealtimeDb(client, http))

    /** Session of the online account (network thread only). */
    private var session: AuthSession? = null

    /** Server clock − this device's, from the last write that measured it. */
    private var offset = 0L

    /** Called on the UI thread when the challenges received or the current match changed. */
    var onChange: (() -> Unit)? = null

    /** Duels need the Realtime Database in this build and online play connected. */
    val available: Boolean get() = online.config.duelsConfigured && online.status == Online.Status.READY

    /** The duel being set up, played or just finished (kept for its result screen). */
    var current: Match? = null
        private set

    /** A duel is being set up or played: no other challenge is offered meanwhile. */
    val busy: Boolean get() = current?.let { it.active && it.stage != Stage.WAITING } == true

    // ---- challenges received ---------------------------------------------------------------------

    private var foreground = false
    private var received: List<DuelInvite> = emptyList()
    private var inviteStream: RealtimeDb.Stream? = null
    private var inviteUid = ""
    private var inviteGen = 0

    /** Challenges waiting for an answer, newest first (older than 10 minutes: ignored). */
    val invites: List<DuelInvite>
        get() {
            val now = System.currentTimeMillis() + offset
            val playing = current?.takeIf { it.active }?.id
            return received.filter { now - it.atMs <= INVITE_MAX_AGE_MS && it.duelId != playing }
        }

    fun setForeground(on: Boolean) {
        foreground = on
        syncInvites()
    }

    /** Follows `invites/<uid>` while the app is in front and online; stops otherwise. */
    fun syncInvites() {
        val account = online.account
        val uid = if (foreground && available && account.exists) account.uid else ""
        if (uid == inviteUid) return
        stopInvites()
        if (uid.isEmpty()) return
        inviteUid = uid
        val gen = inviteGen
        val refreshToken = account.refreshToken
        net.execute {
            val stream = try {
                service.watchInvites(session(uid, refreshToken), { list -> main.post { if (gen == inviteGen) setInvites(list) } })
            } catch (e: RuntimeException) {
                null
            }
            main.post {
                when {
                    gen != inviteGen -> stream?.close()
                    stream != null -> inviteStream = stream
                    else -> {
                        // Could not sign in: try again later (the stream itself retries once open).
                        inviteUid = ""
                        main.postDelayed({ if (gen == inviteGen) syncInvites() }, RETRY_MS)
                    }
                }
            }
        }
    }

    private fun stopInvites() {
        inviteGen++
        inviteUid = ""
        inviteStream?.close()
        inviteStream = null
        if (received.isNotEmpty()) setInvites(emptyList())
    }

    private fun setInvites(list: List<DuelInvite>) {
        received = list
        onChange?.invoke()
    }

    fun decline(invite: DuelInvite) {
        setInvites(received.filter { it.duelId != invite.duelId })
        val account = online.account.takeIf { it.exists } ?: return
        val uid = account.uid
        val refreshToken = account.refreshToken
        net.execute {
            try {
                service.decline(session(uid, refreshToken), invite)
            } catch (e: RuntimeException) {
                // Gone already, or offline: the challenge expires by itself.
            }
        }
    }

    // ---- setting a duel up ---------------------------------------------------------------------

    /** Challenges [friendUid] as the host: the match waits for the answer. */
    fun challenge(friendUid: String, nick: String, avatar: Int): Match {
        val m = newMatch(host = true, friendUid, nick, avatar)
        val account = online.account
        if (!account.exists) return m.also { it.fail(Failure.ERROR) }
        val uid = account.uid
        val refreshToken = account.refreshToken
        net.execute {
            try {
                val s = session(uid, refreshToken)
                val id = service.challenge(s, m.me, friendUid)
                main.post {
                    if (current !== m || m.stage != Stage.CONNECTING) {
                        net.execute { quietly { service.cancel(s, id, friendUid) } }   // left meanwhile
                        return@post
                    }
                    m.auth = s
                    m.id = id
                    m.stage = Stage.WAITING
                    m.deadline = SystemClock.uptimeMillis() + ANSWER_TIMEOUT_MS
                    follow(m, s)
                    m.changed()
                }
            } catch (e: RuntimeException) {
                main.post { if (current === m) m.fail(Failure.ERROR) }
            }
        }
        return m
    }

    /** Accepts [invite] as the guest: joins, then waits for the host's start time. */
    fun accept(invite: DuelInvite): Match {
        setInvites(received.filter { it.duelId != invite.duelId })
        val m = newMatch(host = false, invite.from, invite.nick, invite.avatar)
        m.id = invite.duelId
        val account = online.account
        if (!account.exists) return m.also { it.fail(Failure.ERROR) }
        val uid = account.uid
        val refreshToken = account.refreshToken
        net.execute {
            var signed: AuthSession? = null
            try {
                val s = session(uid, refreshToken)
                signed = s
                if (!service.accept(s, invite)) {
                    main.post { if (current === m) m.fail(Failure.GONE) }
                    return@execute
                }
                val measured = service.join(s, invite.duelId, m.me)
                main.post {
                    if (current !== m || m.stage != Stage.CONNECTING) {
                        net.execute { quietly { service.close(s, invite.duelId) } }
                        return@post
                    }
                    offset = measured
                    m.auth = s
                    m.offset = measured
                    m.stage = Stage.STARTING
                    m.deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS
                    follow(m, s)
                    m.changed()
                }
            } catch (e: RuntimeException) {
                // Accepted but could not join: close the room so the host isn't left waiting.
                signed?.let { joined -> quietly { service.close(joined, invite.duelId) } }
                main.post { if (current === m) m.fail(Failure.ERROR) }
            }
        }
        return m
    }

    private fun newMatch(host: Boolean, rivalUid: String, nick: String, avatar: Int): Match {
        current?.let { if (it.active) it.cancel() }
        val m = Match(host, rivalUid, nick, avatar, online.profile())
        current = m
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
        onChange?.invoke()
        return m
    }

    private fun follow(m: Match, s: AuthSession) {
        m.stream = service.watch(s, m.id, { room -> main.post { if (current === m) m.onRoom(room) } })
    }

    /** Host, once the guest said yes: joins and sets the start a few seconds ahead (server clock). */
    private fun start(m: Match) {
        m.stage = Stage.STARTING
        m.deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS
        val s = m.auth ?: return m.fail(Failure.ERROR)
        val id = m.id
        net.execute {
            try {
                val measured = service.join(s, id, m.me)
                val startAt = System.currentTimeMillis() + measured + START_DELAY_MS
                service.setStart(s, id, startAt)
                main.post {
                    if (current !== m || m.stage != Stage.STARTING) return@post
                    offset = measured
                    m.offset = measured
                    m.begin(startAt)
                }
            } catch (e: RuntimeException) {
                main.post {
                    if (current !== m) return@post
                    m.fail(Failure.ERROR)
                    m.closeRoom()
                }
            }
        }
    }

    /** Keeps the match going (clock, reports, timeouts) even when no screen draws (app in background). */
    private val ticker = object : Runnable {
        override fun run() {
            val m = current ?: return
            m.tick(SystemClock.uptimeMillis())
            if (m.active) main.postDelayed(this, TICK_MS)
        }
    }

    fun release() {
        stopInvites()
        current?.let { if (it.active) it.cancel() }
        main.removeCallbacks(ticker)
    }

    private fun session(uid: String, refreshToken: String): AuthSession =
        session?.takeIf { it.uid == uid } ?: client.resume(uid, refreshToken).also { session = it }

    private inline fun quietly(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Best effort: the room expires or the rival's timeout covers it.
        }
    }

    enum class Stage {
        /** Creating the room (host) or answering the challenge (guest). */
        CONNECTING,

        /** Host: the challenge is out, waiting for the answer. */
        WAITING,

        /** Accepted: both join and the host sets the start time. */
        STARTING,

        /** The start time is set: 3-2-1, then play. */
        PLAYING,

        /** This player's match ended: waiting for the rival's final score. */
        FINISHED,

        /** Decided, cancelled or failed. */
        OVER,
    }

    enum class Failure { DECLINED, NO_ANSWER, GONE, ERROR }
    enum class Outcome { WIN, LOSS, DRAW }
    enum class Reason { SCORE, WALKOVER, RIVAL_GAVE_UP, GAVE_UP }

    /** One duel against [rivalUid], from the challenge to the result. UI thread only. */
    inner class Match internal constructor(
        val host: Boolean,
        val rivalUid: String,
        rivalNick: String,
        rivalAvatar: Int,
        /** How this player appears in the room. */
        internal val me: OnlineProfile,
    ) {
        var id: String = ""
            internal set
        var stage: Stage = Stage.CONNECTING
            internal set
        var failure: Failure? = null
            private set
        var outcome: Outcome? = null
            private set
        var reason: Reason = Reason.SCORE
            private set

        var rivalNick: String = rivalNick
            private set
        var rivalAvatar: Int = rivalAvatar
            private set
        var rivalScore: Long = 0L
            private set
        var rivalDone: Boolean = false
            private set

        /** False while the rival's reports stopped for more than 8 s. */
        var rivalOnline: Boolean = true
            private set

        /** Seed of the room: both players get the same orbs. */
        var seed: Long = 0L
            private set

        /** When play starts after the 3-2-1, on this device's [SystemClock.uptimeMillis]. */
        var startUptime: Long = 0L
            private set

        /** Timeout of the current step (answer or start), in uptime ms. */
        internal var deadline = 0L
        internal var auth: AuthSession? = null
        internal var stream: RealtimeDb.Stream? = null
        internal var offset = 0L

        private var game: GameSession? = null
        private val applied = HashSet<String>()
        private val pending = ArrayDeque<DuelItem>()
        private var reporting = false
        private var lastReportAt = 0L
        /** This player's match ended: the next report is the final one (done), until it gets through. */
        private var finalWanted = false
        private var finalSent = false
        private var finalTries = 0
        /** The room is to be removed once the final score is out. */
        private var closeAfterFinal = false
        private var endedAt = 0L
        private var rivalAt = 0L
        private var rivalHeardAt = 0L

        val active: Boolean get() = stage != Stage.OVER
        val myScore: Long get() = game?.score ?: 0L
        val itemsThrown: Int get() = game?.itemsUsed ?: 0
        val itemsReceived: Int get() = game?.itemsReceived ?: 0

        /** Seconds left of the current wait (answer or start). */
        fun secondsLeft(now: Long): Int = ((deadline - now + 999) / 1000).toInt().coerceAtLeast(0)

        /** The play screen's session: this match starts it on time and feeds it the rival's items. */
        fun attach(session: GameSession) {
            game = session
        }

        /**
         * Starts the attached session at the agreed moment (its 3-2-1 ends exactly at
         * [startUptime]), advances it and lands the rival's items. Called every frame and by the
         * background tick.
         */
        fun drive(now: Long) {
            val g = game ?: return
            if (stage != Stage.PLAYING) {
                if (g.state != GameState.READY) g.update(now)
                return
            }
            if (g.state == GameState.READY) {
                val countdownAt = startUptime - GameBalance.COUNTDOWN_MS
                if (now < countdownAt) return
                g.start(countdownAt)
            }
            g.update(now)
            while (pending.isNotEmpty() && g.state.isActive) g.receiveItem(pending.removeFirst(), now)
            if (g.state == GameState.FINISHED) {
                pending.clear()
                if (stage == Stage.PLAYING) finished(now)
            }
        }

        /** Sends the item just thrown ([GameSession.useItem]) to the rival. */
        fun throwItem(item: DuelItem) {
            val s = auth ?: return
            val index = (game?.itemsUsed ?: 0) - 1
            if (index !in 0..9 || id.isEmpty()) return
            val duelId = id
            net.execute {
                try {
                    service.throwItem(s, duelId, index, item.name)
                } catch (e: RuntimeException) {
                    quietly { service.throwItem(s, duelId, index, item.name) }
                }
            }
        }

        /** This player leaves mid-match: a loss, and the room closes (the rival wins). */
        fun giveUp() {
            if (!active) return
            conclude(Outcome.LOSS, Reason.GAVE_UP)
            game?.abort(SystemClock.uptimeMillis())
            closeRoom()
        }

        /** Leaves before the match (the wait screen's CANCEL, a new challenge…). */
        fun cancel() {
            if (!active) return
            val wasWaiting = stage == Stage.WAITING || stage == Stage.CONNECTING
            stage = Stage.OVER
            stream?.close()
            val s = auth
            val duelId = id
            if (s != null && duelId.isNotEmpty()) {
                net.execute { quietly { if (host && wasWaiting) service.cancel(s, duelId, rivalUid) else service.close(s, duelId) } }
            }
            changed()
        }

        internal fun fail(f: Failure) {
            if (!active) return
            failure = f
            stage = Stage.OVER
            stream?.close()
            changed()
        }

        internal fun changed() {
            if (current === this) onChange?.invoke()
        }

        internal fun onRoom(room: DuelRoom?) {
            if (room == null) {
                roomGone()
                return
            }
            seed = room.seed
            when (stage) {
                Stage.WAITING -> when (room.accepted) {
                    true -> start(this)
                    false -> {
                        fail(Failure.DECLINED)
                        closeRoom()
                    }
                    null -> Unit
                }
                Stage.STARTING -> {
                    val at = room.startAtMs
                    if (!host && at != null) begin(at)
                }
                else -> Unit
            }
            if (stage == Stage.PLAYING || stage == Stage.FINISHED) track(room)
            changed()
        }

        /** Rival's progress and items from a room snapshot. */
        private fun track(room: DuelRoom) {
            room.players[rivalUid]?.let { p ->
                if (p.nick.isNotEmpty()) rivalNick = p.nick
                rivalAvatar = p.avatar
                rivalScore = p.score
                rivalDone = p.done
                if (p.atMs != rivalAt) {
                    rivalAt = p.atMs
                    rivalHeardAt = SystemClock.uptimeMillis()
                    rivalOnline = true
                }
            }
            for (t in room.throws) {
                if (t.from != rivalUid || !applied.add(t.key)) continue
                DuelItem.of(t.type)?.let { pending.addLast(it) }
            }
            if (stage == Stage.FINISHED) resolve(SystemClock.uptimeMillis())
        }

        internal fun begin(startAtServerMs: Long) {
            val now = SystemClock.uptimeMillis()
            startUptime = startAtServerMs - offset - System.currentTimeMillis() + now
            rivalHeardAt = now
            stage = Stage.PLAYING
            changed()
        }

        private fun roomGone() {
            when (stage) {
                Stage.CONNECTING, Stage.WAITING, Stage.STARTING -> fail(Failure.GONE)
                Stage.PLAYING -> {
                    // The rival left before the end.
                    conclude(Outcome.WIN, Reason.RIVAL_GAVE_UP)
                    game?.abort(SystemClock.uptimeMillis())
                }
                Stage.FINISHED -> if (rivalDone) conclude(compare(), Reason.SCORE) else conclude(Outcome.WIN, Reason.WALKOVER)
                Stage.OVER -> Unit
            }
        }

        internal fun tick(now: Long) {
            drive(now)
            when (stage) {
                Stage.WAITING -> if (now > deadline) {
                    fail(Failure.NO_ANSWER)
                    val s = auth
                    val duelId = id
                    if (s != null) net.execute { quietly { service.cancel(s, duelId, rivalUid) } }
                }
                Stage.STARTING -> if (now > deadline) {
                    fail(Failure.ERROR)
                    closeRoom()
                }
                Stage.PLAYING -> {
                    report(now, done = false)
                    val heard = now - rivalHeardAt <= RIVAL_SILENCE_MS
                    if (heard != rivalOnline) {
                        rivalOnline = heard
                        changed()
                    }
                }
                Stage.FINISHED -> resolve(now)
                else -> Unit
            }
        }

        private fun finished(now: Long) {
            stage = Stage.FINISHED
            endedAt = now
            report(now, done = true)
            resolve(now)
            changed()
        }

        /**
         * Publishes the score (at most one report on its way). Once [done] was asked for, the final
         * report goes out as soon as the one in flight lands and is retried until it gets through,
         * whatever the match's stage by then: the rival's result depends on it.
         */
        private fun report(now: Long, done: Boolean) {
            val s = auth ?: return
            if (done) finalWanted = true
            if (finalSent || reporting || (!finalWanted && now - lastReportAt < REPORT_EVERY_MS)) return
            val final = finalWanted
            val g = game
            val r = DuelReport(me.nick, me.avatar, g?.score ?: 0L, g?.taps ?: 0L, g?.let { OnlineService.tps10(it.tps.maxTps) } ?: 0L, final)
            reporting = true
            lastReportAt = now
            val duelId = id
            net.execute {
                val measured = try {
                    service.report(s, duelId, r)
                } catch (e: RuntimeException) {
                    null
                }
                main.post {
                    reporting = false
                    if (measured != null) offset = measured
                    when {
                        !final -> if (finalWanted) report(SystemClock.uptimeMillis(), done = true)
                        measured != null -> {
                            finalSent = true
                            if (closeAfterFinal) closeSoon()
                        }
                        ++finalTries < FINAL_TRIES -> main.postDelayed({ report(SystemClock.uptimeMillis(), done = true) }, RETRY_FINAL_MS)
                        closeAfterFinal -> closeSoon()
                    }
                }
            }
        }

        /** After this player's end: the rival's final score decides, or a walkover after 15 s. */
        private fun resolve(now: Long) {
            if (stage != Stage.FINISHED) return
            when {
                rivalDone -> conclude(compare(), Reason.SCORE)
                now - endedAt >= RIVAL_END_WAIT_MS -> conclude(Outcome.WIN, Reason.WALKOVER)
            }
        }

        private fun compare(): Outcome = when {
            myScore > rivalScore -> Outcome.WIN
            myScore < rivalScore -> Outcome.LOSS
            else -> Outcome.DRAW
        }

        private fun conclude(o: Outcome, why: Reason) {
            if (!active) return
            outcome = o
            reason = why
            stage = Stage.OVER
            stream?.close()
            // Once decided the host removes the room, after its own final score is out and a moment
            // for the guest to see it; after a walkover whoever waited in vain does. Giving up
            // closed it already.
            if (why == Reason.WALKOVER || (host && why == Reason.SCORE)) {
                if (finalSent || !finalWanted) closeSoon() else closeAfterFinal = true
            }
            changed()
        }

        private fun closeSoon() {
            closeAfterFinal = false
            main.postDelayed({ closeRoom() }, CLOSE_DELAY_MS)
        }

        internal fun closeRoom() {
            val s = auth ?: return
            val duelId = id
            if (duelId.isNotEmpty()) net.execute { quietly { service.close(s, duelId) } }
        }
    }

    companion object {
        private const val INVITE_MAX_AGE_MS = 10 * 60_000L
        private const val ANSWER_TIMEOUT_MS = 60_000L
        private const val START_TIMEOUT_MS = 15_000L
        /** Host's start time: this far ahead on the server clock, so the guest learns it in time. */
        private const val START_DELAY_MS = 4_000L
        private const val REPORT_EVERY_MS = 400L
        private const val RIVAL_SILENCE_MS = 8_000L
        private const val RIVAL_END_WAIT_MS = 15_000L
        private const val CLOSE_DELAY_MS = 5_000L
        private const val RETRY_FINAL_MS = 1_000L
        private const val FINAL_TRIES = 10
        private const val RETRY_MS = 10_000L
        private const val TICK_MS = 200L
    }
}
