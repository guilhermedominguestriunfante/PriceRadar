package com.dedonervoso.app.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import com.dedonervoso.app.platform.Endpoints
import com.dedonervoso.core.engine.BossAttack
import com.dedonervoso.core.engine.BossKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * The bosses on screen. When an illustration `assets/art/boss_<kind>.webp` ships (transparent
 * background, the boss filling the square), it is drawn and animated (squash on hits, white flash,
 * red rage tint); otherwise each boss is drawn in neon vector art. Reuses every paint, path and
 * shader: nothing allocates per frame.
 */
class BossArt(private val ui: UiKit, private val assets: AssetManager) {

    /** How the boss looks this frame (set by the play screen). */
    class Look {
        /** 1 right after a hit, decaying to 0. */
        var hit = 0f
        var rage = 0
        var shield = false
        var warning: BossAttack? = null
        var charging = false
        /** 0..1: how open the mouth is (roaring during STOP warnings and holds). */
        var roar = 0f
        var time = 0f
        var reduce = false
        /** Blackout: only the glowing eyes show. */
        var eyesOnly = false
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val flashFilter = PorterDuffColorFilter(Palette.WHITE, PorterDuff.Mode.SRC_ATOP)
    private val rageFilter = PorterDuffColorFilter(0x66FF1E1E, PorterDuff.Mode.SRC_ATOP)
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    private val gradients = HashMap<Long, LinearGradient>()
    private val bitmaps = HashMap<BossKind, Bitmap?>()

    fun color(kind: BossKind): Int = when (kind) {
        BossKind.FURIOSO -> Palette.RED
        BossKind.PUNHO -> Palette.BLUE
        BossKind.CRONOMETRO -> Palette.GOLD
        BossKind.GLITCH -> Palette.CYAN
        BossKind.REI -> Palette.PURPLE
    }

    fun attackColor(a: BossAttack): Int = when (a) {
        BossAttack.SHIELD -> Palette.CYAN
        BossAttack.CHARGE -> Palette.ORANGE
        BossAttack.BLACKOUT -> Palette.PURPLE
        BossAttack.CLOCK -> Palette.GOLD
        BossAttack.TELEPORT -> Palette.MAGENTA
    }

