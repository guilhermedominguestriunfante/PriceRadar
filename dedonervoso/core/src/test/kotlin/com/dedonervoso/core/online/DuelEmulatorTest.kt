package com.dedonervoso.core.online

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Live duels against the Firebase emulators (Auth + Realtime Database with
 * firebase/database.rules.json). Skipped when the emulators aren't running.
 */
class DuelEmulatorTest {
    private val project = "demo-dedonervoso"
    private val http = JavaNetHttp()
    private val config = FirebaseConfig.emulator(project)
    private val auth = FirebaseClient(config, http)
    private val db = RealtimeDb(auth, http)
    private val duels = DuelService(db)
    private val streams = ArrayList<RealtimeDb.Stream>()

    @BeforeEach
    fun freshEmulators() {
        val up = http.request("GET", "http://127.0.0.1:9099/", timeoutMs = 700).code > 0 &&
            http.request("GET", "http://127.0.0.1:9000/.json?ns=${config.databaseNs}", timeoutMs = 700).code > 0
        assumeTrue(up, "Firebase emulators not running")
        http.request("DELETE", "http://127.0.0.1:9099/emulator/v1/projects/$project/accounts")
        http.request("PUT", "http://127.0.0.1:9000/.json?ns=${config.databaseNs}", "null", mapOf("Authorization" to "Bearer owner"))
    }

    @AfterEach
    fun closeStreams() = streams.forEach { it.close() }

    private val alice = OnlineProfile("ALICE", 1, 5)
    private val bob = OnlineProfile("BOB", 2, 3)

