package com.dedonervoso.core.online

import com.dedonervoso.core.util.JsonException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseTest {
    private val app = "com.dedonervoso.app"

    private fun manifest(code: Long = 3, min: Long = 3, extra: String = "") = """
        {"schema":1,"app":"$app","versionCode":$code,"versionName":"1.2.0","minOnlineVersionCode":$min,
         "apkUrl":"https://example.org/dedo-nervoso.apk","size":2052214,"sha256":"ab",
         "notes":{"pt":["Duelo ao vivo","Correções"],"en":["Live duel","Fixes"]}$extra}
    """.trimIndent()

    @Test
    fun parsesThePublishedManifest() {
        val r = ReleaseInfo.parse(manifest(), app)
        assertEquals(3, r.versionCode)
        assertEquals("1.2.0", r.versionName)
        assertEquals(3, r.minOnlineVersionCode)
        assertEquals(listOf("Duelo ao vivo", "Correções"), r.notes("pt"))
        assertEquals(listOf("Live duel", "Fixes"), r.notes("en"))
        // Round-trips through the cache format.
        val again = ReleaseInfo.parse(r.toJson(app), app)
        assertEquals(r.versionCode, again.versionCode)
        assertEquals(r.notesPt, again.notesPt)
    }

    @Test
    fun rejectsForeignOrBrokenManifests() {
        assertFailsWith<JsonException> { ReleaseInfo.parse(manifest().replace(app, "com.other.game"), app) }
        assertFailsWith<JsonException> { ReleaseInfo.parse("""{"app":"$app","versionName":"x"}""", app) }
        assertFailsWith<JsonException> { ReleaseInfo.parse(manifest().replace("https://", "http://"), app) }
        assertFailsWith<JsonException> { ReleaseInfo.parse("<html>404</html>", app) }
        // A floor above the release itself is clamped (a typo can't lock everyone out).
        assertEquals(3, ReleaseInfo.parse(manifest(code = 3, min = 99), app).minOnlineVersionCode)
    }

    @Test
    fun policyGatesOnlyOnlinePlay() {
        val r = ReleaseInfo.parse(manifest(code = 5, min = 4), app)
        assertEquals(UpdateState.UNKNOWN, UpdatePolicy.state(2, null))
        assertTrue(UpdatePolicy.onlineAllowed(2, null), "unknown never locks players out")
        assertEquals(UpdateState.UP_TO_DATE, UpdatePolicy.state(5, r))
        assertEquals(UpdateState.UP_TO_DATE, UpdatePolicy.state(6, r))
        assertEquals(UpdateState.AVAILABLE, UpdatePolicy.state(4, r))
        assertTrue(UpdatePolicy.onlineAllowed(4, r))
        assertEquals(UpdateState.REQUIRED_FOR_ONLINE, UpdatePolicy.state(3, r))
        assertFalse(UpdatePolicy.onlineAllowed(3, r))
    }

    @Test
    fun checkerFollowsMovedManifestsAndSurvivesFailures() {
        val pages = HashMap<String, HttpResponse>()
        val http = object : Http {
            override fun request(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int) =
                pages[url] ?: HttpResponse(404, "Not Found")
        }
        val checker = UpdateChecker(http, app)
        assertNull(checker.fetch("https://old/version.json"), "404 means no information")

        pages["https://old/version.json"] = HttpResponse(200, """{"app":"$app","movedTo":"https://new/version.json"}""")
        pages["https://new/version.json"] = HttpResponse(200, manifest(code = 7, min = 7))
        val moved = assertNotNull(checker.fetch("https://old/version.json"))
        assertEquals(7, moved.release.versionCode)
        assertEquals("https://new/version.json", moved.manifestUrl)

        pages["https://new/version.json"] = HttpResponse(200, """{"app":"$app","movedTo":"https://new/version.json"}""")
        assertNull(checker.fetch("https://new/version.json"), "a manifest pointing at itself is ignored")
        pages["https://new/version.json"] = HttpResponse(-1, "timeout")
        assertNull(checker.fetch("https://old/version.json"))
    }
}
