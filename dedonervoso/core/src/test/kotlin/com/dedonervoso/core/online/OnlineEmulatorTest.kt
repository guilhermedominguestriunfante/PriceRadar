package com.dedonervoso.core.online

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The online layer against the Firebase Local Emulator Suite with the real security rules
 * (`cd firebase && firebase emulators:start --project demo-dedonervoso`). Skipped when the
 * emulators aren't running.
 */
class OnlineEmulatorTest {
    private val project = "demo-dedonervoso"
    private val http = JavaNetHttp()
    private val config = FirebaseConfig.emulator(project)

    @BeforeEach
    fun freshEmulator() {
        val up = http.request("GET", "http://127.0.0.1:8080/", timeoutMs = 700).code > 0 &&
            http.request("GET", "http://127.0.0.1:9099/", timeoutMs = 700).code > 0
        assumeTrue(up, "Firebase emulators not running")
        http.request("DELETE", "http://127.0.0.1:8080/emulator/v1/projects/$project/databases/(default)/documents")
        http.request("DELETE", "http://127.0.0.1:9099/emulator/v1/projects/$project/accounts")
    }

    private fun service() = OnlineService(FirebaseClient(config, http), appVersion = APP)
    private val alice = OnlineProfile("ALICE", 1, 5)
    private val bob = OnlineProfile("BOB", 2, 3)

    /** Firestore allows one write per document every 2 s (see firestore.rules). */
    private fun throttle() = Thread.sleep(2_100)

    @Test
    fun accountsFriendsAndArenaRankings() {
        val a = OnlineAccount()
        val sa = service()
        sa.connect(a, alice, OnlineResult(1_200, 300, 8.5f, 4))
        assertTrue(a.exists)
        assertEquals(6, a.code.length)
        assertEquals(1_200, a.arenaBest)
        assertEquals(4, a.bestStage)

        val b = OnlineAccount()
        val sb = service()
        sb.connect(b, bob, null)
        assertNotEquals(a.code, b.code)
        assertEquals("ALICE", sb.addFriend(b, bob, OnlineService.formatCode(a.code).lowercase()))
        assertEquals(listOf(a.uid), b.friends)
        assertFailsWith<OnlineException> { sb.addFriend(b, bob, "ZZZZZZ") }
        assertFailsWith<OnlineException> { sb.addFriend(b, bob, b.code) }

        // Only players with an Arena result are on the Arena board.
        assertEquals(listOf("ALICE"), sb.leaderboard(b, OnlineBoard.GLOBAL, OnlineMetric.SCORE).map { it.nick })

        throttle()
        sb.submit(b, bob, OnlineResult(1_500, 320, 9.1f, 5))
        val global = sb.leaderboard(b, OnlineBoard.GLOBAL, OnlineMetric.SCORE)
        assertEquals(listOf("BOB", "ALICE"), global.map { it.nick })
        assertTrue(global.first().isMe)
        val week = sa.leaderboard(a, OnlineBoard.WEEK, OnlineMetric.SCORE)
        assertEquals(listOf("BOB" to 1_500L), week.map { it.nick to it.score })
        val friends = sb.leaderboard(b, OnlineBoard.FRIENDS, OnlineMetric.SCORE)
        assertEquals(listOf("BOB" to 1_500L, "ALICE" to 1_200L), friends.map { it.nick to it.score })
        val byTaps = sb.leaderboard(b, OnlineBoard.GLOBAL, OnlineMetric.TAPS)
        assertEquals(listOf("BOB" to 320L, "ALICE" to 300L), byTaps.map { it.nick to it.taps })

        // A worse match changes nothing and writes nothing that could be rejected.
        sb.submit(b, bob, OnlineResult(900, 100, 5f, 2))
        assertEquals(1_500, sb.leaderboard(b, OnlineBoard.WEEK, OnlineMetric.SCORE).single().score)
        // A campaign match only raises the highest stage.
        throttle()
        sb.submit(b, bob, OnlineResult(90_000, 900, 12f, 9, arena = false))
        assertEquals(9L, b.bestStage)
        assertEquals(1_500L, b.arenaBest)
        assertEquals(1_500, sb.leaderboard(b, OnlineBoard.WEEK, OnlineMetric.SCORE).single().score)

        // Where a score stands on this week's board.
        val behind = sa.weekStanding(a, 1_000)
        assertEquals(1L, behind.above)
        assertEquals(2L, behind.total)
        assertEquals(0L, sa.weekStanding(a, 2_000).above)
    }

