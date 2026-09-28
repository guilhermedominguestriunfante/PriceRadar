package com.dedonervoso.app

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Inflates and draws the launcher icon, its themed (monochrome) layer and the launch window the
 * way the system does, so a malformed vector can't ship (it would crash at launch), and writes
 * previews next to the other screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class BrandAssetsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun launcherIconLayersAndSplashRender() {
        val icon = context.getDrawable(res("mipmap", "ic_launcher")) as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        val mono = icon.monochrome
        assertNotNull("themed icon layer", mono)

        val size = 432
        // Circle and rounded-square masks, as launchers apply them.
        val shots = listOf("icon_circle" to circle(size), "icon_squircle" to roundedSquare(size, 0.3f))
        for ((name, mask) in shots) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.rgb(236, 239, 244))
            c.save()
            c.clipPath(mask)
            icon.background.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            icon.background.draw(c)
            icon.foreground.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            icon.foreground.draw(c)
            c.restore()
            assertTrue("$name has visible artwork", opaqueShare(bmp) > 0.5f)
            save(bmp, name)
        }
        // Themed icon (Android 13+): the launcher tints the monochrome layer on a pale disc.
        val themed = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(themed).apply {
            drawColor(Color.rgb(236, 239, 244))
            clipPath(circle(size))
            drawColor(Color.rgb(211, 227, 253))
            mono!!.colorFilter = BlendModeColorFilter(Color.rgb(4, 30, 73), BlendMode.SRC_IN)
            mono.setBounds(-size / 4, -size / 4, size + size / 4, size + size / 4)
            mono.draw(this)
        }
        save(themed, "icon_themed")

        // Launch window of Android 8–11 (Android 12+ shows the same foreground on the system splash).
        val splash = context.getDrawable(res("drawable", "window_background"))!!
        val w = 822
        val h = 1782
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        splash.setBounds(0, 0, w, h)
        splash.draw(Canvas(bmp))
        save(bmp, "splash_legacy")
    }

    /** The build links resources without generating an R class, so look them up by name. */
    private fun res(type: String, name: String): Int =
        context.resources.getIdentifier(name, type, context.packageName).also { assertTrue("$type/$name exists", it != 0) }

    private fun circle(size: Int) = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }

    private fun roundedSquare(size: Int, radius: Float) = Path().apply {
        addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * radius, size * radius, Path.Direction.CW)
    }

    private fun opaqueShare(bmp: Bitmap): Float {
        var n = 0
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (p in px) if (Color.alpha(p) == 255) n++
        return n.toFloat() / px.size
    }

    private fun save(bmp: Bitmap, name: String) {
        val dir = File(System.getProperty("dedo.screenshots") ?: "build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
