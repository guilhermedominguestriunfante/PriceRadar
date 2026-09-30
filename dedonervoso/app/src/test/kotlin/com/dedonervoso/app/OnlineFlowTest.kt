package com.dedonervoso.app

import com.dedonervoso.app.platform.Endpoints
import com.dedonervoso.app.platform.Online
import com.dedonervoso.app.ui.screens.HomeScreen
import com.dedonervoso.app.ui.screens.ProfileScreen
import com.dedonervoso.app.ui.screens.RankingScreen
import com.dedonervoso.core.online.FirebaseClient
import com.dedonervoso.core.online.FirebaseConfig
import com.dedonervoso.core.online.JavaNetHttp
import com.dedonervoso.core.online.OnlineAccount
import com.dedonervoso.core.online.OnlineProfile
import com.dedonervoso.core.online.OnlineResult
import com.dedonervoso.core.online.OnlineService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Online play end to end. With the Firebase emulators running (`firebase emulators:start` in
 * firebase/), the app talks to them exactly as it would to the real project; those tests are
 * skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OnlineFlowTest {
    private val project = "demo-dedonervoso"
    private val http = JavaNetHttp()

    @After
    fun restoreEndpoints() {
        Endpoints.firebaseOverride = null
    }

    private fun emulatorsUp(): Boolean =
        http.request("GET", "http://127.0.0.1:8080/", timeoutMs = 700).code > 0 && http.request("GET", "http://127.0.0.1:9099/", timeoutMs = 700).code > 0

    private fun useEmulators() {
        assumeTrue("Firebase emulators not running", emulatorsUp())
        http.request("DELETE", "http://127.0.0.1:8080/emulator/v1/projects/$project/databases/(default)/documents")
        http.request("DELETE", "http://127.0.0.1:9099/emulator/v1/projects/$project/accounts")
        Endpoints.firebaseOverride = FirebaseConfig.emulator(project)
    }

    /** A friend who already plays online (made directly through the service). */
    private fun friend(nick: String, score: Long): OnlineAccount {
        val account = OnlineAccount()
        OnlineService(FirebaseClient(FirebaseConfig.emulator(project), http), GameHarness.buildVersion)
            .connect(account, OnlineProfile(nick, 3, 7), OnlineResult(score, 400, 9.5f, 9))
        return account
    }

    @Test
    fun turningOnlineOnShowsRankingsAndFriends() {
        useEmulators()
        val bob = friend("BOB", 5_000)
        val h = GameHarness().launch(GameHarness.progressedSave())
        val app = h.app
        assertEquals(Online.Status.OFF, app.online.status)

        h.click(app.strings.ranking, 60)
        assertTrue(h.app.host.current is RankingScreen)
        h.click(app.strings.onlineTab, 20)
        assertEquals(app.strings.onlineOffText, (h.app.host.current as RankingScreen).messageForTest)
        h.screenshot("60_online_off")
        h.click(app.strings.enableOnline, 20)
        h.clickDialog(app.strings.enable, 20)
        h.awaitCondition("signed in with a friend code") { app.online.status == Online.Status.READY && app.online.friendCode.isNotEmpty() }
        h.awaitCondition("global board") { "BOB" in (h.app.host.current as RankingScreen).onlineNicksForTest }
        h.frames(20)
        h.screenshot("61_online_global")
        // The local Arena best (7,890) was published and ranks above Bob's 5,000.
        val rows = (h.app.host.current as RankingScreen).onlineNicksForTest
        assertEquals(listOf("KAWE", "BOB"), rows)

        app.addFriend(OnlineService.formatCode(bob.code))
        h.awaitCondition("friend added") { app.online.account.friends == listOf(bob.uid) }
        h.click(app.strings.friendsTab, 20)
        h.awaitCondition("friends board") { (h.app.host.current as RankingScreen).onlineNicksForTest == listOf("KAWE", "BOB") }
        h.frames(20)
        h.screenshot("62_online_friends")

        h.activity.onBackPressed()
        h.frames(40)
        assertTrue(h.app.host.current is HomeScreen)
        h.click(app.strings.profile, 60)
        assertTrue(h.app.host.current is ProfileScreen)
        h.screenshot("63_profile_online")
        // Remembered: the next launch signs in with the same account.
        assertTrue(app.progression.save.online.exists)
        assertTrue(app.progression.save.onlineEnabled)
    }

    @Test
    fun arenaMatchReachesTheWeeklyBoard() {
        useEmulators()
        val save = GameHarness.progressedSave()
        save.onlineEnabled = true
        save.seenIntros += com.dedonervoso.core.stage.Mechanic.values()
        save.ranking.clear()
        val h = GameHarness().launch(save)
        h.awaitCondition("connected") { h.app.online.status == Online.Status.READY }
        h.click(h.app.strings.arena, 60)
        h.playMatch()
        assertTrue(h.app.host.current is com.dedonervoso.app.ui.screens.ResultScreen)
        val client = FirebaseClient(FirebaseConfig.emulator(project), http)
        val acc = h.app.progression.save.online
        h.awaitCondition("weekly Arena score published") {
            val s = client.resume(acc.uid, acc.refreshToken)
            client.get(s, "${OnlineService.ARENA_WEEKS}/${OnlineService.isoWeek(System.currentTimeMillis())}/scores/${acc.uid}") != null
        }
        // The result shows where the match stands this week (alone on the board: #1 / 1).
        h.frames(120)
        h.screenshot("65_arena_result_online")
    }

    @Test
    fun campaignMatchesStayOffTheBoards() {
        useEmulators()
        val save = GameHarness.progressedSave()
        save.onlineEnabled = true
        save.seenIntros += com.dedonervoso.core.stage.Mechanic.values()
        save.ranking.clear()
        save.selectedStage = 1
        val h = GameHarness().launch(save)
        h.awaitCondition("connected") { h.app.online.status == Online.Status.READY }
        h.click(h.app.strings.play, 60)
        h.playMatch()
        val client = FirebaseClient(FirebaseConfig.emulator(project), http)
        val acc = h.app.progression.save.online
        h.frames(60)
        val s = client.resume(acc.uid, acc.refreshToken)
        assertEquals(null, client.get(s, "${OnlineService.ARENA_WEEKS}/${OnlineService.isoWeek(System.currentTimeMillis())}/scores/${acc.uid}"))
        assertEquals(12L, client.get(s, "players/${acc.uid}")!!.long("bestStage"))
    }

    @Test
    fun onlineWaitsForTheUpdateWhenRequired() {
        val h = GameHarness()
        val next = GameHarness.buildVersion + 1
        h.server.routes["/release/version.json"] = {
            200 to """{"app":"com.dedonervoso.app","versionCode":$next,"versionName":"1.2.0","minOnlineVersionCode":$next,"apkUrl":"https://example.org/a.apk"}"""
        }
        val save = GameHarness.progressedSave()
        save.onlineEnabled = true
        Endpoints.firebaseOverride = FirebaseConfig("demo-dedonervoso", "key", authBase = h.server.url("/auth"), tokenBase = h.server.url("/token"), firestoreBase = h.server.url("/fs"))
        h.launch(save)
        h.awaitCondition("manifest") { h.app.updates.release != null }
        assertEquals(Online.Status.UPDATE_REQUIRED, h.app.online.status)
        h.click(h.app.strings.ranking, 60)
        h.click(h.app.strings.onlineTab, 20)
        assertEquals(h.app.strings.updateRequiredOnline, (h.app.host.current as RankingScreen).messageForTest)
        h.button(h.app.strings.download)
        h.screenshot("64_online_update_required")
        // Nothing was sent to the backend.
        assertTrue(h.server.calls.none { it.path.startsWith("/auth") || it.path.startsWith("/fs") })
    }

    @Test
    fun withoutFirebaseSettingsOnlineSaysUnavailable() {
        val save = GameHarness.progressedSave()
        save.onlineEnabled = true
        val h = GameHarness().launch(save)   // the harness hides the build's Firebase project
        assertEquals(Online.Status.UNAVAILABLE, h.app.online.status)
        h.click(h.app.strings.ranking, 60)
        h.click(h.app.strings.onlineTab, 20)
        assertEquals(h.app.strings.onlineUnavailable, (h.app.host.current as RankingScreen).messageForTest)
    }
}
