package com.taptap.game

import android.app.AlertDialog
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.taptap.game.core.audio.Sfx
import com.taptap.game.core.i18n.Strings
import com.taptap.game.core.progression.AchievementDef
import com.taptap.game.core.progression.Progression
import com.taptap.game.core.save.SaveCodec
import com.taptap.game.platform.Haptics
import com.taptap.game.platform.Links
import com.taptap.game.platform.MusicPlayer
import com.taptap.game.platform.SaveStore
import com.taptap.game.platform.SfxPlayer
import com.taptap.game.ui.ButtonRenderer
import com.taptap.game.ui.Icon
import com.taptap.game.ui.Palette
import com.taptap.game.ui.ScreenHost
import com.taptap.game.ui.UiKit
import com.taptap.game.ui.screens.HomeScreen
import com.taptap.game.ui.screens.OnboardingScreen
import java.util.Locale

/**
 * Composition root: owns the services (storage, audio, haptics), the domain [Progression] and
 * the screen host. Lives as long as the activity.
 */
class GameApp(val activity: MainActivity) {
    val store = SaveStore(activity)
    val progression = Progression(store.load())
    val ui = UiKit(activity)
    val buttons = ButtonRenderer(ui)
    val sfx = SfxPlayer(activity)
    val music = MusicPlayer(activity)
    val haptics = Haptics(activity)
    val host = ScreenHost(this)

    var strings: Strings = resolveStrings()
        private set
    private var resumed = false

    val settings get() = progression.save.settings

    init {
        progression.onChanged = { store.scheduleSave(progression.save) }
        applySettings()
    }

    fun start() {
        val home = HomeScreen(this)
        if (progression.save.onboardingDone) host.setRoot(home) else {
            host.setRoot(home)
            host.push(OnboardingScreen(this))
        }
    }

    private fun resolveStrings(): Strings =
        Strings.forLanguage(progression.save.settings.language, Locale.getDefault().language)

    /** Pushes settings into every subsystem (called at start and whenever they change). */
    fun applySettings() {
        val st = progression.save.settings
        strings = resolveStrings()
        sfx.enabled = st.sfx
        haptics.enabled = st.vibration
        ui.particles.density = if (st.reduceEffects) 0.4f else 1f
        if (resumed) {
            if (st.music) music.start() else music.stop()
        }
    }

    fun settingsChanged() {
        progression.settingsChanged()
        applySettings()
    }

    fun onResume() {
        resumed = true
        sfx.autoResume()
        if (settings.music) music.start()
        host.onAppResume()
    }

    fun onPause() {
        resumed = false
        host.onAppPause()
        music.stop()
        sfx.autoPause()
        store.flush(progression.save, wait = false)
    }

    fun onStop() {
        store.flush(progression.save, wait = true)
    }

    fun release() {
        music.stop()
        sfx.release()
        haptics.release()
        store.close()
        ui.release()
    }

    /** Keeps the display awake during matches only (battery friendly). */
    fun keepScreenOn(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openDeveloperLink() {
        Links.open(activity, Links.DEVELOPER_LINKEDIN, if (strings.code == "pt") "Não foi possível abrir o link" else "Could not open the link")
    }

    /** Announces unlocked achievements with toasts, sound and a celebratory haptic. */
    fun announce(achievements: List<AchievementDef>) {
        if (achievements.isEmpty()) return
        sfx.play(Sfx.ACHIEVEMENT)
        haptics.celebrate()
        for (a in achievements) {
            host.toast(strings.achievementTitle(a.id), "+${strings.num(a.coins)} ${strings.coinsLabel}", Icon.MEDAL, Palette.GOLD)
        }
    }

    /** Native text input for the nickname (the only place a platform widget is used). */
    fun editNickname(onDone: () -> Unit) {
        val input = EditText(activity).apply {
            setText(progression.save.profile.nickname)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(SaveCodec.MAX_NICKNAME))
            setSingleLine()
        }
        val theme = if (Build.VERSION.SDK_INT >= 29) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
        AlertDialog.Builder(activity, theme)
            .setTitle(strings.editName)
            .setView(input)
            .setPositiveButton(strings.ok) { _, _ ->
                progression.setNickname(input.text.toString())
                onDone()
                activity.hideSystemBars()
            }
            .setNegativeButton(strings.cancel) { _, _ -> activity.hideSystemBars() }
            .setOnCancelListener { activity.hideSystemBars() }
            .show()
    }
}
