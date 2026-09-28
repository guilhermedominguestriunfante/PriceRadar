package com.dedonervoso.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

enum class Icon {
    COIN, STAR, STAR_OUTLINE, GEAR, TROPHY, USER, BOLT, HEART, HEART_EMPTY, SHIELD, PAUSE, PLAY, HOME,
    RETRY, NEXT, BACK, CLOSE, LOCK, CHECK, LINKEDIN, TARGET, FLAME, CLOCK, CROWN, SKULL, MUSIC, SOUND,
    VIBRATION, SPARKLE, GLOBE, TRASH, INFO, PODIUM, MEDAL, LIST, CALENDAR, UP, GRID, COMBO, HAND,
}

/**
 * Vector icons drawn in a unit box [-0.5, 0.5] and scaled at draw time. Geometry is built once;
 * drawing only transforms the canvas (no allocation).
 */
class Icons {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paths = HashMap<Icon, Path>()
    private val oval = RectF()

    private fun path(icon: Icon, build: Path.() -> Unit): Path = paths.getOrPut(icon) { Path().apply(build) }

    /** Draws [icon] centred at ([cx], [cy]) with the given pixel [size]. [accent] is used for details. */
    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int, accent: Int = Palette.BG_MID) {
        c.save()
        c.translate(cx, cy)
        c.scale(size, size)
        fill.color = color
        stroke.color = color
        stroke.strokeWidth = 0.09f
        when (icon) {
            Icon.COIN -> {
                c.drawCircle(0f, 0f, 0.46f, fill)
                fill.color = Palette.withAlpha(accent, 110)
                c.drawCircle(0f, 0f, 0.34f, fill)
                stroke.color = Palette.withAlpha(0xFFFFFFFF.toInt(), 170)
                stroke.strokeWidth = 0.05f
                c.drawCircle(0f, 0f, 0.34f, stroke)
                fill.color = color
                c.drawRect(-0.17f, -0.2f, 0.17f, -0.1f, fill)
                c.drawRect(-0.05f, -0.2f, 0.05f, 0.22f, fill)
            }
            Icon.STAR -> c.drawPath(starPath(), fill)
            Icon.STAR_OUTLINE -> {
                stroke.strokeWidth = 0.07f
                c.drawPath(starPath(), stroke)
            }
            Icon.GEAR -> {
                c.drawPath(path(icon) {
                    val teeth = 8
                    for (i in 0 until teeth * 2) {
                        val a0 = (i * Math.PI / teeth).toFloat()
                        val r = if (i % 2 == 0) 0.47f else 0.36f
                        val a1 = a0 + (Math.PI / teeth).toFloat()
                        val x0 = r * cos(a0)
                        val y0 = r * sin(a0)
                        if (i == 0) moveTo(x0, y0) else lineTo(x0, y0)
                        lineTo(r * cos(a1), r * sin(a1))
                    }
                    close()
                    addCircle(0f, 0f, 0.15f, Path.Direction.CCW)
                    fillType = Path.FillType.EVEN_ODD
                }, fill)
            }
            Icon.TROPHY -> {
                c.drawPath(path(icon) {
                    moveTo(-0.3f, -0.42f); lineTo(0.3f, -0.42f); lineTo(0.26f, -0.05f)
                    quadTo(0.2f, 0.12f, 0.06f, 0.14f); lineTo(0.06f, 0.26f); lineTo(0.2f, 0.28f)
                    lineTo(0.22f, 0.42f); lineTo(-0.22f, 0.42f); lineTo(-0.2f, 0.28f); lineTo(-0.06f, 0.26f)
                    lineTo(-0.06f, 0.14f); quadTo(-0.2f, 0.12f, -0.26f, -0.05f); close()
                }, fill)
                stroke.strokeWidth = 0.07f
                oval.set(-0.47f, -0.36f, -0.17f, -0.02f)
                c.drawArc(oval, 90f, 180f, false, stroke)
                oval.set(0.17f, -0.36f, 0.47f, -0.02f)
                c.drawArc(oval, -90f, 180f, false, stroke)
            }
            Icon.USER -> {
                c.drawCircle(0f, -0.18f, 0.18f, fill)
                oval.set(-0.36f, 0.06f, 0.36f, 0.66f)
                c.drawArc(oval, 180f, 180f, true, fill)
            }
            Icon.BOLT -> c.drawPath(path(icon) {
                moveTo(0.08f, -0.48f); lineTo(-0.3f, 0.06f); lineTo(-0.02f, 0.06f); lineTo(-0.1f, 0.48f)
                lineTo(0.3f, -0.08f); lineTo(0.02f, -0.08f); close()
            }, fill)
            Icon.HEART -> c.drawPath(heartPath(), fill)
            Icon.HEART_EMPTY -> {
                stroke.strokeWidth = 0.08f
                c.drawPath(heartPath(), stroke)
            }
            Icon.SHIELD -> c.drawPath(path(icon) {
                moveTo(0f, -0.46f); lineTo(0.38f, -0.3f); lineTo(0.36f, 0.02f)
                quadTo(0.3f, 0.34f, 0f, 0.48f); quadTo(-0.3f, 0.34f, -0.36f, 0.02f); lineTo(-0.38f, -0.3f); close()
            }, fill)
            Icon.PAUSE -> {
                c.drawRoundRect(-0.3f, -0.36f, -0.08f, 0.36f, 0.05f, 0.05f, fill)
                c.drawRoundRect(0.08f, -0.36f, 0.3f, 0.36f, 0.05f, 0.05f, fill)
            }
            Icon.PLAY -> c.drawPath(path(icon) {
                moveTo(-0.26f, -0.4f); lineTo(0.4f, 0f); lineTo(-0.26f, 0.4f); close()
            }, fill)
            Icon.HOME -> c.drawPath(path(icon) {
                moveTo(0f, -0.44f); lineTo(0.44f, -0.04f); lineTo(0.3f, -0.04f); lineTo(0.3f, 0.42f)
                lineTo(0.08f, 0.42f); lineTo(0.08f, 0.14f); lineTo(-0.08f, 0.14f); lineTo(-0.08f, 0.42f)
                lineTo(-0.3f, 0.42f); lineTo(-0.3f, -0.04f); lineTo(-0.44f, -0.04f); close()
            }, fill)
            Icon.RETRY -> {
                stroke.strokeWidth = 0.1f
                oval.set(-0.34f, -0.34f, 0.34f, 0.34f)
                c.drawArc(oval, -60f, 290f, false, stroke)
                c.drawPath(path(icon) {
                    moveTo(0.36f, -0.46f); lineTo(0.38f, -0.1f); lineTo(0.04f, -0.2f); close()
                }, fill)
            }
            Icon.NEXT -> {
                stroke.strokeWidth = 0.11f
                c.drawLine(-0.3f, -0.34f, 0.04f, 0f, stroke)
                c.drawLine(0.04f, 0f, -0.3f, 0.34f, stroke)
                c.drawLine(0.02f, -0.34f, 0.36f, 0f, stroke)
                c.drawLine(0.36f, 0f, 0.02f, 0.34f, stroke)
            }
            Icon.BACK -> {
                stroke.strokeWidth = 0.11f
                c.drawLine(0.36f, 0f, -0.34f, 0f, stroke)
                c.drawLine(-0.34f, 0f, -0.06f, -0.3f, stroke)
                c.drawLine(-0.34f, 0f, -0.06f, 0.3f, stroke)
            }
            Icon.CLOSE -> {
                stroke.strokeWidth = 0.11f
                c.drawLine(-0.3f, -0.3f, 0.3f, 0.3f, stroke)
                c.drawLine(0.3f, -0.3f, -0.3f, 0.3f, stroke)
            }
            Icon.LOCK -> {
                stroke.strokeWidth = 0.1f
                oval.set(-0.22f, -0.46f, 0.22f, 0.02f)
                c.drawArc(oval, 180f, 180f, false, stroke)
                c.drawLine(-0.22f, -0.22f, -0.22f, -0.04f, stroke)
                c.drawLine(0.22f, -0.22f, 0.22f, -0.04f, stroke)
                c.drawRoundRect(-0.34f, -0.06f, 0.34f, 0.44f, 0.08f, 0.08f, fill)
                fill.color = Palette.withAlpha(accent, 200)
                c.drawCircle(0f, 0.15f, 0.07f, fill)
            }
            Icon.CHECK -> {
                stroke.strokeWidth = 0.13f
                c.drawLine(-0.34f, 0.02f, -0.1f, 0.28f, stroke)
                c.drawLine(-0.1f, 0.28f, 0.36f, -0.26f, stroke)
            }
            Icon.LINKEDIN -> {
                c.drawRoundRect(-0.46f, -0.46f, 0.46f, 0.46f, 0.1f, 0.1f, fill)
                fill.color = accent
                c.drawCircle(-0.24f, -0.24f, 0.07f, fill)
                c.drawRect(-0.31f, -0.1f, -0.17f, 0.32f, fill)
                c.drawRect(-0.08f, -0.1f, 0.05f, 0.32f, fill)
                stroke.color = accent
                stroke.strokeWidth = 0.13f
                stroke.strokeCap = Paint.Cap.BUTT
                oval.set(-0.015f, -0.08f, 0.26f, 0.2f)
                c.drawArc(oval, 180f, 180f, false, stroke)
                c.drawLine(0.245f, 0.06f, 0.245f, 0.32f, stroke)
                stroke.strokeCap = Paint.Cap.ROUND
            }
            Icon.TARGET -> {
                stroke.strokeWidth = 0.08f
                c.drawCircle(0f, 0f, 0.4f, stroke)
                c.drawCircle(0f, 0f, 0.22f, stroke)
                c.drawCircle(0f, 0f, 0.07f, fill)
            }
            Icon.FLAME -> c.drawPath(path(icon) {
                moveTo(0f, -0.48f)
                cubicTo(0.18f, -0.26f, 0.4f, -0.1f, 0.34f, 0.16f)
                cubicTo(0.3f, 0.38f, 0.14f, 0.48f, 0f, 0.48f)
                cubicTo(-0.2f, 0.48f, -0.36f, 0.34f, -0.34f, 0.12f)
                cubicTo(-0.33f, -0.04f, -0.2f, -0.1f, -0.16f, -0.24f)
                cubicTo(-0.08f, -0.1f, -0.04f, -0.08f, 0f, -0.06f)
                cubicTo(0.05f, -0.2f, 0.04f, -0.32f, 0f, -0.48f)
                close()
            }, fill)
            Icon.CLOCK -> {
                stroke.strokeWidth = 0.09f
                c.drawCircle(0f, 0f, 0.42f, stroke)
                c.drawLine(0f, 0f, 0f, -0.24f, stroke)
                c.drawLine(0f, 0f, 0.18f, 0.1f, stroke)
            }
            Icon.CROWN -> c.drawPath(path(icon) {
                moveTo(-0.44f, -0.24f); lineTo(-0.22f, 0.02f); lineTo(0f, -0.36f); lineTo(0.22f, 0.02f)
                lineTo(0.44f, -0.24f); lineTo(0.36f, 0.3f); lineTo(-0.36f, 0.3f); close()
            }, fill)
            Icon.SKULL -> {
                c.drawPath(path(icon) {
                    addCircle(0f, -0.08f, 0.38f, Path.Direction.CW)
                    addRoundRect(-0.22f, 0.1f, 0.22f, 0.44f, 0.08f, 0.08f, Path.Direction.CW)
                }, fill)
                fill.color = accent
                c.drawCircle(-0.15f, -0.06f, 0.1f, fill)
                c.drawCircle(0.15f, -0.06f, 0.1f, fill)
                c.drawRect(-0.03f, 0.3f, 0.03f, 0.44f, fill)
                c.drawRect(-0.12f, 0.3f, -0.07f, 0.44f, fill)
                c.drawRect(0.07f, 0.3f, 0.12f, 0.44f, fill)
            }
            Icon.MUSIC -> {
                c.drawCircle(-0.2f, 0.26f, 0.14f, fill)
                c.drawCircle(0.26f, 0.16f, 0.14f, fill)
                c.drawPath(path(icon) {
                    moveTo(-0.1f, 0.26f); lineTo(-0.1f, -0.38f); lineTo(0.36f, -0.46f); lineTo(0.36f, 0.16f)
                    lineTo(0.28f, 0.16f); lineTo(0.28f, -0.26f); lineTo(-0.02f, -0.2f); lineTo(-0.02f, 0.26f); close()
                }, fill)
            }
            Icon.SOUND -> {
                c.drawPath(path(icon) {
                    moveTo(-0.44f, -0.14f); lineTo(-0.2f, -0.14f); lineTo(0.06f, -0.38f); lineTo(0.06f, 0.38f)
                    lineTo(-0.2f, 0.14f); lineTo(-0.44f, 0.14f); close()
                }, fill)
                stroke.strokeWidth = 0.08f
                oval.set(-0.1f, -0.22f, 0.3f, 0.22f)
                c.drawArc(oval, -60f, 120f, false, stroke)
                oval.set(-0.1f, -0.4f, 0.46f, 0.4f)
                c.drawArc(oval, -55f, 110f, false, stroke)
            }
            Icon.VIBRATION -> {
                c.drawRoundRect(-0.17f, -0.4f, 0.17f, 0.4f, 0.07f, 0.07f, fill)
                stroke.strokeWidth = 0.07f
                c.drawLine(-0.3f, -0.2f, -0.3f, 0.2f, stroke)
                c.drawLine(0.3f, -0.2f, 0.3f, 0.2f, stroke)
                c.drawLine(-0.42f, -0.12f, -0.42f, 0.12f, stroke)
                c.drawLine(0.42f, -0.12f, 0.42f, 0.12f, stroke)
            }
            Icon.SPARKLE -> c.drawPath(path(icon) {
                moveTo(0f, -0.48f); quadTo(0.06f, -0.06f, 0.48f, 0f); quadTo(0.06f, 0.06f, 0f, 0.48f)
                quadTo(-0.06f, 0.06f, -0.48f, 0f); quadTo(-0.06f, -0.06f, 0f, -0.48f); close()
            }, fill)
            Icon.GLOBE -> {
                stroke.strokeWidth = 0.07f
                c.drawCircle(0f, 0f, 0.42f, stroke)
                oval.set(-0.18f, -0.42f, 0.18f, 0.42f)
                c.drawOval(oval, stroke)
                c.drawLine(-0.42f, 0f, 0.42f, 0f, stroke)
            }
            Icon.TRASH -> {
                c.drawRoundRect(-0.3f, -0.2f, 0.3f, 0.44f, 0.06f, 0.06f, fill)
                c.drawRect(-0.38f, -0.34f, 0.38f, -0.26f, fill)
                c.drawRect(-0.1f, -0.44f, 0.1f, -0.34f, fill)
            }
            Icon.INFO -> {
                stroke.strokeWidth = 0.08f
                c.drawCircle(0f, 0f, 0.42f, stroke)
                c.drawCircle(0f, -0.2f, 0.06f, fill)
                c.drawRoundRect(-0.05f, -0.06f, 0.05f, 0.26f, 0.03f, 0.03f, fill)
            }
            Icon.PODIUM -> {
                c.drawRect(-0.13f, -0.26f, 0.13f, 0.4f, fill)
                c.drawRect(-0.44f, -0.02f, -0.16f, 0.4f, fill)
                c.drawRect(0.16f, 0.1f, 0.44f, 0.4f, fill)
            }
            Icon.MEDAL -> {
                c.drawPath(path(icon) {
                    moveTo(-0.28f, -0.46f); lineTo(-0.08f, -0.46f); lineTo(0.08f, -0.12f); lineTo(-0.08f, -0.08f); close()
                    moveTo(0.28f, -0.46f); lineTo(0.08f, -0.46f); lineTo(-0.06f, -0.14f); lineTo(0.1f, -0.08f); close()
                }, fill)
                c.drawCircle(0f, 0.16f, 0.3f, fill)
                fill.color = Palette.withAlpha(accent, 150)
                c.drawCircle(0f, 0.16f, 0.18f, fill)
            }
            Icon.LIST -> {
                for (i in 0..2) {
                    val y = -0.3f + i * 0.3f
                    c.drawCircle(-0.32f, y, 0.07f, fill)
                    c.drawRoundRect(-0.18f, y - 0.05f, 0.42f, y + 0.05f, 0.04f, 0.04f, fill)
                }
            }
            Icon.CALENDAR -> {
                stroke.strokeWidth = 0.08f
                c.drawRoundRect(-0.4f, -0.32f, 0.4f, 0.42f, 0.08f, 0.08f, stroke)
                c.drawRect(-0.4f, -0.32f, 0.4f, -0.12f, fill)
                c.drawLine(-0.2f, -0.46f, -0.2f, -0.28f, stroke)
                c.drawLine(0.2f, -0.46f, 0.2f, -0.28f, stroke)
                for (i in 0..2) for (j in 0..1) c.drawCircle(-0.2f + i * 0.2f, 0.06f + j * 0.18f, 0.045f, fill)
            }
            Icon.UP -> c.drawPath(path(icon) {
                moveTo(0f, -0.46f); lineTo(0.4f, 0f); lineTo(0.16f, 0f); lineTo(0.16f, 0.44f)
                lineTo(-0.16f, 0.44f); lineTo(-0.16f, 0f); lineTo(-0.4f, 0f); close()
            }, fill)
            Icon.GRID -> {
                for (i in 0..1) for (j in 0..1) {
                    val x = -0.4f + i * 0.46f
                    val y = -0.4f + j * 0.46f
                    c.drawRoundRect(x, y, x + 0.34f, y + 0.34f, 0.06f, 0.06f, fill)
                }
            }
            Icon.COMBO -> {
                stroke.strokeWidth = 0.12f
                c.drawLine(-0.3f, -0.3f, 0.3f, 0.3f, stroke)
                c.drawLine(0.3f, -0.3f, -0.3f, 0.3f, stroke)
                c.drawCircle(0.34f, -0.36f, 0.08f, fill)
            }
            Icon.HAND -> c.drawPath(path(icon) {
                // Raised open hand (STOP symbol).
                addRoundRect(-0.28f, -0.1f, 0.26f, 0.46f, 0.16f, 0.16f, Path.Direction.CW)
                addRoundRect(-0.26f, -0.36f, -0.14f, 0.1f, 0.06f, 0.06f, Path.Direction.CW)
                addRoundRect(-0.12f, -0.46f, 0.0f, 0.0f, 0.06f, 0.06f, Path.Direction.CW)
                addRoundRect(0.02f, -0.42f, 0.14f, 0.0f, 0.06f, 0.06f, Path.Direction.CW)
                addRoundRect(0.16f, -0.3f, 0.27f, 0.05f, 0.06f, 0.06f, Path.Direction.CW)
                addRoundRect(0.18f, 0.02f, 0.46f, 0.14f, 0.06f, 0.06f, Path.Direction.CW)
            }, fill)
        }
        c.restore()
    }

    private fun starPath(): Path = paths.getOrPut(Icon.STAR) {
        Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.5f else 0.22f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val x = (r * Math.cos(a)).toFloat()
                val y = (r * Math.sin(a)).toFloat() + 0.03f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    private fun heartPath(): Path = paths.getOrPut(Icon.HEART) {
        Path().apply {
            moveTo(0f, 0.42f)
            cubicTo(-0.5f, 0.06f, -0.46f, -0.44f, -0.12f, -0.36f)
            cubicTo(-0.04f, -0.34f, 0f, -0.26f, 0f, -0.22f)
            cubicTo(0f, -0.26f, 0.04f, -0.34f, 0.12f, -0.36f)
            cubicTo(0.46f, -0.44f, 0.5f, 0.06f, 0f, 0.42f)
            close()
        }
    }
}
