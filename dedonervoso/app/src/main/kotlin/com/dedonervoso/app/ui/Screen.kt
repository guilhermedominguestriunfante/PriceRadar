package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.core.audio.MusicMode
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.util.Ease
import kotlin.math.abs

/** A full-screen state of the game (menu page or gameplay). */
abstract class Screen(protected val app: GameApp) {
    protected val ui: UiKit get() = app.ui
    protected val u: Float get() = app.ui.u
    protected val s get() = app.strings

    var width = 0f
        private set
    var height = 0f
        private set
    /** Area free of system bars / display cutouts. */
    val safe = RectF()

    protected val buttons = ArrayList<Button>()
    protected val scrolls = ArrayList<ScrollArea>()

    /** Exposed for UI tests. */
    internal val buttonsForTest: List<Button> get() = buttons
    internal val scrollsForTest: List<ScrollArea> get() = scrolls
    private var pressed: Button? = null
    private var dragScroll: ScrollArea? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    /** Seconds since this screen was entered (drives entrance animations). */
    protected var age = 0f

    open val musicMode: MusicMode get() = MusicMode.MENU

    fun resize(w: Float, h: Float, insets: RectF) {
        width = w
        height = h
        val margin = 10f * app.ui.density
        safe.set(insets.left + margin, insets.top + margin, w - insets.right - margin, h - insets.bottom - margin)
        buttons.clear()
        scrolls.clear()
        layout()
    }

    /** (Re)creates buttons and computes rects. Called on enter and on every size/inset change. */
    protected abstract fun layout()

    /** Rebuilds the layout after a state change (same size). */
    protected fun relayout() {
        buttons.clear()
        scrolls.clear()
        layout()
    }

    open fun onEnter() {}

    /**
     * Shows this screen (pushed, replaced or uncovered by a pop). The first layout gives [onEnter]
     * its geometry (the play screen sizes its arena from it); the second one uses whatever
     * [onEnter] loaded — lists, rows, badges — so scroll heights and buttons match the data.
     */
    fun enter(w: Float, h: Float, insets: RectF) {
        resize(w, h, insets)
        onEnter()
        relayout()
    }
    open fun onExit() {}
    /** Background work (update check, online sync) brought news; screens that show it re-layout. */
    open fun onBackgroundUpdate() {}
    open fun onAppPause() {}
    open fun onAppResume() {}

    open fun update(now: Long, dt: Float) {
        age += dt
        for (b in buttons) {
            val target = if (b.pressed) 1f else 0f
            b.press += (target - b.press) * minOf(1f, dt * 18f)
        }
        for (sc in scrolls) sc.update(dt)
    }

    abstract fun draw(c: Canvas)

    /** Returns true if back was handled (otherwise the host navigates back). */
    open fun onBack(): Boolean = false

    protected fun button(
        label: String, icon: Icon? = null, style: Button.Style = Button.Style.SECONDARY,
        accent: Int = Palette.CYAN, onClick: () -> Unit,
    ): Button = Button(label, icon, style, accent, onClick).also { buttons += it }

    protected fun scrollArea(): ScrollArea = ScrollArea().also { scrolls += it }

    protected fun drawButtons(c: Canvas, inScroll: ScrollArea? = null) {
        for (b in buttons) if (b.scroll === inScroll) app.buttons.draw(c, b)
    }

