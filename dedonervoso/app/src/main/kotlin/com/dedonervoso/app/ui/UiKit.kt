package com.dedonervoso.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import com.dedonervoso.core.util.Ease
import com.dedonervoso.app.ui.fx.Background
import com.dedonervoso.app.ui.fx.FloatingTexts
import com.dedonervoso.app.ui.fx.Particles
import com.dedonervoso.app.ui.fx.Rings
import com.dedonervoso.app.ui.fx.Sprites
import kotlin.math.abs
import kotlin.math.sin

/**
 * Shared drawing resources and metrics. `u` is the layout unit: 1 dp scaled so the portrait
 * UI fits phones from ~320 dp to tablets (spec §45).
 */
class UiKit(context: Context) {
    val density: Float = context.resources.displayMetrics.density
    var u: Float = density
        private set
    val fonts = Fonts(context.assets)
    val neon = Neon(density)
    val icons = Icons()
    val sprites = Sprites()
    val particles = Particles(sprites, density)
    val rings = Rings(density)
    val background = Background(density, neon)

    /** Paints for floating popups: 0 = display face, 1 = text face. */
    val popupPaints = arrayOf(
        textPaint(fonts.display, 20f * density, Palette.WHITE, Paint.Align.CENTER),
        textPaint(fonts.text, 20f * density, Palette.WHITE, Paint.Align.CENTER, 0.04f),
    )
    val popups = FloatingTexts(density, popupPaints)

    // Reusable paints (sizes are set per use by screens through the helpers below).
    val displayPaint = textPaint(fonts.display, 20f, Palette.WHITE, Paint.Align.CENTER)
    val displayBoldPaint = textPaint(fonts.displayBold, 20f, Palette.WHITE, Paint.Align.CENTER)
    val textPaint = textPaint(fonts.text, 20f, Palette.TEXT, Paint.Align.CENTER, 0.03f)
    val semiPaint = textPaint(fonts.textSemi, 20f, Palette.TEXT, Paint.Align.LEFT, 0.02f)
    val mediumPaint = textPaint(fonts.textMedium, 20f, Palette.DIM, Paint.Align.LEFT, 0.02f)

    /** Wordmark and glove-hand mark (Home logo, opening). */
    val brand = Brand(this)

    /** Mascot illustration and the menus' blurred backdrop. */
    val art = Art(context.assets)

    /** The five bosses. */
    val bossArt = BossArt(this, context.assets)

    var time = 0f
        private set

    fun resize(widthPx: Float, heightPx: Float) {
        val widthDp = widthPx / density
        val scale = (widthDp / 390f).coerceIn(0.82f, 1.25f)
        // Very short screens (landscape-ish, split screen): shrink further so content fits.
        val heightDp = heightPx / density
        val hScale = (heightDp / 760f).coerceIn(0.7f, 1.25f)
        u = density * minOf(scale, hScale.coerceAtLeast(0.78f))
        background.resize(widthPx, heightPx)
    }

    fun tick(dt: Float) {
        time += dt
    }

    /** Configures [p] (typeface kept) for a size in layout units and colour/alignment. */
    fun style(p: Paint, sizeU: Float, color: Int, align: Paint.Align = p.textAlign): Paint {
        p.textSize = sizeU * u
        p.color = color
        p.textAlign = align
        return p
    }

    /** Shrinks [p]'s text size in place so [text] is at most [maxWidth] wide (for effects that draw it themselves). */
    fun fitSize(p: Paint, text: String, maxWidth: Float): Paint {
        val w = p.measureText(text)
        if (w > maxWidth && maxWidth > 0f) p.textSize *= maxWidth / w
        return p
    }

    /** Draws text that shrinks to fit [maxWidth]. */
    fun fitText(c: Canvas, text: String, x: Float, y: Float, p: Paint, maxWidth: Float) {
        val w = p.measureText(text)
        if (w <= maxWidth || w <= 0f) {
            c.drawText(text, x, y, p)
        } else {
            val old = p.textSize
            p.textSize = old * maxWidth / w
            c.drawText(text, x, y, p)
            p.textSize = old
        }
    }