    @Test
    fun rulesRejectCheatsAndForeignWrites() {
        val a = OnlineAccount()
        service().connect(a, alice, OnlineResult(1_000, 200, 7f, 3))
        val client = FirebaseClient(config, http)
        val s = client.resume(a.uid, a.refreshToken)
        fun player(fields: Map<String, Any?>) = FsWrite("players/${a.uid}", fields, mask = fields.keys.toList(), serverTimestamps = listOf("updatedAt"))

        val lower = assertFailsWith<OnlineException> { client.commit(s, listOf(player(mapOf("arenaBest" to 10L)))) }
        assertEquals(OnlineFailure.DENIED, lower.failure)
        assertFailsWith<OnlineException> { client.commit(s, listOf(player(mapOf("arenaBest" to 600_000L)))) }
        assertFailsWith<OnlineException> { client.commit(s, listOf(player(mapOf("bestScore" to 999_999_999L)))) }
        assertFailsWith<OnlineException> { client.commit(s, listOf(player(mapOf("appVersion" to APP - 1)))) }
        assertFailsWith<OnlineException> { client.commit(s, listOf(player(mapOf("code" to "AAAAAA")))) }
        // Someone else's profile.
        val b = OnlineAccount()
        service().connect(b, bob, null)
        val foreign = FsWrite("players/${b.uid}", mapOf("nick" to "HACKED"), mask = listOf("nick"), serverTimestamps = listOf("updatedAt"))
        assertEquals(OnlineFailure.DENIED, assertFailsWith<OnlineException> { client.commit(s, listOf(foreign)) }.failure)
        // A plausible raise is fine.
        client.commit(s, listOf(player(mapOf("arenaBest" to 2_000L))))
        // Weekly Arena scores: never lower, and a better one only after another whole match.
        val week = "${OnlineService.ARENA_WEEKS}/${OnlineService.isoWeek(System.currentTimeMillis())}/scores/${a.uid}"
        fun weekly(score: Long) = FsWrite(
            week,
            mapOf("nick" to "ALICE", "avatar" to 1, "score" to score, "stage" to 3, "taps" to 200, "tps10" to 70, "appVersion" to APP),
            serverTimestamps = listOf("updatedAt"),
        )
        assertFailsWith<OnlineException> { client.commit(s, listOf(weekly(600_000L))) }
        client.commit(s, listOf(weekly(1_000L)))
        throttle()
        assertFailsWith<OnlineException> { client.commit(s, listOf(weekly(1_100L))) }
        assertFailsWith<OnlineException> { client.commit(s, listOf(weekly(900L))) }
    }

    @Test
    fun sessionSurvivesRestartAndDeletionRemovesEverything() {
        val a = OnlineAccount()
        service().connect(a, alice, null)
        val uid = a.uid
        val code = a.code

        // A new process (app restart) signs in again from the saved refresh token.
        val restarted = service()
        restarted.connect(a, alice, OnlineResult(700, 150, 6f, 2))
        assertEquals(uid, a.uid)
        assertEquals(code, a.code)
        assertFalse(a.dirty)

        val other = OnlineAccount()
        service().connect(other, bob, null)
        restarted.deleteAccount(a)
        assertFalse(a.exists)
        val client = FirebaseClient(config, http)
        val s = client.resume(other.uid, other.refreshToken)
        assertNull(client.get(s, "players/$uid"))
        assertNull(client.get(s, "codes/$code"))
        // Deleted accounts can't sign in anymore; the service starts a fresh identity instead.
        val stale = OnlineAccount(uid = uid, refreshToken = "revoked", code = code)
        service().connect(stale, alice, null)
        assertNotEquals(uid, stale.uid)
    }

    private companion object {
        /** The oldest app version the rules accept (minAppVersion in firestore.rules). */
        const val APP = 5L
    }
}