    /** Standard dispatch: buttons (with tap-vs-scroll disambiguation) and scroll areas. */
    open fun onTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                moved = false
                pressed = buttons.lastOrNull { it.contains(x, y) }?.also { it.pressed = true }
                dragScroll = scrolls.firstOrNull { it.rect.contains(x, y) }?.also { it.begin(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(x - downX) > TOUCH_SLOP * u || abs(y - downY) > TOUCH_SLOP * u)) {
                    moved = true
                    if (dragScroll != null) {
                        pressed?.pressed = false
                        pressed = null
                    }
                }
                dragScroll?.move(e)
                pressed?.let { it.pressed = it.contains(x, y) }
            }
            MotionEvent.ACTION_UP -> {
                dragScroll?.end(e)
                dragScroll = null
                val b = pressed
                pressed = null
                if (b != null) {
                    b.pressed = false
                    if (b.contains(x, y)) click(b)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragScroll?.end(null)
                dragScroll = null
                pressed?.pressed = false
                pressed = null
            }
        }
        return true
    }

    protected fun click(b: Button) {
        if (b.sound) app.sfx.play(if (b.backSound) Sfx.UI_BACK else Sfx.UI_CLICK, 0.9f)
        app.haptics.click()
        b.onClick()
    }

    /** Standard screen header: back button + title. Returns the y below the header. */
    protected fun header(
        title: String, icon: Icon? = null, rightReserve: Float = 0f, onBack: () -> Unit = { app.host.pop() },
    ): Float {
        val size = 44f * u
        val back = button("", Icon.BACK, Button.Style.ICON, Palette.CYAN, onBack)
        back.rect.set(safe.left, safe.top, safe.left + size, safe.top + size)
        back.backSound = true
        headerTitle = title
        headerIcon = icon
        headerMaxWidth = width - 2f * maxOf(safe.left + size + 10f * u, width - safe.right + rightReserve + 10f * u)
        return safe.top + size + 12f * u
    }

    private var headerTitle = ""
    private var headerIcon: Icon? = null
    private var headerMaxWidth = 0f

    protected fun drawHeader(c: Canvas) {
        val p = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.CENTER)
        val y = safe.top + 22f * u + p.textSize * 0.36f
        val w = p.measureText(headerTitle)
        if (headerMaxWidth > 0f && w > headerMaxWidth) p.textSize *= headerMaxWidth / w
        ui.neon.glowText(c, headerTitle, width / 2f, y, p, Palette.withAlpha(Palette.CYAN, 0.8f), 10f * u)
    }

    /**
     * Menu background: the mascot scene blurred and darkened, drifting slowly (the neon grid when
     * the illustration isn't available).
     */
    protected fun drawBackdrop(c: Canvas, gridAlpha: Float = 0.55f, horizon: Float = 0.7f) {
        val art = ui.art.backdrop
        if (art != null) {
            val drift = if (app.settings.reduceEffects) 0f else kotlin.math.sin(ui.time * 0.15f)
            ui.art.drawCover(c, art, width, height, zoom = 1.08f + 0.03f * drift, dx = drift * 10f * u)
            backdropShade.color = 0x66050208
            c.drawRect(0f, 0f, width, height, backdropShade)
            return
        }
        val bg = ui.background
        bg.horizon = horizon
        bg.gridAlpha = gridAlpha
        bg.tint = Palette.MAGENTA
        bg.tint2 = Palette.CYAN
        bg.speed = 1f
        bg.draw(c)
    }

    private val backdropShade = android.graphics.Paint()

    companion object {
        private const val TOUCH_SLOP = 8f
    }
}

/** Modal confirmation drawn above everything. */
class Dialog(
    val title: String,
    val message: String,
    val actions: List<Pair<String, () -> Unit>>,
    val danger: Boolean = false,
)

/**
 * Navigation stack with fade transitions, a modal dialog layer and a toast queue.
 * Touches are ignored while a transition runs, so double taps can't stack screens.
 */
class ScreenHost(private val app: GameApp) {
    private val stack = ArrayList<Screen>()
    val current: Screen? get() = stack.lastOrNull()

    private var width = 0f
    private var height = 0f
    private val insets = RectF()

    private var fade = 0f
    private var fadingOut = false
    private var pendingAction: (() -> Unit)? = null
    private var fadeInActive = false

    private var dialog: Dialog? = null
    private var dialogAge = 0f
    private val dialogButtons = ArrayList<Button>()
    private val dialogRect = RectF()
    private var dialogPressed: Button? = null

    private class Toast(val title: String, val subtitle: String, val icon: Icon, val color: Int)
    private val toasts = ArrayDeque<Toast>()
    private var toastAge = 0f
    private val toastRect = RectF()

    private val overlay = Paint()

    fun resize(w: Float, h: Float, safeInsets: RectF) {
        width = w
        height = h
        insets.set(safeInsets)
        app.ui.resize(w, h)
        for (s in stack) s.resize(w, h, insets)
        dialog?.let { layoutDialog(it) }
    }

    fun setRoot(screen: Screen) = navigate(fadeOut = false) {
        stack.forEach { it.onExit() }
        stack.clear()
        stack += screen
        enter(screen)
    }

