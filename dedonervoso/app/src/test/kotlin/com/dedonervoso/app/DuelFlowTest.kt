package com.dedonervoso.app

import android.view.MotionEvent
import com.dedonervoso.app.platform.Duel
import com.dedonervoso.app.platform.Endpoints
import com.dedonervoso.app.platform.Online
import com.dedonervoso.app.ui.screens.DuelLobbyScreen
import com.dedonervoso.app.ui.screens.DuelResultScreen
import com.dedonervoso.app.ui.screens.DuelWaitScreen
import com.dedonervoso.app.ui.screens.HomeScreen
import com.dedonervoso.app.ui.screens.PlayScreen
import com.dedonervoso.core.engine.GameState
import com.dedonervoso.core.online.AuthSession
import com.dedonervoso.core.online.DuelInvite
import com.dedonervoso.core.online.DuelReport
import com.dedonervoso.core.online.DuelRoom
import com.dedonervoso.core.online.DuelService
import com.dedonervoso.core.online.FirebaseClient
import com.dedonervoso.core.online.FirebaseConfig
import com.dedonervoso.core.online.JavaNetHttp
import com.dedonervoso.core.online.OnlineAccount
import com.dedonervoso.core.online.OnlineProfile
import com.dedonervoso.core.online.OnlineResult
import com.dedonervoso.core.online.OnlineService
import com.dedonervoso.core.online.RealtimeDb
import com.dedonervoso.core.stage.Mechanic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicReference

