package com.dedonervoso.app.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.MainActivity

/**
 * The only view. A Choreographer callback runs the game loop on vsync (update, then invalidate);
 * drawing is hardware-accelerated canvas work recorded on the UI thread and rendered by the
 * RenderThread. Touches are forwarded immediately on arrival — never queued behind rendering.
 */
@SuppressLint("ViewConstructor")
class GameView(activity: MainActivity, private val app: GameApp) : View(activity), Choreographer.FrameCallback {
    private var running = false
    private var lastFrameNanos = 0L
    private val insets = RectF()

    // FPS overlay (Settings → show FPS).
    private var fpsFrames = 0
    private var fpsTime = 0f
    private var fps = 0
    private var worstFrameMs = 0f
    private var shownWorst = 0f
    private val fpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.GREEN
        textSize = 12f * resources.displayMetrics.density
        typeface = app.ui.fonts.text
    }
    private val fpsText = NumText { "$it FPS" }

    init {
        isFocusable = true
        isHapticFeedbackEnabled = false
        setOnApplyWindowInsetsListener { _, wi ->
            readInsets(wi)
            if (width > 0) app.host.resize(width.toFloat(), height.toFloat(), insets)
            wi
        }
    }

    private fun readInsets(wi: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val cut = wi.getInsets(WindowInsets.Type.displayCutout())
            val bars = wi.getInsets(WindowInsets.Type.systemBars())
            insets.set(
                maxOf(cut.left, bars.left).toFloat(), maxOf(cut.top, bars.top).toFloat(),
                maxOf(cut.right, bars.right).toFloat(), maxOf(cut.bottom, bars.bottom).toFloat(),
            )
        } else {
            val cut = if (Build.VERSION.SDK_INT >= 28) wi.displayCutout else null
            @Suppress("DEPRECATION")
            insets.set(
                maxOf(cut?.safeInsetLeft ?: 0, wi.systemWindowInsetLeft).toFloat(),
                maxOf(cut?.safeInsetTop ?: 0, wi.systemWindowInsetTop).toFloat(),
                maxOf(cut?.safeInsetRight ?: 0, wi.systemWindowInsetRight).toFloat(),
                maxOf(cut?.safeInsetBottom ?: 0, wi.systemWindowInsetBottom).toFloat(),
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        app.host.resize(w.toFloat(), h.toFloat(), insets)
    }

    fun resume() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        if (lastFrameNanos != 0L) {
            val ms = (frameTimeNanos - lastFrameNanos) / 1e6f
            if (ms > worstFrameMs) worstFrameMs = ms
        }
        lastFrameNanos = frameTimeNanos
        fpsFrames++
        fpsTime += dt
        if (fpsTime >= 1f) {
            fps = fpsFrames
            shownWorst = worstFrameMs
            fpsFrames = 0
            fpsTime = 0f
            worstFrameMs = 0f
        }
        app.host.update(SystemClock.uptimeMillis(), dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        app.host.draw(canvas)
        if (app.settings.showFps) {
            canvas.drawText(fpsText.of(fps), insets.left + 8f * app.ui.density, height - insets.bottom - 8f * app.ui.density, fpsPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        app.host.onTouch(event)
        return true
    }
}