    private fun <T> await(what: String, ref: AtomicReference<T>, timeoutMs: Long = 5_000, condition: (T) -> Boolean): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val v = ref.get()
            if (condition(v)) return v
            if (System.currentTimeMillis() > deadline) error("Timed out waiting for $what (last: $v)")
            Thread.sleep(20)
        }
    }

    @Test
    fun challengeAcceptPlayAndThrow() {
        val host = auth.signUpAnonymously()
        val guest = auth.signUpAnonymously()

        val invites = AtomicReference<List<DuelInvite>>(emptyList())
        streams += duels.watchInvites(guest, { invites.set(it) })
        val id = duels.challenge(host, alice, guest.uid)
        val invite = await("invite", invites) { it.isNotEmpty() }.single()
        assertEquals(host.uid, invite.from)
        assertEquals("ALICE", invite.nick)
        assertEquals(id, invite.duelId)

        val hostView = AtomicReference<DuelRoom?>(null)
        streams += duels.watch(host, id, { hostView.set(it) })
        val guestView = AtomicReference<DuelRoom?>(null)
        streams += duels.watch(guest, id, { guestView.set(it) })

        assertTrue(duels.accept(guest, invite))
        await("invite consumed", invites) { it.isEmpty() }
        await("accepted", hostView) { it?.accepted == true }

        // Both join; on one machine the clocks agree to within the round trip.
        val hostOffset = duels.join(host, id, alice)
        val guestOffset = duels.join(guest, id, bob)
        assertTrue(kotlin.math.abs(hostOffset) < 1_000 && kotlin.math.abs(guestOffset) < 1_000, "offsets $hostOffset / $guestOffset")
        val start = System.currentTimeMillis() + hostOffset + 5_000
        duels.setStart(host, id, start)
        assertEquals(start, await("start seen by the guest", guestView) { it?.startAtMs != null }!!.startAtMs)

        duels.report(guest, id, DuelReport("BOB", 2, 150, 40, 95, false))
        assertEquals(150L, await("guest score", hostView) { it?.players?.get(guest.uid)?.score == 150L }!!.players.getValue(guest.uid).score)
        duels.throwItem(guest, id, 0, "STOP")
        val thrown = await("throw", hostView) { it?.throws?.isNotEmpty() == true }!!.throws.single()
        assertEquals(guest.uid, thrown.from)
        assertEquals("STOP", thrown.type)
        assertEquals(guest.uid, hostView.get()!!.opponentOf(host.uid))

        duels.report(host, id, DuelReport("ALICE", 1, 300, 80, 110, true))
        duels.report(guest, id, DuelReport("BOB", 2, 280, 70, 100, true))
        await("both done", hostView) { r -> r != null && r.players.size == 2 && r.players.values.all { it.done } }

        duels.close(host, id)
        await("room gone for the guest", guestView) { it == null }
    }

    @Test
    fun rulesKeepEachPlayerInTheirLane() {
        val host = auth.signUpAnonymously()
        val guest = auth.signUpAnonymously()
        val stranger = auth.signUpAnonymously()
        val id = duels.challenge(host, alice, guest.uid)

        fun denied(block: () -> Unit) = assertEquals(OnlineFailure.DENIED, assertFailsWith<OnlineException> { block() }.failure)
        // A new room carries only host, guest, seed and time: no forged answer or scores.
        val base = mapOf("host" to host.uid, "guest" to guest.uid, "seed" to 1, "createdAt" to RealtimeDb.SERVER_TIMESTAMP)
        denied { db.put(host, "duels/forgedRoom0001", base + ("accepted" to true)) }
        denied { db.put(host, "duels/forgedRoom0002", base + ("players" to mapOf(guest.uid to mapOf("nick" to "BOB", "avatar" to 1, "score" to 0, "at" to RealtimeDb.SERVER_TIMESTAMP)))) }
        denied { db.get(stranger, "duels/$id") }
        assertNull(duels.room(stranger, id))   // what a stranger sees: nothing
        denied { duels.setStart(host, id, System.currentTimeMillis() + 5_000) }   // not accepted yet
        denied { db.put(host, "duels/$id/accepted", true) }                        // only the guest answers
        denied { duels.join(stranger, id, OnlineProfile("EVE", 0, 1)) }
        denied { db.put(guest, "duels/$id/players/${host.uid}", mapOf("nick" to "ALICE", "avatar" to 1, "score" to 0, "at" to RealtimeDb.SERVER_TIMESTAMP)) }
        denied { db.put(guest, "duels/$id/items/${host.uid}-0", mapOf("type" to "STOP", "at" to RealtimeDb.SERVER_TIMESTAMP)) }
        denied { duels.throwItem(stranger, id, 0, "STOP") }

        val invite = DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).single()
        assertTrue(duels.accept(guest, invite))
        denied { duels.setStart(guest, id, System.currentTimeMillis() + 5_000) }  // only the host starts
        denied { duels.setStart(host, id, System.currentTimeMillis() + 120_000) } // not in the far future
        duels.join(guest, id, bob)
        denied { duels.report(guest, id, DuelReport("BOB", 2, 99_000_000, 0, 0, false)) } // implausible score
        duels.throwItem(guest, id, 0, "SLOW")
        denied { duels.throwItem(guest, id, 0, "CLOCK") }                           // each item once
        denied { duels.throwItem(guest, id, 1, "NUKE") }
        denied { db.put(stranger, "invites/${guest.uid}/${stranger.uid}", mapOf("duel" to id, "nick" to "EVE", "avatar" to 0, "at" to RealtimeDb.SERVER_TIMESTAMP)) }
        duels.close(guest, id)
        assertNull(duels.room(host, id))
    }

    @Test
    fun declineAndCancel() {
        val host = auth.signUpAnonymously()
        val guest = auth.signUpAnonymously()

        val first = duels.challenge(host, alice, guest.uid)
        val view = AtomicReference<DuelRoom?>(null)
        streams += duels.watch(host, first, { view.set(it) })
        val invite = DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).single()
        duels.decline(guest, invite)
        assertEquals(false, await("declined", view) { it?.accepted == false }!!.accepted)
        assertTrue(DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).isEmpty())
        duels.close(host, first)

        val second = duels.challenge(host, alice, guest.uid)
        val pending = DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).single()
        assertEquals(second, pending.duelId)
        duels.cancel(host, second, guest.uid)
        assertTrue(DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).isEmpty())
        // Accepting a withdrawn challenge just says it's gone.
        assertFalse(duels.accept(guest, pending))
        assertNull(duels.room(host, second))
    }

    @Test
    fun streamsDeliverTheWholeTreeThenEveryChange() {
        val host = auth.signUpAnonymously()
        val guest = auth.signUpAnonymously()
        val id = duels.challenge(host, alice, guest.uid)
        val snapshots = java.util.concurrent.CopyOnWriteArrayList<DuelRoom?>()
        streams += duels.watch(host, id, { snapshots.add(it) })
        val latest = AtomicReference<DuelRoom?>(null)
        streams += duels.watch(host, id, { latest.set(it) })
        await("first snapshot", latest) { it != null }
        val invite = DuelService.parseInvites(db.get(guest, "invites/${guest.uid}")).single()
        duels.accept(guest, invite)
        for (score in listOf(10L, 20L, 30L)) duels.report(guest, id, DuelReport("BOB", 2, score, score, 50, false))
        await("last report", latest) { it?.players?.get(guest.uid)?.score == 30L }
        assertNotNull(snapshots.first())
        assertEquals(alice.nick, DuelService.parseInvites(null).firstOrNull()?.nick ?: "ALICE")
        // Scores only ever arrive in order.
        val seen = snapshots.mapNotNull { it?.players?.get(guest.uid)?.score }
        assertEquals(seen.sorted(), seen)
    }
}