    /** Illustration for [kind], loaded once (null when the build ships none). */
    fun illustration(kind: BossKind): Bitmap? = bitmaps.getOrPut(kind) {
        try {
            Endpoints.openArt(assets, "art/boss_${kind.name.lowercase()}.webp").use { BitmapFactory.decodeStream(it) }
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** Draws the boss centred at ([cx], [cy]) with hit radius [r] (pixels). */
    fun draw(c: Canvas, kind: BossKind, tier: Int, cx: Float, cy: Float, r: Float, look: Look) {
        val glowColor = if (look.rage > 0) Palette.mix(color(kind), Palette.RED, 0.5f + 0.25f * look.rage) else color(kind)
        val warn = look.warning
        if (!look.eyesOnly) {
            // Ground shadow and aura.
            fill.color = Palette.withAlpha(0xFF000000.toInt(), 0.35f)
            rect.set(cx - r * 0.9f, cy + r * 0.95f, cx + r * 0.9f, cy + r * 1.2f)
            c.drawOval(rect, fill)
            val pulse = if (look.reduce) 0.5f else 0.5f + 0.5f * sin(look.time * (if (warn != null) 18f else 4f))
            ui.neon.glowBlob(c, cx, cy, r * (2.1f + 0.2f * pulse), if (warn != null) attackColor(warn) else glowColor, 0.28f + 0.2f * pulse + 0.08f * look.rage)
        }
        c.save()
        // Squash on hits, a nervous tremble when angry, stretched while charging.
        val squash = look.hit * 0.14f
        val tremble = if (look.reduce || look.rage == 0) 0f else sin(look.time * 60f) * r * 0.015f * look.rage
        c.translate(cx + tremble, cy)
        if (look.charging) c.scale(1.18f, 0.88f) else c.scale(1f + squash, 1f - squash)
        if (tier > 0) c.scale(1f + 0.04f * tier.coerceAtMost(3), 1f + 0.04f * tier.coerceAtMost(3))
        val art = illustration(kind)
        if (art != null && !look.eyesOnly) {
            drawBitmap(c, art, r, look)
        } else {
            when (kind) {
                BossKind.FURIOSO -> furioso(c, r, look)
                BossKind.PUNHO -> punho(c, r, look)
                BossKind.CRONOMETRO -> cronometro(c, r, look)
                BossKind.GLITCH -> glitch(c, r, look)
                BossKind.REI -> rei(c, r, look)
            }
        }
        c.restore()
        if (look.shield && !look.eyesOnly) shieldBubble(c, cx, cy, r, look)
        if (warn != null && !look.eyesOnly) {
            val p = ui.style(ui.displayPaint, 30f, Palette.WHITE, Paint.Align.CENTER)
            ui.neon.glowText(c, "!", cx, cy - r * 1.35f, p, attackColor(warn), 12f * ui.u)
        }
    }

    private fun drawBitmap(c: Canvas, art: Bitmap, r: Float, look: Look) {
        // The illustration is drawn a bit larger than the hit circle: claws and spikes stick out.
        val half = r * 1.55f
        rect.set(-half, -half, half, half)
        bitmapPaint.colorFilter = null
        bitmapPaint.alpha = 255
        c.drawBitmap(art, null, rect, bitmapPaint)
        if (look.rage > 0) {
            bitmapPaint.colorFilter = rageFilter
            bitmapPaint.alpha = 60 + 50 * look.rage
            c.drawBitmap(art, null, rect, bitmapPaint)
        }
        if (look.hit > 0.01f) {
            bitmapPaint.colorFilter = flashFilter
            bitmapPaint.alpha = (look.hit * 170).toInt()
            c.drawBitmap(art, null, rect, bitmapPaint)
        }
        bitmapPaint.colorFilter = null
    }

    // ---- vector bosses (drawn around the origin, radius r) --------------------------------------

    /** Vertical gradient fill of [rect] (a cached unit shader, mapped with a local matrix). */
    private fun gradientFill(top: Int, bottom: Int, r: RectF) {
        val key = (top.toLong() shl 32) xor (bottom.toLong() and 0xFFFFFFFFL)
        val g = gradients.getOrPut(key) { LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP) }
        matrix.setScale(1f, r.height())
        matrix.postTranslate(0f, r.top)
        g.setLocalMatrix(matrix)
        fill.shader = g
    }

    private inline fun flash(look: Look, drawShape: () -> Unit) {
        if (look.hit <= 0.01f) return
        fill.shader = null
        fill.color = Palette.withAlpha(Palette.WHITE, look.hit * 0.6f)
        drawShape()
    }

    /** Two angry eyes: white, a glowing iris and heavy brows. Also drawn alone in a blackout. */
    private fun eyes(c: Canvas, x: Float, y: Float, gap: Float, size: Float, iris: Int, look: Look, pixel: Boolean = false) {
        for (side in SIDES) {
            val ex = x + side * gap
            if (!look.eyesOnly) {
                fill.shader = null
                fill.color = 0xFFF8F4EA.toInt()
                if (pixel) c.drawRect(ex - size, y - size * 0.7f, ex + size, y + size * 0.7f, fill) else c.drawOval(ex - size, y - size * 0.72f, ex + size, y + size * 0.72f, fill)
            }
            ui.neon.glowBlob(c, ex, y, size * 1.6f, iris, if (look.eyesOnly) 0.9f else 0.5f)
            fill.color = iris
            if (pixel) c.drawRect(ex - size * 0.42f, y - size * 0.42f, ex + size * 0.42f, y + size * 0.42f, fill) else c.drawCircle(ex, y, size * 0.45f, fill)
            if (look.eyesOnly) continue
            fill.color = 0xFF14080A.toInt()
            c.drawCircle(ex, y, size * 0.2f, fill)
            // Brow slanting down to the middle.
            stroke.color = 0xFF1A0E10.toInt()
            stroke.strokeWidth = size * 0.42f
            c.drawLine(ex - side * size * 1.15f, y - size * 1.25f - look.rage * size * 0.08f, ex + side * size * 0.9f, y - size * 0.75f, stroke)
        }
    }

    /** A mouth that opens with [Look.roar]: dark inside, a row of teeth on top, tongue below. */
    private fun mouth(c: Canvas, x: Float, y: Float, w: Float, look: Look) {
        if (look.eyesOnly) return
        val open = 0.25f + 0.75f * look.roar
        val h = w * 0.45f * open
        fill.shader = null
        fill.color = 0xFF2A060C.toInt()
        rect.set(x - w / 2f, y - h * 0.3f, x + w / 2f, y + h)
        c.drawRoundRect(rect, w * 0.2f, w * 0.2f, fill)
        if (look.roar > 0.3f) {
            fill.color = 0xFFD8505E.toInt()
            rect.set(x - w * 0.22f, y + h * 0.45f, x + w * 0.22f, y + h * 0.98f)
            c.drawOval(rect, fill)
        }
        fill.color = 0xFFF2EEDC.toInt()
        val teeth = 6
        val tw = w / teeth
        for (i in 0 until teeth) {
            path.reset()
            val tx = x - w / 2f + i * tw
            path.moveTo(tx + tw * 0.08f, y - h * 0.3f)
            path.lineTo(tx + tw * 0.92f, y - h * 0.3f)
            path.lineTo(tx + tw * 0.5f, y - h * 0.3f + tw * 0.9f)
            path.close()
            c.drawPath(path, fill)
        }
    }

    private fun furioso(c: Canvas, r: Float, look: Look) {
        if (!look.eyesOnly) {
            // The finger: a tall capsule with a nail, wearing its cap backwards.
            rect.set(-r * 0.62f, -r * 1.02f, r * 0.62f, r * 1.02f)
            gradientFill(0xFFEE7A60.toInt(), 0xFF8C1C26.toInt(), rect)
            c.drawRoundRect(rect, r * 0.62f, r * 0.62f, fill)
            flash(look) { c.drawRoundRect(-r * 0.62f, -r * 1.02f, r * 0.62f, r * 1.02f, r * 0.62f, r * 0.62f, fill) }
            fill.shader = null
            // Knuckle creases.
            stroke.color = 0x66601018
            stroke.strokeWidth = r * 0.03f
            for (k in 0 until 3) c.drawLine(-r * 0.3f, r * (0.36f + k * 0.08f), r * 0.3f, r * (0.36f + k * 0.08f), stroke)
            // Nail.
            fill.color = 0xFFFFC7B8.toInt()
            rect.set(-r * 0.38f, r * 0.55f, r * 0.38f, r * 0.98f)
            c.drawRoundRect(rect, r * 0.3f, r * 0.3f, fill)
            // Cap: dark dome and a red brim to the side.
            fill.color = 0xFF16161C.toInt()
            rect.set(-r * 0.66f, -r * 1.16f, r * 0.66f, -r * 0.52f)
            c.drawArc(rect, 180f, 180f, true, fill)
            fill.color = Palette.RED
            rect.set(r * 0.25f, -r * 0.9f, r * 1.05f, -r * 0.74f)
            c.drawRoundRect(rect, r * 0.08f, r * 0.08f, fill)
            ui.icons.draw(c, Icon.CROWN, 0f, -r * 0.93f, r * 0.34f, Palette.GOLD)
        }
        eyes(c, 0f, -r * 0.42f, r * 0.26f, r * 0.17f, 0xFFFF3A1E.toInt(), look)
        mouth(c, 0f, -r * 0.04f, r * 0.72f, look)
    }

    private fun punho(c: Canvas, r: Float, look: Look) {
        if (!look.eyesOnly) {
            // A steel fist: knuckles on top, rivets, a visor slit for eyes.
            rect.set(-r * 0.92f, -r * 0.6f, r * 0.92f, r * 0.95f)
            gradientFill(0xFFC8D2E4.toInt(), 0xFF3C4660.toInt(), rect)
            c.drawRoundRect(rect, r * 0.32f, r * 0.32f, fill)
            for (k in 0 until 4) {
                val kx = -r * 0.69f + k * r * 0.46f
                rect.set(kx - r * 0.24f, -r * 0.92f, kx + r * 0.24f, -r * 0.36f)
                gradientFill(0xFFE4EAF4.toInt(), 0xFF7A869E.toInt(), rect)
                c.drawRoundRect(rect, r * 0.2f, r * 0.2f, fill)
            }
            flash(look) { c.drawRoundRect(-r * 0.92f, -r * 0.92f, r * 0.92f, r * 0.95f, r * 0.3f, r * 0.3f, fill) }
            fill.shader = null
            fill.color = 0xFF2A3246.toInt()
            for (k in 0 until 4) c.drawCircle(-r * 0.7f + k * r * 0.46f, r * 0.78f, r * 0.05f, fill)
            fill.color = 0xFF0C0E16.toInt()
            rect.set(-r * 0.7f, -r * 0.28f, r * 0.7f, r * 0.02f)
            c.drawRoundRect(rect, r * 0.1f, r * 0.1f, fill)
        }
        // Visor eyes: two glowing red slits.
        for (side in SIDES) {
            val ex = side * r * 0.3f
            ui.neon.glowBlob(c, ex, -r * 0.13f, r * 0.3f, Palette.RED, if (look.eyesOnly) 0.9f else 0.6f)
            fill.shader = null
            fill.color = 0xFFFF4A4A.toInt()
            rect.set(ex - r * 0.2f, -r * 0.17f, ex + r * 0.2f, -r * 0.09f)
            c.drawRoundRect(rect, r * 0.04f, r * 0.04f, fill)
        }
        mouth(c, 0f, r * 0.3f, r * 0.8f, look)
    }

    private fun cronometro(c: Canvas, r: Float, look: Look) {
        if (!look.eyesOnly) {
            // A golden stopwatch: crown button, ticks, racing hands.
            fill.shader = null
            fill.color = 0xFFB88A1C.toInt()
            rect.set(-r * 0.16f, -r * 1.2f, r * 0.16f, -r * 0.9f)
            c.drawRoundRect(rect, r * 0.05f, r * 0.05f, fill)
            rect.set(-r, -r, r, r)
            gradientFill(0xFFFFE27A.toInt(), 0xFFB07A14.toInt(), rect)
            c.drawCircle(0f, 0f, r, fill)
            flash(look) { c.drawCircle(0f, 0f, r, fill) }
            fill.shader = null
            fill.color = 0xFFFFF6DC.toInt()
            c.drawCircle(0f, 0f, r * 0.82f, fill)
            stroke.color = 0xFF6A4A10.toInt()
            stroke.strokeWidth = r * 0.04f
            for (k in 0 until 12) {
                val a = k * (Math.PI.toFloat() / 6f)
                c.drawLine(cos(a) * r * 0.7f, sin(a) * r * 0.7f, cos(a) * r * 0.78f, sin(a) * r * 0.78f, stroke)
            }
            val speed = if (look.reduce) 1f else 3f + 3f * look.rage
            stroke.strokeWidth = r * 0.05f
            val a1 = look.time * speed
            c.drawLine(0f, 0f, cos(a1) * r * 0.62f, sin(a1) * r * 0.62f, stroke)
            val a2 = look.time * speed * 0.12f
            c.drawLine(0f, 0f, cos(a2) * r * 0.42f, sin(a2) * r * 0.42f, stroke)
        }
        eyes(c, 0f, -r * 0.3f, r * 0.3f, r * 0.15f, 0xFFFF6A00.toInt(), look)
        mouth(c, 0f, r * 0.2f, r * 0.7f, look)
    }

    private fun glitch(c: Canvas, r: Float, look: Look) {
        val jitter = if (look.reduce) 0f else r * 0.05f * (1f + look.rage) * sin(look.time * 37f)
        if (!look.eyesOnly) {
            // RGB split: a cyan and a magenta copy offset around the body.
            fill.shader = null
            val split = r * 0.07f + jitter
            fill.color = Palette.withAlpha(Palette.CYAN, 0.45f)
            c.drawRect(-r * 0.85f - split, -r * 0.85f, r * 0.85f - split, r * 0.85f, fill)
            fill.color = Palette.withAlpha(Palette.MAGENTA, 0.45f)
            c.drawRect(-r * 0.85f + split, -r * 0.85f, r * 0.85f + split, r * 0.85f, fill)
            fill.color = 0xFF0E1330.toInt()
            c.drawRect(-r * 0.85f, -r * 0.85f, r * 0.85f, r * 0.85f, fill)
            flash(look) { c.drawRect(-r * 0.85f, -r * 0.85f, r * 0.85f, r * 0.85f, fill) }
            fill.shader = null
            // Broken pixels.
            val cell = r * 0.17f
            for (i in 0 until 10) {
                for (j in 0 until 10) {
                    val h = (i * 73 + j * 151 + (look.time * 8f).toInt() * 17) % 23
                    if (h > 3) continue
                    fill.color = Palette.withAlpha(if (h % 2 == 0) Palette.CYAN else Palette.MAGENTA, 0.6f)
                    c.drawRect(-r * 0.85f + i * cell, -r * 0.85f + j * cell, -r * 0.85f + (i + 1) * cell, -r * 0.85f + (j + 1) * cell, fill)
                }
            }
            stroke.color = Palette.CYAN
            stroke.strokeWidth = r * 0.04f
            c.drawRect(-r * 0.85f, -r * 0.85f, r * 0.85f, r * 0.85f, stroke)
        }
        eyes(c, jitter * 0.5f, -r * 0.3f, r * 0.32f, r * 0.16f, Palette.CYAN, look, pixel = true)
        if (!look.eyesOnly) {
            // A jagged grin.
            stroke.color = Palette.MAGENTA
            stroke.strokeWidth = r * 0.06f
            path.reset()
            path.moveTo(-r * 0.45f, r * 0.3f)
            for (k in 1..6) path.lineTo(-r * 0.45f + k * r * 0.15f, r * (if (k % 2 == 0) 0.3f else 0.42f + 0.1f * look.roar))
            c.drawPath(path, stroke)
        }
    }

    private fun rei(c: Canvas, r: Float, look: Look) {
        if (!look.eyesOnly) {
            // Cape behind, a round purple king with a golden crown.
            fill.shader = null
            fill.color = 0xFF4A1470.toInt()
            path.reset()
            path.moveTo(-r * 0.5f, -r * 0.2f)
            path.lineTo(r * 0.5f, -r * 0.2f)
            path.lineTo(r * 1.1f, r * 1.05f)
            path.lineTo(-r * 1.1f, r * 1.05f)
            path.close()
            c.drawPath(path, fill)
            rect.set(-r * 0.9f, -r * 0.75f, r * 0.9f, r * 0.95f)
            gradientFill(0xFFB57BFF.toInt(), 0xFF3A0E66.toInt(), rect)
            c.drawOval(rect, fill)
            flash(look) { c.drawOval(-r * 0.9f, -r * 0.75f, r * 0.9f, r * 0.95f, fill) }
            fill.shader = null
            fill.color = Palette.GOLD
            path.reset()
            path.moveTo(-r * 0.6f, -r * 0.6f)
            path.lineTo(-r * 0.6f, -r * 1.15f)
            path.lineTo(-r * 0.3f, -r * 0.85f)
            path.lineTo(0f, -r * 1.3f)
            path.lineTo(r * 0.3f, -r * 0.85f)
            path.lineTo(r * 0.6f, -r * 1.15f)
            path.lineTo(r * 0.6f, -r * 0.6f)
            path.close()
            c.drawPath(path, fill)
            fill.color = Palette.RED
            c.drawCircle(0f, -r * 0.8f, r * 0.08f, fill)
            fill.color = Palette.CYAN
            c.drawCircle(-r * 0.4f, -r * 0.72f, r * 0.05f, fill)
            c.drawCircle(r * 0.4f, -r * 0.72f, r * 0.05f, fill)
        }
        eyes(c, 0f, -r * 0.15f, r * 0.3f, r * 0.16f, Palette.GOLD, look)
        mouth(c, 0f, r * 0.3f, r * 0.72f, look)
    }

    private fun shieldBubble(c: Canvas, cx: Float, cy: Float, r: Float, look: Look) {
        val rr = r * 1.4f
        fill.shader = null
        fill.color = Palette.withAlpha(Palette.CYAN, 0.12f)
        c.drawCircle(cx, cy, rr, fill)
        ui.neon.circleStroke(c, cx, cy, rr, Palette.withAlpha(Palette.CYAN, 0.9f), 3f * ui.u)
        // Rotating hexagon segments.
        stroke.color = Palette.withAlpha(Palette.WHITE, 0.7f)
        stroke.strokeWidth = 2f * ui.u
        val spin = if (look.reduce) 0f else look.time * 1.5f
        for (k in 0 until 6) {
            val a0 = spin + k * (Math.PI.toFloat() / 3f)
            val a1 = a0 + 0.5f
            c.drawLine(cx + cos(a0) * rr, cy + sin(a0) * rr, cx + cos(a1) * rr, cy + sin(a1) * rr, stroke)
        }
    }

    // ---- health bar -------------------------------------------------------------------------------

    private var barLoaded = false
    private var barFrame: Bitmap? = null
    private val clip = Path()
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE8120408.toInt() }

