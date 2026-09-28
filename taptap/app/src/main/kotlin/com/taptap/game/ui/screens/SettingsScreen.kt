package com.taptap.game.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.taptap.game.GameApp
import com.taptap.game.core.save.Settings
import com.taptap.game.ui.Button
import com.taptap.game.ui.Dialog
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.ScrollArea
import com.taptap.game.ui.Screen

/** Settings (spec §51): audio, vibration, reduced effects, language, reset, about. */
class SettingsScreen(app: GameApp) : Screen(app) {
    private lateinit var scroll: ScrollArea
    private var savedOffset = 0f

    private class Toggle(val label: String, val icon: Icon, val get: (Settings) -> Boolean, val set: (Settings, Boolean) -> Unit) {
        val row = RectF()
        var anim = 0f
    }

    private val toggles = ArrayList<Toggle>()
    private var aboutTop = 0f
    private val langRow = RectF()

    override fun layout() {
        val top = header(s.settings)
        scroll = scrollArea()
        scroll.rect.set(safe.left, top, safe.right, safe.bottom)
        toggles.clear()
        toggles += Toggle(s.music, Icon.MUSIC, { it.music }, { st, v -> st.music = v })
        toggles += Toggle(s.sfx, Icon.SOUND, { it.sfx }, { st, v -> st.sfx = v })
        toggles += Toggle(s.vibration, Icon.VIBRATION, { it.vibration }, { st, v -> st.vibration = v })
        toggles += Toggle(s.reduceEffects, Icon.SPARKLE, { it.reduceEffects }, { st, v -> st.reduceEffects = v })
        toggles += Toggle(s.showFps, Icon.BOLT, { it.showFps }, { st, v -> st.showFps = v })
        var y = scroll.rect.top + 4f * u
        val rowH = 58f * u
        for (t in toggles) {
            t.row.set(scroll.rect.left + 4f * u, y, scroll.rect.right - 4f * u, y + rowH - 8f * u)
            t.anim = if (t.get(app.settings)) 1f else 0f
            val b = button("", null, Button.Style.GHOST) {
                t.set(app.settings, !t.get(app.settings))
                app.settingsChanged()
            }
            b.scroll = scroll
            b.rect.set(t.row)
            y += rowH
        }
        // Language selector.
        langRow.set(scroll.rect.left + 4f * u, y, scroll.rect.right - 4f * u, y + rowH - 8f * u)
        val options = listOf("auto" to s.languageAuto, "pt" to "PT", "en" to "EN")
        val segW = 58f * u
        for ((i, pair) in options.withIndex()) {
            val (code, label) = pair
            val b = button(label, null, Button.Style.SECONDARY, if (app.settings.language == code) Palette.CYAN else Palette.MUTED) {
                app.settings.language = code
                app.settingsChanged()
                relayout()
            }
            b.scroll = scroll
            val right = langRow.right - 10f * u - (options.size - 1 - i) * (segW + 6f * u)
            b.rect.set(right - segW, langRow.centerY() - 18f * u, right, langRow.centerY() + 18f * u)
        }
        y += rowH + 10f * u
        val reset = button(s.resetProgress, Icon.TRASH, Button.Style.DANGER, Palette.RED) { confirmReset() }
        reset.scroll = scroll
        reset.rect.set(scroll.rect.left + 4f * u, y, scroll.rect.right - 4f * u, y + 50f * u)
        y += 86f * u
        aboutTop = y
        y += 150f * u
        val link = button(s.developer, Icon.LINKEDIN, Button.Style.LINK, Palette.BLUE) { app.openDeveloperLink() }
        link.sublabel = "linkedin.com/in/guilhermekawe"
        link.scroll = scroll
        link.rect.set(scroll.rect.left + 4f * u, y, scroll.rect.right - 4f * u, y + 48f * u)
        y += 70f * u
        scroll.contentHeight = y - scroll.rect.top + 60f * u
        scroll.scrollTo(savedOffset)
    }

    private fun confirmReset() {
        app.host.showDialog(Dialog(s.resetProgress, s.resetConfirm, listOf(s.cancel to {}, s.ok to {
            app.host.showDialog(Dialog(s.resetProgress, s.resetConfirm2, listOf(s.cancel to {}, s.resetProgress to {
                app.progression.resetProgress()
                app.host.toast(s.resetDone, "", Icon.TRASH, Palette.RED)
                app.host.resetTo(HomeScreen(app))
            }), danger = true))
        }), danger = true))
    }

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        savedOffset = scroll.offset
        ui.background.update(dt * 0.5f)
        for (t in toggles) {
            val target = if (t.get(app.settings)) 1f else 0f
            t.anim += (target - t.anim) * kotlin.math.min(1f, dt * 14f)
        }
    }

    override fun draw(c: Canvas) {
        drawBackdrop(c, 0.35f, 0.82f)
        drawHeader(c)
        c.save()
        c.clipRect(scroll.rect)
        c.translate(0f, -scroll.offset)
        val lp = ui.style(ui.textPaint, 16f, Palette.WHITE, Paint.Align.LEFT)
        for (t in toggles) {
            ui.neon.panel(c, t.row, 14f * u, Palette.withAlpha(Palette.PANEL, 0.9f), Palette.withAlpha(Palette.CYAN, 0.25f + 0.4f * t.anim), 0.2f)
            ui.icons.draw(c, t.icon, t.row.left + 28f * u, t.row.centerY(), 20f * u, Palette.mix(Palette.DIM, Palette.CYAN, t.anim))
            c.drawText(t.label, t.row.left + 52f * u, t.row.centerY() + 6f * u, lp)
            app.buttons.toggle(c, t.row, t.get(app.settings), t.anim)
        }
        ui.neon.panel(c, langRow, 14f * u, Palette.withAlpha(Palette.PANEL, 0.9f), Palette.withAlpha(Palette.CYAN, 0.3f), 0.2f)
        ui.icons.draw(c, Icon.GLOBE, langRow.left + 28f * u, langRow.centerY(), 20f * u, Palette.CYAN)
        c.drawText(s.language, langRow.left + 52f * u, langRow.centerY() + 6f * u, lp)
        drawButtons(c, scroll)
        // About.
        val hp = ui.style(ui.displayPaint, 17f, Palette.CYAN, Paint.Align.LEFT)
        c.drawText(s.about, scroll.rect.left + 12f * u, aboutTop, hp)
        val tp = ui.style(ui.semiPaint, 14f, Palette.TEXT, Paint.Align.LEFT)
        var y = aboutTop + 28f * u
        c.drawText("TAP TAP · ${s.version} $VERSION", scroll.rect.left + 12f * u, y, tp)
        y += 24f * u
        val mp = ui.style(ui.mediumPaint, 13f, Palette.DIM, Paint.Align.LEFT)
        y += ui.wrapText(c, s.privacyNote, scroll.rect.left + 12f * u, y, mp, scroll.rect.width() - 24f * u, 18f * u)
        ui.wrapText(c, "${s.credits}: ${s.fontsCredit}", scroll.rect.left + 12f * u, y + 4f * u, mp, scroll.rect.width() - 24f * u, 18f * u)
        c.restore()
        drawButtons(c)
    }

    companion object {
        const val VERSION = "1.0.0"
    }
}
