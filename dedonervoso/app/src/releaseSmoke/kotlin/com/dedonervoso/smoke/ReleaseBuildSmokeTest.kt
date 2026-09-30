package com.dedonervoso.smoke

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Plays the release build's bytecode (ProGuard-optimized and obfuscated) like a first-time player:
 * onboarding, stage 1 by touch, results, then checks what was saved. Only public entry points are
 * used — the activity's class name, the content view, touches and the save file — so nothing here
 * depends on names the obfuscator changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ReleaseBuildSmokeTest {
    private lateinit var view: View

    @Test
    fun releaseBytecodeRunsAFullFirstSession() {
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        @Suppress("UNCHECKED_CAST")
        val activityClass = Class.forName("com.dedonervoso.app.MainActivity") as Class<out Activity>
        val controller = Robolectric.buildActivity(activityClass).setup()
        view = controller.get().findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) }
        frames(40)
        val density = view.resources.displayMetrics.density
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        shot("r0_intro")
        // A touch skips the opening; then the first-run onboarding shows.
        tap(w / 2f, h / 2f, 40)
        shot("r1_onboarding")

        // Three onboarding pages, then START (primary button at 62% of the height, 66dp tall).
        repeat(3) { tap(w / 2f, h / 2f, 20) }
        shot("r2_onboarding_ready")
        tap(w / 2f, h * 0.62f + 33f * density, 60)
        // Stage 1 introduces TAP with a card: dismiss it, then wait out the 3-2-1 countdown.
        tap(w / 2f, h / 2f, 10)
        frames(200)
        shot("r3_countdown_done")

        // ~9 taps/s around the centre for the whole match (stage 1 has no STOP).
        var t = 0
        while (t < 66_000) {
            val x = w / 2f + ((t * 7919) % 201 - 100) * density * 0.5f
            val y = h / 2f + ((t * 104729) % 201 - 100) * density * 0.5f
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_UP, x, y)
            frames(7)
            t += 7 * 16
            if (t in 20_000..20_111) shot("r4_playing")
        }
        frames(240)
        shot("r5_results")

        // Leaving the app flushes the save; progress must be on disk in the documented format.
        controller.pause().stop()
        frames(10)
        val save = File(RuntimeEnvironment.getApplication().filesDir, "dedo_save.json")
        assertTrue("save written", save.isFile)
        val json = save.readText()
        assertTrue("onboarding done: $json", json.contains("\"onboardingDone\":true"))
        assertTrue("stage 2 unlocked: $json", json.contains("\"highestUnlocked\":2"))
        val taps = Regex("\"totalTaps\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
        // Stage 1 asks for 150 TAPs and ends the moment they are made.
        assertTrue("taps counted: $taps", taps >= 150)
        assertTrue("best time recorded: $json", json.contains("\"stageBestTime\":{\"1\":"))
        val coins = Regex("\"coins\":(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
        assertTrue("coins earned: $coins", coins > 0)
        controller.destroy()
    }

    /**
     * Opt-in, for releases (DEDO_LIVE_FIREBASE=1 with DEDO_FIREBASE_API_KEY, which the release build
     * then carries): the shipped bytecode signs in to the real Firebase project by itself and
     * creates its player. The test reads that player back and deletes it, its friend code, its
     * weekly score and its sign-in account through the REST API.
     */
    @Test
    fun releaseBytecodeGoesOnlineOnTheRealProject() {
        assumeTrue("live check not requested (DEDO_LIVE_FIREBASE=1)", System.getenv("DEDO_LIVE_FIREBASE") == "1")
        val key = System.getenv("DEDO_FIREBASE_API_KEY").orEmpty()
        assumeTrue("DEDO_FIREBASE_API_KEY not set", key.isNotBlank())
        val project = System.getenv("DEDO_FIREBASE_PROJECT_ID") ?: System.getProperty("dedo.firebaseProjectId").orEmpty()

        val save = File(RuntimeEnvironment.getApplication().filesDir, "dedo_save.json")
        save.writeText("""{"version":1,"profile":{"nickname":"TESTE REL","avatar":1,"createdAt":1790000000000},"onboardingDone":true,"online":{"enabled":true}}""")
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        @Suppress("UNCHECKED_CAST")
        val activityClass = Class.forName("com.dedonervoso.app.MainActivity") as Class<out Activity>
        val controller = Robolectric.buildActivity(activityClass).setup()
        view = controller.get().findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) }
        frames(40)
        tap(view.width / 2f, view.height / 2f, 40)   // skip the opening

        // Update check, then sign-in and player creation on the network thread; the save records it.
        fun saved(field: String) = Regex("\"$field\":\"([^\"]*)\"").find(save.readText())?.groupValues?.get(1).orEmpty()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && (saved("uid").isEmpty() || saved("code").length != 6)) {
            Thread.sleep(50)
            frames(3)
        }
        controller.pause().stop().destroy()
        val uid = saved("uid")
        val code = saved("code")
        val refresh = saved("token")
        assertTrue("signed in and saved (uid, friend code, session)", uid.isNotEmpty() && code.length == 6 && refresh.isNotEmpty())

        val (status, session) = http(
            "POST", "https://securetoken.googleapis.com/v1/token?key=$key",
            "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"), form = true,
        )
        assertEquals("session renewal: $session", 200, status)
        val token = Regex("\"id_token\"\\s*:\\s*\"([^\"]+)\"").find(session)!!.groupValues[1]
        val docs = "https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"
        val names = "projects/$project/databases/(default)/documents"
        val today = LocalDate.now(ZoneOffset.UTC)
        val week = "%d-W%02d".format(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        try {
            val (found, player) = http("GET", "$docs/players/$uid", token = token)
            assertEquals("player on the server: $player", 200, found)
            assertTrue("player's nickname: $player", Regex("\"nick\"\\s*:\\s*\\{\\s*\"stringValue\"\\s*:\\s*\"TESTE REL\"").containsMatchIn(player))
        } finally {
            val (deleted, detail) = http(
                "POST", "$docs:commit", token = token,
                body = """{"writes":[{"delete":"$names/players/$uid"},{"delete":"$names/codes/$code"},{"delete":"$names/weeks/$week/scores/$uid"}]}""",
            )
            assertEquals("online data deleted: $detail", 200, deleted)
            assertEquals(200, http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$key", """{"idToken":"$token"}""").first)
        }
    }

    private fun http(method: String, url: String, body: String? = null, token: String? = null, form: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    }

    private fun frames(n: Int) {
        repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, action, x, y, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tap(x: Float, y: Float, settle: Int) {
        touch(MotionEvent.ACTION_DOWN, x, y)
        frames(2)
        touch(MotionEvent.ACTION_UP, x, y)
        frames(settle)
    }

    private fun shot(name: String) {
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("dedo.screenshots") ?: "build/screenshots-release").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }
}