    /** Multi-line text wrapped to [maxWidth]; returns the height used. Allocates — use for static text. */
    fun wrapText(c: Canvas, text: String, x: Float, y: Float, p: Paint, maxWidth: Float, lineHeight: Float): Float {
        var cy = y
        for (paragraph in text.split('\n')) {
            val words = paragraph.split(' ')
            val line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                    c.drawText(line.toString(), x, cy, p)
                    cy += lineHeight
                    line.setLength(0)
                    line.append(word)
                } else {
                    line.setLength(0)
                    line.append(candidate)
                }
            }
            if (line.isNotEmpty()) {
                c.drawText(line.toString(), x, cy, p)
                cy += lineHeight
            }
        }
        return cy - y
    }

    fun release() {
        sprites.recycle()
    }
}

/** A tappable element. Rect is in screen coordinates (or content coordinates when [scroll] is set). */
class Button(
    var label: String = "",
    var icon: Icon? = null,
    var style: Style = Style.SECONDARY,
    var accent: Int = Palette.CYAN,
    var onClick: () -> Unit = {},
) {
    enum class Style { PRIMARY, SECONDARY, ICON, GHOST, DANGER, TILE, LINK }

    val rect = RectF()
    var enabled = true
    var visible = true
    var pressed = false
    var press = 0f
    var sublabel: String? = null
    var badge: String? = null
    var pulse = false
    var scroll: ScrollArea? = null
    var sound = true
    var backSound = false

    fun contains(x: Float, y: Float): Boolean {
        if (!visible || !enabled) return false
        val s = scroll
        if (s != null) {
            if (!s.rect.contains(x, y)) return false
            return rect.contains(x, y + s.offset)
        }
        // Slightly larger touch target than the visual (min ~44dp tap targets).
        return x >= rect.left - SLOP && x <= rect.right + SLOP && y >= rect.top - SLOP && y <= rect.bottom + SLOP
    }

    companion object {
        var SLOP = 6f
    }
}

/** Vertical scroll region with drag, fling and edge bounce. */
class ScrollArea {
    val rect = RectF()
    var contentHeight = 0f
    var offset = 0f
    private var velocity = 0f
    private var dragging = false
    private var lastY = 0f
    private var tracker: VelocityTracker? = null

    val maxOffset: Float get() = maxOf(0f, contentHeight - rect.height())

    fun begin(e: MotionEvent) {
        dragging = true
        velocity = 0f
        lastY = e.y
        tracker?.recycle()
        tracker = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    fun move(e: MotionEvent) {
        if (!dragging) return
        tracker?.addMovement(e)
        val dy = e.y - lastY
        lastY = e.y
        val resist = if (offset < 0f || offset > maxOffset) 0.45f else 1f
        offset -= dy * resist
    }

    fun end(e: MotionEvent?) {
        if (!dragging) return
        dragging = false
        tracker?.let {
            if (e != null) it.addMovement(e)
            it.computeCurrentVelocity(1000)
            velocity = -it.yVelocity
            it.recycle()
        }
        tracker = null
    }

    fun update(dt: Float) {
        if (dragging) return
        if (abs(velocity) > 1f) {
            offset += velocity * dt
            velocity *= 1f / (1f + 3.2f * dt)
        } else {
            velocity = 0f
        }
        // Spring back inside bounds.
        if (offset < 0f) {
            offset += (0f - offset) * minOf(1f, dt * 12f)
            if (velocity < 0f) velocity *= 0.5f
        } else if (offset > maxOffset) {
            offset += (maxOffset - offset) * minOf(1f, dt * 12f)
            if (velocity > 0f) velocity *= 0.5f
        }
    }

    fun scrollTo(y: Float) {
        offset = y.coerceIn(0f, maxOffset)
        velocity = 0f
    }
}

/** Draws [Button]s in the game's neon style. */
class ButtonRenderer(private val ui: UiKit) {
    private val r = RectF()

