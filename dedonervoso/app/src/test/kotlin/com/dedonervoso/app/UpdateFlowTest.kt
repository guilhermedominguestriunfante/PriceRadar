package com.dedonervoso.app

import android.content.Intent
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.app.ui.screens.HomeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New-version notices: the manifest is served by a local stand-in for GitHub. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class UpdateFlowTest {

    private fun manifest(code: Long, min: Long, name: String) = """
        {"schema":1,"app":"com.dedonervoso.app","versionCode":$code,"versionName":"$name","minOnlineVersionCode":$min,
         "apkUrl":"https://example.org/dedo-nervoso.apk","size":2000000,"sha256":"00",
         "notes":{"pt":["Duelo ao vivo","Itens de sabotagem"],"en":["Live duel","Sabotage items"]}}
    """.trimIndent()

    @Test
    fun newVersionIsAnnouncedAndDownloadOpensTheApk() {
        val h = GameHarness()
        val next = GameHarness.buildVersion + 1
        h.server.routes["/release/version.json"] = { 200 to manifest(next, next, "1.2.0") }
        h.launch(GameHarness.progressedSave())
        val app = h.app
        assertEquals("installed version comes from the manifest", GameHarness.buildVersion, app.updates.version.code)
        h.awaitCondition("update check") { app.updates.release != null }
        assertEquals(UpdateState.REQUIRED_FOR_ONLINE, app.updates.state)
        assertTrue(!app.updates.onlineAllowed)
        h.frames(30)
        assertTrue(app.host.current is HomeScreen)
        val pill = h.button(app.strings.updateAvailable("1.2.0"))
        h.screenshot("50_update_notice")
        h.click(pill, 30)
        h.screenshot("51_update_dialog")
        h.clickDialog(app.strings.download, 10)
        val intent = shadowOf(h.activity).nextStartedActivity
        assertNotNull("download opens the browser", intent)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://example.org/dedo-nervoso.apk", intent.dataString)
        // Remembered for offline starts.
        assertTrue(app.progression.save.releaseCache.contains("1.2.0"))
    }

    @Test
    fun sameVersionShowsNothingAndOnlineStaysOpen() {
        val h = GameHarness()
        val installed = GameHarness.buildVersion
        h.server.routes["/release/version.json"] = { 200 to manifest(installed, installed, "atual") }
        h.launch(GameHarness.progressedSave())
        h.awaitCondition("update check") { h.app.updates.release != null }
        h.frames(10)
        assertEquals(UpdateState.UP_TO_DATE, h.app.updates.state)
        assertTrue(h.app.updates.onlineAllowed)
        assertTrue(h.buttons().none { it.label.startsWith(h.app.strings.updateAvailable("").trim()) })
    }

    @Test
    fun unreachableManifestNeverBlocksAnything() {
        val h = GameHarness().launch(GameHarness.progressedSave())   // the stand-in answers 404
        h.awaitCondition("request made") { h.server.calls.any { it.path == "/release/version.json" } }
        h.frames(10)
        assertEquals(UpdateState.UNKNOWN, h.app.updates.state)
        assertTrue(h.app.updates.onlineAllowed)
    }
}
