package com.dedonervoso.app

import android.app.AlertDialog
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.i18n.Strings
import com.dedonervoso.core.online.JavaNetHttp
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.core.progression.AchievementDef
import com.dedonervoso.core.progression.Progression
import com.dedonervoso.core.save.SaveCodec
import com.dedonervoso.app.platform.AppVersion
import com.dedonervoso.app.platform.Haptics
import com.dedonervoso.app.platform.Links
import com.dedonervoso.app.platform.MusicPlayer
import com.dedonervoso.app.platform.SaveStore
import com.dedonervoso.app.platform.SfxPlayer
import com.dedonervoso.app.platform.Updates
import com.dedonervoso.app.ui.ButtonRenderer
import com.dedonervoso.app.ui.Dialog
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.ScreenHost
import com.dedonervoso.app.ui.UiKit
import com.dedonervoso.app.ui.screens.HomeScreen
import com.dedonervoso.app.ui.screens.IntroScreen
import com.dedonervoso.app.ui.screens.OnboardingScreen
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

    /** Single background thread for network calls (results are posted back to the UI thread). */
    val net: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dedo-net").apply { isDaemon = true } }
    val updates = Updates(progression, AppVersion.of(activity), activity.packageName, JavaNetHttp(), net)

    var strings: Strings = resolveStrings()
        private set
    private var resumed = false

    val settings get() = progression.save.settings

    init {
        progression.onChanged = { store.scheduleSave(progression.save) }
        updates.onChange = { host.current?.onUpdatesChanged() }
        applySettings()
    }

    fun start() {
        host.setRoot(IntroScreen(this) { enterGame() })
        updates.check()
    }

    /** After the opening: Home, with the onboarding on top on the first run. */
    private fun enterGame() {
        host.resetTo(HomeScreen(this), if (progression.save.onboardingDone) null else OnboardingScreen(this))
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
        updates.check()
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
        net.shutdownNow()
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

    /** The new version's notes with a download button (the APK opens in the browser to install). */
    fun showUpdate() {
        val r = updates.release ?: return
        val notes = r.notes(strings.code).ifEmpty { listOf(strings.updateDefaultNotes) }
        val required = updates.state == UpdateState.REQUIRED_FOR_ONLINE
        val message = (if (required) strings.updateRequiredOnline + "\n" else "") + notes.joinToString("\n") { "• $it" }
        host.showDialog(
            Dialog(
                strings.updateTitle(r.versionName), message,
                listOf(strings.later to {}, strings.download to { Links.open(activity, r.apkUrl, strings.linkFailed) }),
            ),
        )
    }

    fun openDeveloperLink() {
        Links.open(activity, Links.DEVELOPER_LINKEDIN, strings.linkFailed)
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