    fun draw(c: Canvas, b: Button) {
        if (!b.visible) return
        val u = ui.u
        val neon = ui.neon
        val press = Ease.outQuad(b.press)
        val scale = 1f - 0.05f * press + (if (b.pulse && b.enabled) 0.025f * (0.5f + 0.5f * sin(ui.time * 4.2f)) else 0f)
        r.set(b.rect)
        val cx = r.centerX()
        val cy = r.centerY()
        val hw = r.width() / 2f * scale
        val hh = r.height() / 2f * scale
        r.set(cx - hw, cy - hh, cx + hw, cy + hh)
        val alpha = if (b.enabled) 1f else 0.4f
        when (b.style) {
            Button.Style.PRIMARY -> {
                val radius = r.height() / 2f
                neon.glowBlob(c, cx, cy, r.width() * 0.62f, b.accent, 0.28f * alpha + 0.2f * press)
                neon.gradientRect(c, r, radius, Palette.mix(b.accent, Palette.WHITE, 0.08f + 0.2f * press), Palette.mix(b.accent, Palette.PURPLE, 0.55f), (255 * alpha).toInt())
                neon.glowStroke(c, r, radius, Palette.withAlpha(Palette.WHITE, 0.85f * alpha), 0.8f, 1.6f * u)
                // Dark label on the bright neon fill: maximum contrast for the main call to action.
                val p = ui.style(ui.displayPaint, 22f, Palette.withAlpha(Palette.BG_TOP, alpha), Paint.Align.CENTER)
                p.setShadowLayer(6f * u, 0f, 0f, Palette.withAlpha(Palette.WHITE, 0.55f * alpha))
                ui.fitText(c, b.label, cx, cy + p.textSize * 0.36f, p, r.width() * 0.84f)
                p.clearShadowLayer()
            }
            Button.Style.SECONDARY, Button.Style.DANGER -> {
                val radius = minOf(r.height() / 2f, 16f * u)
                val accent = if (b.style == Button.Style.DANGER) Palette.RED else b.accent
                neon.panel(c, r, radius, Palette.withAlpha(Palette.mix(Palette.PANEL, accent, 0.08f + 0.18f * press), 0.92f * alpha), Palette.withAlpha(accent, 0.9f * alpha), 0.7f + press)
                val icon = b.icon
                val label = ui.style(ui.textPaint, 16f, Palette.withAlpha(Palette.WHITE, alpha), Paint.Align.CENTER)
                if (icon != null && b.label.isNotEmpty()) {
                    val iconSize = minOf(r.height() * 0.46f, 24f * u)
                    if (r.width() > r.height() * 1.8f) {
                        ui.icons.draw(c, icon, r.left + r.height() * 0.5f + 4f * u, cy, iconSize, Palette.withAlpha(accent, alpha))
                        label.textAlign = Paint.Align.LEFT
                        ui.fitText(c, b.label, r.left + r.height() + 6f * u, cy + label.textSize * 0.35f, label, r.width() - r.height() - 14f * u)
                    } else {
                        ui.icons.draw(c, icon, cx, cy - r.height() * 0.13f, iconSize, Palette.withAlpha(accent, alpha))
                        label.textSize = 12.5f * u
                        ui.fitText(c, b.label, cx, r.bottom - r.height() * 0.14f, label, r.width() - 8f * u)
                    }
                } else if (icon != null) {
                    ui.icons.draw(c, icon, cx, cy, r.height() * 0.5f, Palette.withAlpha(accent, alpha))
                } else {
                    ui.fitText(c, b.label, cx, cy + label.textSize * 0.35f, label, r.width() - 16f * u)
                }
                b.sublabel?.let {
                    val sp = ui.style(ui.mediumPaint, 11f, Palette.withAlpha(Palette.DIM, alpha), Paint.Align.CENTER)
                    c.drawText(it, cx, r.bottom + 14f * u, sp)
                }
            }
            Button.Style.ICON -> {
                val radius = minOf(r.width(), r.height()) / 2f
                neon.circle(c, cx, cy, radius, Palette.withAlpha(Palette.mix(Palette.PANEL, b.accent, 0.12f + 0.25f * press), 0.9f * alpha))
                neon.circleStroke(c, cx, cy, radius, Palette.withAlpha(b.accent, 0.35f * alpha), 5f * u)
                neon.circleStroke(c, cx, cy, radius, Palette.withAlpha(b.accent, 0.9f * alpha), 1.5f * u)
                b.icon?.let { ui.icons.draw(c, it, cx, cy, radius * 0.95f, Palette.withAlpha(b.accent, alpha)) }
            }
            Button.Style.GHOST -> {
                val p = ui.style(ui.textPaint, 15f, Palette.withAlpha(if (press > 0.3f) Palette.WHITE else Palette.DIM, alpha), Paint.Align.CENTER)
                c.drawText(b.label, cx, cy + p.textSize * 0.35f, p)
            }
            Button.Style.TILE -> {
                val radius = 14f * u
                neon.panel(c, r, radius, Palette.withAlpha(Palette.mix(Palette.PANEL, b.accent, 0.1f + 0.2f * press), 0.9f * alpha), Palette.withAlpha(b.accent, 0.75f * alpha), 0.5f + press)
                b.icon?.let { ui.icons.draw(c, it, cx, r.top + r.height() * 0.38f, r.height() * 0.34f, Palette.withAlpha(b.accent, alpha)) }
                val p = ui.style(ui.textPaint, 12.5f, Palette.withAlpha(Palette.WHITE, alpha), Paint.Align.CENTER)
                ui.fitText(c, b.label, cx, r.bottom - r.height() * 0.16f, p, r.width() - 6f * u)
            }
            Button.Style.LINK -> {
                val radius = r.height() / 2f
                neon.panel(c, r, radius, Palette.withAlpha(Palette.mix(Palette.PANEL, b.accent, 0.15f + 0.2f * press), 0.9f), Palette.withAlpha(b.accent, 0.85f), 0.6f + press)
                val iconSize = r.height() * 0.52f
                b.icon?.let { ui.icons.draw(c, it, r.left + r.height() * 0.55f, cy, iconSize, b.accent, Palette.WHITE) }
                val tx = r.left + r.height() * 1.05f
                val p = ui.style(ui.textPaint, 14f, Palette.WHITE, Paint.Align.LEFT)
                val sub = b.sublabel
                if (sub != null) {
                    ui.fitText(c, b.label, tx, cy - 1f * u, p, r.right - tx - 10f * u)
                    val sp = ui.style(ui.mediumPaint, 11.5f, Palette.DIM, Paint.Align.LEFT)
                    ui.fitText(c, sub, tx, cy + 13f * u, sp, r.right - tx - 10f * u)
                } else {
                    ui.fitText(c, b.label, tx, cy + p.textSize * 0.35f, p, r.right - tx - 10f * u)
                }
            }
        }
        b.badge?.let { badge ->
            val bx = r.right - 6f * u
            val by = r.top + 6f * u
            val br = 9f * u
            neon.circle(c, bx, by, br, Palette.RED)
            val p = ui.style(ui.textPaint, 11f, Palette.WHITE, Paint.Align.CENTER)
            c.drawText(badge, bx, by + p.textSize * 0.36f, p)
        }
    }

    /** On/off switch drawn at the right side of [row]. */
    fun toggle(c: Canvas, row: RectF, on: Boolean, anim: Float) {
        val u = ui.u
        val w = 48f * u
        val h = 26f * u
        r.set(row.right - w - 12f * u, row.centerY() - h / 2f, row.right - 12f * u, row.centerY() + h / 2f)
        val color = Palette.mix(Palette.MUTED, Palette.CYAN, anim)
        ui.neon.panel(c, r, h / 2f, Palette.withAlpha(color, 0.35f), color, 0.4f + 0.6f * anim, 1.5f * u)
        val knobX = r.left + h / 2f + (w - h) * anim
        ui.neon.circle(c, knobX, r.centerY(), h / 2f - 4f * u, if (on) Palette.WHITE else Palette.DIM)
    }
}
