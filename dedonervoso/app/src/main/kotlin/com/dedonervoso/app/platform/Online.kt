package com.dedonervoso.app.platform

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dedonervoso.core.online.FirebaseClient
import com.dedonervoso.core.online.FirebaseConfig
import com.dedonervoso.core.online.Http
import com.dedonervoso.core.online.OnlineAccount
import com.dedonervoso.core.online.OnlineBoard
import com.dedonervoso.core.online.OnlineEntry
import com.dedonervoso.core.online.OnlineException
import com.dedonervoso.core.online.OnlineFailure
import com.dedonervoso.core.online.OnlineMetric
import com.dedonervoso.core.online.OnlineProfile
import com.dedonervoso.core.online.OnlineResult
import com.dedonervoso.core.online.OnlineService
import com.dedonervoso.core.progression.MatchOutcome
import com.dedonervoso.core.progression.Progression
import java.util.Properties
import java.util.concurrent.Executor

/**
 * Online play for the UI. Every call runs [OnlineService] on the network thread against one
 * working copy of the account (so two quick actions can never create two accounts), then
 * stores what it learned in the save and reports back on the UI thread. Offline play never
 * waits for any of this.
 */
class Online(
    private val progression: Progression,
    private val updates: Updates,
    val config: FirebaseConfig,
    http: Http,
    private val net: Executor,
    appVersion: Long,
) {
    enum class Status { OFF, UNAVAILABLE, UPDATE_REQUIRED, CONNECTING, READY, OFFLINE }

    private val service = OnlineService(FirebaseClient(config, http), appVersion)
    private val main = Handler(Looper.getMainLooper())
    private var connecting = false
    private var connected = false

    /** Account as seen by the network thread (only touched there). */
    private var working: OnlineAccount? = null

    /** Called on the UI thread whenever status or account data changed. */
    var onChange: (() -> Unit)? = null

    val enabled: Boolean get() = progression.save.onlineEnabled
    val account: OnlineAccount get() = progression.save.online
    val friendCode: String get() = account.code.takeIf { it.isNotEmpty() }?.let { OnlineService.formatCode(it) } ?: ""

    val status: Status
        get() = when {
            !enabled -> Status.OFF
            !config.configured -> Status.UNAVAILABLE
            !updates.onlineAllowed -> Status.UPDATE_REQUIRED
            connected -> Status.READY
            connecting -> Status.CONNECTING
            else -> Status.OFFLINE
        }

    private val canTalk: Boolean get() = enabled && config.configured && updates.onlineAllowed

    fun setEnabled(on: Boolean, done: ((Boolean) -> Unit)? = null) {
        progression.setOnlineEnabled(on)
        if (on) connect(done) else {
            connected = false
            onChange?.invoke()
            done?.invoke(true)
        }
    }

    /** Signs in (creating the account on first use) and syncs profile and bests. */
    fun connect(done: ((Boolean) -> Unit)? = null) {
        if (!canTalk || connecting) {
            done?.invoke(connected)
            return
        }
        connecting = true
        onChange?.invoke()
        val profile = profile()
        val best = localBest()
        call({ service.connect(it, profile, best) }) { _, failure ->
            connecting = false
            done?.invoke(failure == null)
        }
    }

    /** Publishes a finished match; a failure just waits for the next sync. */
    fun submit(outcome: MatchOutcome) {
        if (!canTalk || outcome.result.suspicious) return
        val r = outcome.result
        val result = OnlineResult(r.score, r.taps, r.maxTps, outcome.stage.number)
        val profile = profile()
        call({ service.submit(it, profile, result) }) { _, _ -> }
    }

    fun leaderboard(board: OnlineBoard, metric: OnlineMetric, done: (List<OnlineEntry>?, OnlineFailure?) -> Unit) {
        if (!canTalk) {
            done(null, OnlineFailure.NOT_CONFIGURED)
            return
        }
        call({ service.leaderboard(it, board, metric) }, done)
    }

    /** Adds a friend by code; [done] gets their nickname or why it failed. */
    fun addFriend(code: String, done: (String?, OnlineFailure?) -> Unit) {
        if (!canTalk) {
            done(null, OnlineFailure.NOT_CONFIGURED)
            return
        }
        val profile = profile()
        call({ service.addFriend(it, profile, code) }, done)
    }

    /** Deletes all online data and switches online play off. */
    fun deleteAccount(done: (Boolean) -> Unit) {
        call({ service.deleteAccount(it) }) { _, failure ->
            if (failure == null) {
                connected = false
                progression.setOnlineEnabled(false)
            }
            done(failure == null)
        }
    }

    private fun <T> call(op: (OnlineAccount) -> T, done: (T?, OnlineFailure?) -> Unit) {
        val seed = account.copy()
        net.execute {
            val acc = working ?: seed.also { working = it }
            var value: T? = null
            var failure: OnlineFailure? = null
            try {
                value = op(acc)
            } catch (e: OnlineException) {
                failure = e.failure
            } catch (e: RuntimeException) {
                failure = OnlineFailure.SERVER
            }
            val snapshot = acc.copy()
            main.post {
                progression.updateOnline(snapshot)
                when (failure) {
                    null -> connected = true
                    OnlineFailure.NETWORK, OnlineFailure.AUTH -> connected = false
                    else -> Unit
                }
                done(value, failure)
                onChange?.invoke()
            }
        }
    }

    /** How this player appears online (also to duel rivals). */
    fun profile(): OnlineProfile {
        val save = progression.save
        val nick = save.profile.nickname.trim().take(16).let { if (it.length < 2) it.padEnd(2, '_') else it }
        return OnlineProfile(nick, save.profile.avatar, progression.level)
    }

    /** Best results already made on this device (published when online play is switched on). */
    private fun localBest(): OnlineResult? {
        val save = progression.save
        val honest = save.ranking.filter { !it.suspicious }
        if (honest.isEmpty()) return null
        return OnlineResult(
            score = honest.maxOf { it.score },
            taps = honest.maxOf { it.taps },
            tps = honest.maxOf { it.maxTps },
            stage = save.highestCleared.coerceAtLeast(0),
        )
    }

    companion object {
        /** Reads assets/config/online.properties (written at build time; see app/build.gradle.kts). */
        fun loadConfig(context: Context): FirebaseConfig {
            Endpoints.firebaseOverride?.let { return it }
            val p = Properties()
            try {
                context.assets.open("config/online.properties").use { p.load(it) }
            } catch (e: java.io.IOException) {
                // No online configuration in this build.
            }
            return FirebaseConfig(
                p.getProperty("projectId", "").trim(), p.getProperty("apiKey", "").trim(),
                databaseUrl = p.getProperty("databaseUrl", "").trim(),
            )
        }
    }
}
