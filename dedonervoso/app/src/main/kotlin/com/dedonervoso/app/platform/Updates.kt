package com.dedonervoso.app.platform

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dedonervoso.core.online.Http
import com.dedonervoso.core.online.ReleaseInfo
import com.dedonervoso.core.online.UpdateChecker
import com.dedonervoso.core.online.UpdatePolicy
import com.dedonervoso.core.online.UpdateState
import com.dedonervoso.core.progression.Progression
import com.dedonervoso.core.util.JsonException
import java.util.concurrent.Executor

/** Network addresses. Tests point them at a local server. */
object Endpoints {
    /** Release manifest on the repository's default branch, written by `./gradlew publishRelease`. */
    const val DEFAULT_RELEASE_MANIFEST =
        "https://raw.githubusercontent.com/guilhermedominguestriunfante/PriceRadar/main/dedonervoso/release/version.json"

    /** Stable download link of the newest APK (used in invites before any manifest was fetched). */
    const val DEFAULT_APK_URL =
        "https://raw.githubusercontent.com/guilhermedominguestriunfante/PriceRadar/main/dedonervoso/release/dedo-nervoso.apk"

    @Volatile
    var releaseManifest: String = DEFAULT_RELEASE_MANIFEST

    /** Replaces the build's Firebase settings (tests use the local emulators). */
    @Volatile
    var firebaseOverride: com.dedonervoso.core.online.FirebaseConfig? = null
}

/** The installed version, from the package manager (single source: app/build.gradle.kts). */
class AppVersion(val code: Long, val name: String) {
    companion object {
        fun of(context: Context): AppVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            AppVersion(code, info.versionName ?: code.toString())
        } catch (e: Exception) {
            AppVersion(0L, "?")
        }
    }
}

/**
 * Update notices (the game is distributed as an APK, so it tells players itself): checks the
 * release manifest in the background at most every [MIN_INTERVAL_MS], keeps the answer in the
 * save and exposes what this install may do. Offline play never depends on it.
 */
class Updates(
    private val progression: Progression,
    val version: AppVersion,
    private val appId: String,
    private val http: Http,
    private val net: Executor,
) {
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    /** Last release seen (from the network or the save), or null when none is known. */
    var release: ReleaseInfo? = parseCache()
        private set

    /** Called on the main thread when a check brought news. */
    var onChange: (() -> Unit)? = null

    val state: UpdateState get() = UpdatePolicy.state(version.code, release)
    val onlineAllowed: Boolean get() = UpdatePolicy.onlineAllowed(version.code, release)
    val hasUpdate: Boolean get() = state == UpdateState.AVAILABLE || state == UpdateState.REQUIRED_FOR_ONLINE

    /** Starts a check unless one ran recently ([force] skips that). [done] gets whether it succeeded. */
    fun check(force: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val save = progression.save
        val now = System.currentTimeMillis()
        if (running || (!force && now - save.releaseCheckedAt in 0 until MIN_INTERVAL_MS)) {
            done?.invoke(false)
            return
        }
        running = true
        val url = save.releaseManifestUrl.ifEmpty { Endpoints.releaseManifest }
        net.execute {
            val result = UpdateChecker(http, appId).fetch(url)
            main.post {
                running = false
                if (result != null) {
                    val moved = result.manifestUrl.takeIf { it != Endpoints.releaseManifest } ?: ""
                    progression.recordRelease(result.release.toJson(appId), moved, now)
                    release = result.release
                    onChange?.invoke()
                }
                done?.invoke(result != null)
            }
        }
    }

    private fun parseCache(): ReleaseInfo? {
        val json = progression.save.releaseCache
        if (json.isEmpty()) return null
        return try {
            ReleaseInfo.parse(json, appId)
        } catch (e: JsonException) {
            null
        }
    }

    companion object {
        const val MIN_INTERVAL_MS = 30L * 60_000L
    }
}
