package com.taptap.game

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeGraphicsProbeTest {
    @Test
    fun drawsRealPixels() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        c.drawCircle(100f, 100f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
        c.drawText("TAP", 60f, 190f, p)
        assertEquals(Color.RED, bmp.getPixel(100, 100))
        assertEquals(Color.BLACK, bmp.getPixel(5, 5))
        val dir = File(System.getProperty("taptap.screenshots")).apply { mkdirs() }
        File(dir, "probe.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
