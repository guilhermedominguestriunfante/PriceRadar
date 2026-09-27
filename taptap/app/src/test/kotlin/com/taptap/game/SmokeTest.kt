package com.taptap.game

import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun activityLaunches() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val text = activity.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as TextView
        assertEquals("Hello tap tap", text.text.toString())
        assertEquals(35, activity.applicationInfo.targetSdkVersion)
    }
}