    fun push(screen: Screen) = navigate {
        current?.onExit()
        stack += screen
        enter(screen)
    }

    fun replace(screen: Screen) = navigate {
        current?.onExit()
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack += screen
        enter(screen)
    }

    fun pop() {
        if (stack.size <= 1) return
        navigate {
            current?.onExit()
            stack.removeAt(stack.lastIndex)
            current?.let {
                it.enter(width, height, insets)
                app.music.engine.mode = it.musicMode
                app.screenShown()
            }
        }
    }

    /** Clears to the root screen and optionally pushes [screen]. */
    fun resetTo(root: Screen, then: Screen? = null) = navigate {
        stack.forEach { it.onExit() }
        stack.clear()
        stack += root
        if (then != null) stack += then
        enter(current!!)
        if (then != null) root.resize(width, height, insets)
    }

    private fun enter(screen: Screen) {
        screen.enter(width, height, insets)
        app.music.engine.mode = screen.musicMode
        app.screenShown()
    }

    private fun navigate(fadeOut: Boolean = true, action: () -> Unit) {
        if (pendingAction != null) return
        if (!fadeOut || width == 0f) {
            action()
            fade = if (width == 0f) 0f else 1f
            fadeInActive = width != 0f
            return
        }
        pendingAction = action
        fadingOut = true
    }

    val transitioning: Boolean get() = pendingAction != null || fadingOut

    fun update(now: Long, dt: Float) {
        app.ui.tick(dt)
        if (fadingOut) {
            fade += dt / FADE_OUT_S
            if (fade >= 1f) {
                fade = 1f
                fadingOut = false
                pendingAction?.invoke()
                pendingAction = null
                fadeInActive = true
            }
        } else if (fadeInActive) {
            fade -= dt / FADE_IN_S
            if (fade <= 0f) {
                fade = 0f
                fadeInActive = false
            }
        }
        current?.update(now, dt)
        if (dialog == null && !transitioning) app.idleFrame()
        if (dialog != null) {
            dialogAge += dt
            for (b in dialogButtons) b.press += ((if (b.pressed) 1f else 0f) - b.press) * minOf(1f, dt * 18f)
        }
        if (toasts.isNotEmpty()) {
            toastAge += dt
            if (toastAge > TOAST_S) {
                toasts.removeFirst()
                toastAge = 0f
            }
        }
    }

    fun draw(c: Canvas) {
        current?.draw(c)
        dialog?.let { drawDialog(c, it) }
        drawToast(c)
        if (fade > 0f) {
            overlay.color = Palette.withAlpha(Palette.BG_TOP, Ease.inOutCubic(fade.coerceIn(0f, 1f)))
            c.drawRect(0f, 0f, width, height, overlay)
        }
    }

    fun onTouch(e: MotionEvent): Boolean {
        if (transitioning) return true
        val d = dialog
        if (d != null) {
            dialogTouch(e)
            return true
        }
        return current?.onTouch(e) ?: false
    }

    /** Returns false when the app should close (back on the root screen). */
    fun onBack(): Boolean {
        if (transitioning) return true
        if (dialog != null) {
            dismissDialog()
            return true
        }
        val screen = current ?: return false
        if (screen.onBack()) return true
        if (stack.size > 1) {
            app.sfx.play(Sfx.UI_BACK)
            pop()
            return true
        }
        return false
    }

    fun onAppPause() = current?.onAppPause()
    fun onAppResume() = current?.onAppResume()

    // ---- dialogs -----------------------------------------------------------------------------

    /** Exposed for UI tests. */
    internal val dialogButtonsForTest: List<Button> get() = dialogButtons

    fun showDialog(d: Dialog) {
        dialog = d
        dialogAge = 0f
        layoutDialog(d)
    }

    fun dismissDialog() {
        dialog = null
        dialogButtons.clear()
    }

