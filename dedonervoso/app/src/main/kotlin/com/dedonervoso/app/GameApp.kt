package com.dedonervoso.app

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.i18n.Strings
import com.dedonervoso.core.online.DuelInvite
import com.dedonervoso.core.online.JavaNetHttp
import com.dedonervoso.core.online.OnlineFailure
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.core.progression.AchievementDef
import com.dedonervoso.core.progression.Progression
import com.dedonervoso.core.save.SaveCodec
import com.dedonervoso.app.platform.AppVersion
import com.dedonervoso.app.platform.Duel
import com.dedonervoso.app.platform.Haptics
import com.dedonervoso.app.platform.Endpoints
import com.dedonervoso.app.platform.Links
import com.dedonervoso.app.platform.Online
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
import com.dedonervoso.app.ui.screens.DuelResultScreen
import com.dedonervoso.app.ui.screens.DuelWaitScreen
import com.dedonervoso.app.ui.screens.HomeScreen
import com.dedonervoso.app.ui.screens.IntroScreen
import com.dedonervoso.app.ui.screens.OnboardingScreen
import com.dedonervoso.app.ui.screens.PlayScreen
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
    val online = Online(progression, updates, Online.loadConfig(activity), JavaNetHttp(), net, updates.version.code)
    val duel = Duel(online, JavaNetHttp(), net)

    var strings: Strings = resolveStrings()
        private set
    private var resumed = false

    /** Challenges already offered in a dialog (each is offered once). */
    private val offeredInvites = HashSet<String>()
    private var inviteCheck = false

    val settings get() = progression.save.settings

    init {
        progression.onChanged = { store.scheduleSave(progression.save) }
        updates.onChange = { host.current?.onBackgroundUpdate() }
        online.onChange = {
            host.current?.onBackgroundUpdate()
            duel.syncInvites()
        }
        duel.onChange = {
            host.current?.onBackgroundUpdate()
            inviteCheck = true
        }
        applySettings()
    }

    fun start() {
        host.setRoot(IntroScreen(this) { enterGame() })
        // Online waits for the version check, so an outdated install never reaches the server.
        updates.check { online.connect() }
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
        duel.setForeground(true)
    }

    fun onPause() {
        resumed = false
        host.onAppPause()
        duel.setForeground(false)
        music.stop()
        sfx.autoPause()
        store.flush(progression.save, wait = false)
    }

    fun onStop() {
        store.flush(progression.save, wait = true)
    }

    fun release() {
        duel.release()
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

    /** Asks before going online (what becomes visible), then signs in. */
    fun enableOnlineWithConsent(done: () -> Unit = {}) {
        host.showDialog(
            Dialog(
                strings.onlineConsentTitle, strings.onlineConsentText,
                listOf(
                    strings.notNow to {},
                    strings.enable to {
                        online.setEnabled(true) { ok ->
                            if (!ok && online.status != Online.Status.READY) {
                                host.toast(strings.onlineRanking, strings.onlineError, Icon.GLOBE, Palette.ORANGE)
                            }
                            done()
                        }
                    },
                ),
            ),
        )
    }

    /** Friend code input; the friend is added in the background and announced with a toast. */
    fun addFriendDialog() {
        val input = EditText(activity).apply {
            hint = strings.friendCodeHint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(9))
            setSingleLine()
        }
        val theme = if (Build.VERSION.SDK_INT >= 29) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
        AlertDialog.Builder(activity, theme)
            .setTitle(strings.addFriend)
            .setView(input)
            .setPositiveButton(strings.ok) { _, _ ->
                activity.hideSystemBars()
                addFriend(input.text.toString())
            }
            .setNegativeButton(strings.cancel) { _, _ -> activity.hideSystemBars() }
            .setOnCancelListener { activity.hideSystemBars() }
            .show()
    }

    fun addFriend(code: String) {
        online.addFriend(code) { nick, failure ->
            when {
                nick != null -> {
                    sfx.play(Sfx.ACHIEVEMENT)
                    host.toast(strings.friendAdded(nick), strings.friendsTab, Icon.USER, Palette.GREEN)
                }
                failure == OnlineFailure.NOT_FOUND -> host.toast(strings.codeNotFound, code.uppercase(), Icon.INFO, Palette.ORANGE)
                failure == OnlineFailure.CONFLICT -> host.toast(strings.ownCode, online.friendCode, Icon.INFO, Palette.ORANGE)
                else -> host.toast(strings.addFriend, strings.onlineError, Icon.GLOBE, Palette.ORANGE)
            }
        }
    }

    /** Shares the friend code and the download link (WhatsApp or any app, via the system sheet). */
    fun shareInvite() {
        val url = updates.release?.apkUrl ?: Endpoints.DEFAULT_APK_URL
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, strings.inviteText(online.friendCode.ifEmpty { "—" }, url))
        }
        try {
            activity.startActivity(Intent.createChooser(send, strings.invite))
        } catch (e: ActivityNotFoundException) {
            host.toast(strings.invite, strings.linkFailed, Icon.INFO, Palette.ORANGE)
        }
    }

    /** A screen was shown: a challenge that waited (e.g. during a match) may be offered now. */
    fun screenShown() {
        inviteCheck = true
    }

    /** Called by the host on frames without a transition or dialog. */
    fun idleFrame() {
        if (!inviteCheck) return
        inviteCheck = false
        offerInvite()
    }

    /** "X te desafiou!" for the newest challenge — never during a match, a duel or another dialog. */
    private fun offerInvite() {
        val screen = host.current ?: return
        if (screen is PlayScreen || screen is IntroScreen || screen is OnboardingScreen || duel.busy) return
        val invite = duel.invites.firstOrNull { it.duelId !in offeredInvites } ?: return
        offeredInvites += invite.duelId
        sfx.play(Sfx.ZONE_SPAWN)
        haptics.click()
        host.showDialog(
            Dialog(
                strings.challengedYou(invite.nick), strings.challengeText,
                listOf(strings.decline to { duel.decline(invite) }, strings.accept to { acceptInvite(invite) }),
            ),
        )
    }

    fun acceptInvite(invite: DuelInvite) {
        offeredInvites += invite.duelId
        // A dialog left open while another duel got under way (both asked for a rematch): that one wins.
        if (duel.busy) return
        val wait = DuelWaitScreen(this, duel.accept(invite))
        // From another duel's wait or result, the new one takes its place.
        if (host.current is DuelWaitScreen || host.current is DuelResultScreen) host.replace(wait) else host.push(wait)
    }

    fun confirmDeleteOnline() {
        host.showDialog(
            Dialog(
                strings.deleteOnline, strings.deleteOnlineConfirm,
                listOf(
                    strings.cancel to {},
                    strings.deleteOnline to {
                        online.deleteAccount { ok ->
                            if (ok) host.toast(strings.onlineDeleted, "", Icon.TRASH, Palette.RED)
                            else host.toast(strings.deleteOnline, strings.onlineError, Icon.GLOBE, Palette.ORANGE)
                        }
                    },
                ),
                danger = true,
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