/**
 * Live duels end to end against the Firebase emulators (Auth, Firestore and the Realtime Database
 * with firebase/database.rules.json). The app plays one side; the rival is a second account driven
 * straight through [DuelService]. Skipped when the emulators aren't running.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DuelFlowTest {
    private val project = "demo-dedonervoso"
    private val http = JavaNetHttp()
    private val config = FirebaseConfig.emulator(project)
    private val streams = ArrayList<RealtimeDb.Stream>()

    @After
    fun cleanUp() {
        streams.forEach { it.close() }
        Endpoints.firebaseOverride = null
    }

    private fun useEmulators() {
        val up = http.request("GET", "http://127.0.0.1:8080/", timeoutMs = 700).code > 0 &&
            http.request("GET", "http://127.0.0.1:9099/", timeoutMs = 700).code > 0 &&
            http.request("GET", "http://127.0.0.1:9000/.json?ns=${config.databaseNs}", timeoutMs = 700).code > 0
        assumeTrue("Firebase emulators not running", up)
        http.request("DELETE", "http://127.0.0.1:8080/emulator/v1/projects/$project/databases/(default)/documents")
        http.request("DELETE", "http://127.0.0.1:9099/emulator/v1/projects/$project/accounts")
        http.request("PUT", "http://127.0.0.1:9000/.json?ns=${config.databaseNs}", "null", mapOf("Authorization" to "Bearer owner"))
        Endpoints.firebaseOverride = config
    }

    /** The other player: a real account on the emulators, driven straight through the services. */
    private inner class Rival(val nick: String) {
        val profile = OnlineProfile(nick, 3, 7)
        val account = OnlineAccount()
        private val client = FirebaseClient(config, http)
        val db = RealtimeDb(client, http)
        val duels = DuelService(db)
        val session: AuthSession

        /** The room as the rival sees it; [lastRoom] keeps the last one after it is gone. */
        val room = AtomicReference<DuelRoom?>(null)
        val lastRoom = AtomicReference<DuelRoom?>(null)

        @Volatile
        var roomGone = false

        init {
            OnlineService(client, GameHarness.buildVersion).connect(account, profile, OnlineResult(400, 120, 8.5f, 4))
            session = client.resume(account.uid, account.refreshToken)
        }

        fun invites(): List<DuelInvite> = DuelService.parseInvites(db.get(session, "invites/${session.uid}"))

        fun watch(id: String) {
            streams += duels.watch(session, id, { r ->
                room.set(r)
                if (r != null) lastRoom.set(r) else if (lastRoom.get() != null) roomGone = true
            })
        }

        fun report(id: String, score: Long, done: Boolean = false) =
            duels.report(session, id, DuelReport(nick, 3, score, score / 3, 90, done))
    }

    /** The app online, with [rival] as a friend. */
    private fun launchWithFriend(rival: Rival): GameHarness {
        val save = GameHarness.progressedSave()
        save.onlineEnabled = true
        save.seenIntros += Mechanic.values()
        val h = GameHarness().launch(save)
        h.awaitCondition("online") { h.app.online.status == Online.Status.READY }
        h.app.addFriend(OnlineService.formatCode(rival.account.code))
        h.awaitCondition("friend added") { h.app.online.account.friends == listOf(rival.account.uid) }
        h.frames(10)
        return h
    }

    @Test
    fun challengeAFriendThrowAndReceiveItemsAndWin() {
        useEmulators()
        val rival = Rival("RIVAL")
        val h = launchWithFriend(rival)
        val app = h.app
        val s = app.strings
        val me = app.online.account.uid

        // Home → DUELO → the friend with CHALLENGE.
        assertTrue(app.host.current is HomeScreen)
        h.click(s.duel, 60)
        val lobby = app.host.current as DuelLobbyScreen
        h.awaitCondition("friends listed") { lobby.friendNicksForTest == listOf("RIVAL") }
        h.frames(10)
        h.screenshot("70_duel_lobby")
        h.click(s.challenge, 40)
        assertTrue(app.host.current is DuelWaitScreen)
        h.awaitCondition("challenge sent") { app.duel.current?.stage == Duel.Stage.WAITING }
        h.frames(10)
        h.screenshot("71_duel_waiting")

        // The friend gets the challenge and accepts it.
        var invites: List<DuelInvite> = emptyList()
        h.awaitCondition("challenge received") {
            invites = rival.invites()
            invites.isNotEmpty()
        }
        val invite = invites.single()
        assertEquals(me, invite.from)
        val id = invite.duelId
        rival.watch(id)
        assertTrue(rival.duels.accept(rival.session, invite))
        rival.duels.join(rival.session, id, rival.profile)

        // The host joins and sets the start: the match opens and waits for it.
        h.awaitCondition("match opened", 15_000) { app.host.current is PlayScreen }
        val play = app.host.current as PlayScreen
        val sess = play.sessionForTest!!
        val match = app.duel.current!!
        assertTrue(match.host)
        assertTrue(sess.duel)
        h.frames(10)
        h.screenshot("72_duel_face_off")

        // The match: the app taps ~15/s (holding still during STOPs) and throws what it catches;
        // the friend reports every ~400 ms, throws a SLOW early on and finishes before the end.
        val arena = play.arenaForTest
        val item = play.itemRectForTest
        var frame = 0
        var rivalScore = 0L
        var slowSent = false
        var doneSent = false
        var holdUntil = 0
        var slowShot = false
        var orbShot = false
        while (app.host.current === play && frame < 7_000) {
            h.frames(1)
            frame++
            val st = sess.state
            if (st != GameState.READY && !doneSent && frame % 25 == 0) {
                rivalScore += 9
                rival.report(id, rivalScore)
            }
            if (!slowSent && st.isActive && sess.matchTimeMs > 3_000) {
                rival.duels.throwItem(rival.session, id, 0, "SLOW")
                slowSent = true
            }
            if (!doneSent && st.isActive && sess.timeLeftMs < 4_000) {
                rival.report(id, RIVAL_FINAL, done = true)
                doneSent = true
            }
            if (!slowShot && sess.slowActive) {
                h.screenshot("73_duel_slowed")
                slowShot = true
            }
            if (!orbShot && sess.activeOrb?.let { it.progress > 0.5f } == true) {
                h.screenshot("74_duel_orb")
                orbShot = true
            }
            if (sess.heldItem != null && st.isActive) {
                h.touch(MotionEvent.ACTION_DOWN, item.centerX(), item.centerY())
                h.touch(MotionEvent.ACTION_UP, item.centerX(), item.centerY())
                continue
            }
            if (st == GameState.STOP || sess.warningActive) {
                holdUntil = frame + 16
                continue
            }
            if (!st.isActive || frame < holdUntil || frame % 4 != 0) continue
            val x = arena.centerX() + ((frame * 7919) % 101 - 50) * 1.2f
            val y = arena.centerY() + ((frame * 104729) % 101 - 50) * 1.2f
            h.touch(MotionEvent.ACTION_DOWN, x, y)
            h.touch(MotionEvent.ACTION_UP, x, y)
        }
        assertTrue("result screen after the match", app.host.current is DuelResultScreen)
        h.awaitCondition("decided") { match.outcome != null }
        h.frames(60)
        h.screenshot("75_duel_victory")
        assertTrue("app score ${sess.score}", sess.score > RIVAL_FINAL)
        assertEquals(Duel.Outcome.WIN, match.outcome)
        assertEquals(Duel.Reason.SCORE, match.reason)
        assertEquals(RIVAL_FINAL, match.rivalScore)
        assertEquals("the friend's SLOW landed once", 1, sess.itemsReceived)
        assertTrue("an orb was caught and thrown", sess.itemsUsed >= 1)

        // What the friend saw: the same seed, the items thrown and the final score; then the host
        // closed the room.
        val seen = rival.lastRoom.get()!!
        assertEquals(seen.seed, match.seed)
        assertEquals(sess.itemsUsed, seen.throws.count { it.from == me })
        h.awaitCondition("final score seen by the friend") { rival.lastRoom.get()!!.players[me]?.done == true }
        assertEquals(sess.score, rival.lastRoom.get()!!.players.getValue(me).score)
        h.awaitCondition("room closed by the host", 20_000) { rival.roomGone }
    }

    @Test
    fun acceptAChallengeFromTheDialogTakeAStopAndGiveUp() {
        useEmulators()
        val rival = Rival("ZECA")
        val h = launchWithFriend(rival)
        val app = h.app
        val s = app.strings
        val me = app.online.account.uid

        // The friend challenges: the dialog shows up over Home.
        val id = rival.duels.challenge(rival.session, rival.profile, me)
        rival.watch(id)
        h.awaitCondition("challenge dialog") { app.host.dialogButtonsForTest.any { it.label == s.accept } }
        h.frames(20)
        h.screenshot("76_duel_challenge_dialog")
        h.clickDialog(s.accept, 20)
        assertTrue(app.host.current is DuelWaitScreen)

        // The friend (the host) sees the answer, joins and sets the start.
        h.awaitCondition("accepted") { rival.room.get()?.accepted == true }
        val offset = rival.duels.join(rival.session, id, rival.profile)
        rival.duels.setStart(rival.session, id, System.currentTimeMillis() + offset + 4_000)
        h.awaitCondition("match opened", 15_000) { app.host.current is PlayScreen }
        val play = app.host.current as PlayScreen
        val sess = play.sessionForTest!!
        val match = app.duel.current!!
        assertFalse(match.host)
        assertEquals(id, match.id)

        // After the 3-2-1 the friend's STOP lands on this screen.
        h.awaitCondition("playing", 20_000) { sess.state.isActive }
        rival.duels.throwItem(rival.session, id, 0, "STOP")
        h.awaitCondition("STOP received") { sess.itemsReceived == 1 }
        h.awaitCondition("forced STOP") { sess.state == GameState.STOP }
        h.screenshot("77_duel_stop_received")

        // No pause in a duel: back asks, and giving up loses and closes the room.
        h.activity.onBackPressed()
        h.frames(10)
        assertTrue(app.host.dialogButtonsForTest.any { it.label == s.giveUp })
        h.clickDialog(s.giveUp, 20)
        h.awaitCondition("result") { app.host.current is DuelResultScreen }
        assertEquals(Duel.Outcome.LOSS, match.outcome)
        assertEquals(Duel.Reason.GAVE_UP, match.reason)
        h.awaitCondition("room closed", 10_000) { rival.roomGone }
        h.frames(60)
        h.screenshot("78_duel_gave_up")
    }

    companion object {
        private const val RIVAL_FINAL = 150L
    }
}