    private fun layoutDialog(d: Dialog) {
        val u = app.ui.u
        val w = minOf(width - 40f * u, 360f * u)
        val h = 230f * u
        dialogRect.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        dialogButtons.clear()
        val n = d.actions.size
        val gap = 12f * u
        val bw = (w - 32f * u - gap * (n - 1)) / n
        val by = dialogRect.bottom - 64f * u
        d.actions.forEachIndexed { i, (label, action) ->
            val primary = i == n - 1
            val b = Button(
                label, null,
                if (primary) (if (d.danger) Button.Style.DANGER else Button.Style.SECONDARY) else Button.Style.SECONDARY,
                if (primary) (if (d.danger) Palette.RED else Palette.CYAN) else Palette.DIM,
            ) {
                dismissDialog()
                action()
            }
            val left = dialogRect.left + 16f * u + i * (bw + gap)
            b.rect.set(left, by, left + bw, by + 48f * u)
            dialogButtons += b
        }
    }

    private fun dialogTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> dialogPressed = dialogButtons.firstOrNull { it.contains(e.x, e.y) }?.also { it.pressed = true }
            MotionEvent.ACTION_MOVE -> dialogPressed?.let { it.pressed = it.contains(e.x, e.y) }
            MotionEvent.ACTION_UP -> {
                val b = dialogPressed
                dialogPressed = null
                if (b != null) {
                    b.pressed = false
                    if (b.contains(e.x, e.y)) {
                        app.sfx.play(Sfx.UI_CLICK)
                        app.haptics.click()
                        b.onClick()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dialogPressed?.pressed = false
                dialogPressed = null
            }
        }
    }

    private fun drawDialog(c: Canvas, d: Dialog) {
        val ui = app.ui
        val u = ui.u
        val t = Ease.outCubic((dialogAge / 0.2f).coerceIn(0f, 1f))
        overlay.color = Palette.withAlpha(0xFF000000.toInt(), 0.65f * t)
        c.drawRect(0f, 0f, width, height, overlay)
        c.save()
        val sc = 0.9f + 0.1f * t
        c.scale(sc, sc, dialogRect.centerX(), dialogRect.centerY())
        ui.neon.panel(c, dialogRect, 18f * u, Palette.PANEL_LIGHT, if (d.danger) Palette.RED else Palette.CYAN, 1f)
        val tp = ui.style(ui.displayPaint, 18f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, d.title, dialogRect.centerX(), dialogRect.top + 40f * u, tp, dialogRect.width() - 32f * u)
        val mp = ui.style(ui.semiPaint, 15f, Palette.TEXT, Paint.Align.CENTER)
        ui.wrapText(c, d.message, dialogRect.centerX(), dialogRect.top + 76f * u, mp, dialogRect.width() - 40f * u, 20f * u)
        for (b in dialogButtons) app.buttons.draw(c, b)
        c.restore()
    }

    // ---- toasts --------------------------------------------------------------------------------

    fun toast(title: String, subtitle: String, icon: Icon, color: Int) {
        toasts.addLast(Toast(title, subtitle, icon, color))
    }

    private fun drawToast(c: Canvas) {
        val t = toasts.firstOrNull() ?: return
        val ui = app.ui
        val u = ui.u
        val appear = Ease.outBack((toastAge / 0.35f).coerceIn(0f, 1f))
        val leave = ((toastAge - (TOAST_S - 0.3f)) / 0.3f).coerceIn(0f, 1f)
        val y = insets.top + 14f * u - (1f - appear) * 90f * u - leave * 90f * u
        val w = minOf(width - 32f * u, 380f * u)
        toastRect.set((width - w) / 2f, y, (width + w) / 2f, y + 62f * u)
        ui.neon.panel(c, toastRect, 16f * u, Palette.PANEL_LIGHT, t.color, 1f)
        ui.icons.draw(c, t.icon, toastRect.left + 32f * u, toastRect.centerY(), 30f * u, t.color)
        val tp = ui.style(ui.textPaint, 16f, Palette.WHITE, Paint.Align.LEFT)
        ui.fitText(c, t.title, toastRect.left + 60f * u, toastRect.top + 26f * u, tp, w - 72f * u)
        val sp = ui.style(ui.mediumPaint, 13f, Palette.DIM, Paint.Align.LEFT)
        ui.fitText(c, t.subtitle, toastRect.left + 60f * u, toastRect.top + 46f * u, sp, w - 72f * u)
    }

    companion object {
        private const val FADE_OUT_S = 0.14f
        private const val FADE_IN_S = 0.22f
        private const val TOAST_S = 2.8f
    }
}
