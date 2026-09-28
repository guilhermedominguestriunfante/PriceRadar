package com.dedonervoso.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.dedonervoso.app.ui.GameView

/**
 * Single activity. Everything is drawn by [GameView]; the activity only manages the window
 * (edge-to-edge, immersive), lifecycle and back navigation. Config changes are handled in
 * place (manifest configChanges), so a match is never lost to a rotation or resize.
 */
class MainActivity : Activity() {
    internal lateinit var app: GameApp
        private set
    internal lateinit var view: GameView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        app = GameApp(this)
        view = GameView(this, app)
        setContentView(view)
        // Only now does the DecorView exist: its insets controller is null (or throws) before.
        hideSystemBars()
        app.start()
    }

    private fun setupWindow() {
        val w = window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Immersive: bars hidden, revealed transiently by a swipe (games shouldn't lose taps to bars). */
    fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.decorView.windowInsetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        app.onResume()
        view.resume()
    }

    override fun onPause() {
        view.pause()
        app.onPause()
        super.onPause()
    }

    override fun onStop() {
        app.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        app.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Handled in place (no recreation): refresh language-dependent text.
        app.applySettings()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else app.host.onAppPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!app.host.onBack()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
