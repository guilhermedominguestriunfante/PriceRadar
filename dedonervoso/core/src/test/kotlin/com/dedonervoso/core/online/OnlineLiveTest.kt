package com.dedonervoso.core.online

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The online layer against the real Firebase project, for releases. Opt-in:
 *
 *     DEDO_LIVE_FIREBASE=1 ./gradlew :core:test --tests '*OnlineLiveTest*' --rerun
 *
 * with DEDO_FIREBASE_API_KEY in the environment (project: dedo.firebaseProjectId in
 * gradle.properties, or DEDO_FIREBASE_PROJECT_ID). Two throwaway players are created, play, become
 * friends and are deleted again; a third anonymous session checks that nothing was left behind.
 */
class OnlineLiveTest {
    private val http = JavaNetHttp()

    @Test
    fun twoPlayersOnTheRealProject() {
        assumeTrue(System.getenv("DEDO_LIVE_FIREBASE") == "1", "live check not requested (DEDO_LIVE_FIREBASE=1)")
        val key = System.getenv("DEDO_FIREBASE_API_KEY").orEmpty()
        assumeTrue(key.isNotBlank(), "DEDO_FIREBASE_API_KEY not set")
        val project = System.getenv("DEDO_FIREBASE_PROJECT_ID") ?: System.getProperty("dedo.firebaseProjectId").orEmpty()
        val config = FirebaseConfig(project, key)
        fun service() = OnlineService(FirebaseClient(config, http), appVersion = 5)

        val alice = OnlineProfile("TESTE_A", 1, 1)
        val bob = OnlineProfile("TESTE_B", 2, 1)
        val a = OnlineAccount()
        val b = OnlineAccount()
        val traces = ArrayList<String>()
        try {
            service().connect(a, alice, OnlineResult(1, 10, 1f, 1))
            service().connect(b, bob, null)
            assertTrue(a.exists && b.exists && a.code != b.code)
            traces += listOf("players/${a.uid}", "players/${b.uid}", "codes/${a.code}", "codes/${b.code}")

            assertEquals("TESTE_A", service().addFriend(b, bob, OnlineService.formatCode(a.code)))
            // A new app start resumes the saved session (token refresh).
            service().connect(b, bob, null)
            Thread.sleep(2_100)   // weekly scores accept one write every 2 s
            service().submit(b, bob, OnlineResult(2, 20, 2f, 1))
            val week = OnlineService.isoWeek(System.currentTimeMillis())
            traces += listOf("${OnlineService.ARENA_WEEKS}/$week/scores/${a.uid}", "${OnlineService.ARENA_WEEKS}/$week/scores/${b.uid}")

            val friends = service().leaderboard(b, OnlineBoard.FRIENDS, OnlineMetric.SCORE)
            assertEquals(listOf("TESTE_B" to 2L, "TESTE_A" to 1L), friends.map { it.nick to it.score })
            // Every ordered board query the game runs is served (single-field indexes).
            for (board in listOf(OnlineBoard.GLOBAL, OnlineBoard.WEEK)) {
                for (metric in OnlineMetric.values()) service().leaderboard(a, board, metric)
            }
            // And the "top X%" count of the weekly Arena board.
            assertEquals(0L, service().weekStanding(b, 2).above)
        } finally {
            if (a.exists) service().deleteAccount(a)
            if (b.exists) service().deleteAccount(b)
        }

        val client = FirebaseClient(config, http)
        val inspector = client.signUpAnonymously()
        try {
            for (path in traces) assertNull(client.get(inspector, path), "$path was left behind")
        } finally {
            client.deleteAccount(inspector)
        }
    }
}