    /** The illustrated frame (portrait ring + bar), or null when the build ships none. */
    private fun frame(): Bitmap? {
        if (!barLoaded) {
            barLoaded = true
            barFrame = try {
                Endpoints.openArt(assets, "art/boss_bar.webp").use { BitmapFactory.decodeStream(it) }
            } catch (e: java.io.IOException) {
                null
            }
        }
        return barFrame
    }

    /** Width ÷ height of the health bar as drawn by [healthBar] ([BAR_ASPECT] with the frame). */
    val barAspect: Float get() = if (frame() != null) BAR_ASPECT else 12f

    /**
     * The boss's health bar in [r]: with the illustrated frame, the boss's face in its ring and the
     * molten bar going dark from the right as health drops; otherwise a neon bar with the name above.
     */
    fun healthBar(c: Canvas, r: RectF, fraction: Float, name: String, kind: BossKind, rage: Int, shake: Float) {
        val u = ui.u
        val f = fraction.coerceIn(0f, 1f)
        val art = frame()
        c.save()
        c.translate(shake, 0f)
        if (art == null) {
            val p = ui.style(ui.displayBoldPaint, 12f, Palette.withAlpha(Palette.WHITE, 0.95f), Paint.Align.CENTER)
            p.letterSpacing = 0.12f
            ui.fitText(c, name, r.centerX(), r.top - 6f * u, p, r.width())
            p.letterSpacing = 0f
            ui.neon.panel(c, r, r.height() / 2f, 0xCC14060E.toInt(), Palette.withAlpha(if (rage > 0) Palette.RED else color(kind), 0.9f), 0.8f)
            rect.set(r.left + 2f * u, r.top + 2f * u, r.left + 2f * u + (r.width() - 4f * u) * f, r.bottom - 2f * u)
            if (rect.width() > 0f) ui.neon.gradientRect(c, rect, rect.height() / 2f, 0xFFFF1959.toInt(), 0xFFFFCA3A.toInt())
            c.restore()
            return
        }
        val w = r.width()
        val h = r.height()
        // The boss's face behind the frame's ring.
        val pr = w * PORTRAIT_R
        val pcx = r.left + w * PORTRAIT_X
        val pcy = r.top + h * PORTRAIT_Y
        fill.shader = null
        fill.color = 0xFF14060E.toInt()
        c.drawCircle(pcx, pcy, pr, fill)
        illustration(kind)?.let { boss ->
            c.save()
            clip.reset()
            clip.addCircle(pcx, pcy, pr, Path.Direction.CW)
            c.clipPath(clip)
            val face = FACES[kind.ordinal]
            val k = pr * 1.1f / (face[2] * boss.width)
            rect.set(pcx - face[0] * boss.width * k, pcy - face[1] * boss.height * k, 0f, 0f)
            rect.right = rect.left + boss.width * k
            rect.bottom = rect.top + boss.height * k
            bitmapPaint.colorFilter = if (rage > 0) rageFilter else null
            bitmapPaint.alpha = 255
            c.drawBitmap(boss, null, rect, bitmapPaint)
            bitmapPaint.colorFilter = null
            c.restore()
        }
        rect.set(r)
        bitmapPaint.alpha = 255
        c.drawBitmap(art, null, rect, bitmapPaint)
        // Lost health: the bar goes dark from the right.
        val left = r.left + w * FILL_LEFT
        val right = r.left + w * FILL_RIGHT
        val cut = left + (right - left) * f
        if (cut < right) {
            rect.set(cut, r.top + h * FILL_TOP, right, r.top + h * FILL_BOTTOM)
            c.drawRoundRect(rect, h * 0.04f, h * 0.04f, dark)
        }
        // The name on the bar, readable over the fill.
        val p = ui.style(ui.displayBoldPaint, 1f, Palette.WHITE, Paint.Align.CENTER)
        p.textSize = h * 0.11f
        p.letterSpacing = 0.1f
        ui.fitSize(p, name, (right - left) * 0.9f)
        val ty = r.top + h * (FILL_TOP + FILL_BOTTOM) / 2f + p.textSize * 0.36f
        ui.neon.glowText(c, name, (left + right) / 2f, ty, p, 0xFF000000.toInt(), 6f * u)
        p.letterSpacing = 0f
        c.restore()
    }

    private companion object {
        val SIDES = intArrayOf(-1, 1)

        // Geometry of assets/art/boss_bar.webp (fractions of its width/height).
        const val BAR_ASPECT = 3f
        const val PORTRAIT_X = 0.146f
        const val PORTRAIT_Y = 0.486f
        const val PORTRAIT_R = 0.071f
        const val FILL_LEFT = 0.279f
        const val FILL_RIGHT = 0.822f
        const val FILL_TOP = 0.41f
        const val FILL_BOTTOM = 0.614f

        /** Face of each boss illustration: centre x, centre y and radius, as fractions of the image. */
        val FACES = arrayOf(
            floatArrayOf(0.63f, 0.25f, 0.17f), // FURIOSO
            floatArrayOf(0.53f, 0.47f, 0.18f), // PUNHO
            floatArrayOf(0.53f, 0.27f, 0.15f), // CRONOMETRO
            floatArrayOf(0.73f, 0.3f, 0.16f), // GLITCH
            floatArrayOf(0.68f, 0.27f, 0.17f), // REI
        )
    }
}
