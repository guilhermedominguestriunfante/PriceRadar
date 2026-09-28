package com.dedonervoso.core.online

import com.dedonervoso.core.util.JObj
import com.dedonervoso.core.util.Json
import com.dedonervoso.core.util.JsonException

/** A published release, as described by `release/version.json` (written by `publishRelease`). */
class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    /** Oldest version still allowed online; players below it must update to play online. */
    val minOnlineVersionCode: Long,
    val apkUrl: String,
    val notesPt: List<String>,
    val notesEn: List<String>,
    val sha256: String = "",
    val size: Long = 0L,
    /** When the manifest moves, the old one points here and clients follow (and remember) it. */
    val movedTo: String? = null,
) {
    fun notes(language: String): List<String> = when {
        language == "pt" && notesPt.isNotEmpty() -> notesPt
        notesEn.isNotEmpty() -> notesEn
        else -> notesPt
    }

    fun toJson(appId: String): String = Json.write(
        linkedMapOf(
            "schema" to 1, "app" to appId, "versionCode" to versionCode, "versionName" to versionName,
            "minOnlineVersionCode" to minOnlineVersionCode, "apkUrl" to apkUrl, "size" to size, "sha256" to sha256,
            "notes" to mapOf("pt" to notesPt, "en" to notesEn),
        ) + (if (movedTo != null) mapOf("movedTo" to movedTo) else emptyMap()),
    )

    companion object {
        /**
         * Parses a manifest for [appId]. Throws [JsonException] when it is malformed, belongs to
         * another app or lacks the version/download fields — a broken file never counts as a release.
         */
        fun parse(text: String, appId: String): ReleaseInfo {
            val o = JObj.parse(text)
            val app = o.string("app")
            if (app.isNotEmpty() && app != appId) throw JsonException("Manifest is for $app, not $appId")
            val moved = o.stringOrNull("movedTo")?.takeIf { it.startsWith("https://") }
            val code = o.long("versionCode", -1L)
            val url = o.string("apkUrl")
            if (moved == null && (code <= 0L || !url.startsWith("https://"))) throw JsonException("Manifest lacks versionCode/apkUrl")
            val notes = o.obj("notes")
            return ReleaseInfo(
                versionCode = code,
                versionName = o.string("versionName", code.toString()),
                minOnlineVersionCode = o.long("minOnlineVersionCode", 0L).coerceIn(0L, maxOf(code, 0L)),
                apkUrl = url,
                notesPt = notes.list("pt").filterIsInstance<String>().take(MAX_NOTES),
                notesEn = notes.list("en").filterIsInstance<String>().take(MAX_NOTES),
                sha256 = o.string("sha256"),
                size = o.long("size"),
                movedTo = moved,
            )
        }

        private const val MAX_NOTES = 8
    }
}

enum class UpdateState {
    /** Never reached the manifest (offline since install, or none published yet). */
    UNKNOWN,
    UP_TO_DATE,
    /** A newer version exists; this one still plays online. */
    AVAILABLE,
    /** A newer version exists and online play needs it (offline play is unaffected). */
    REQUIRED_FOR_ONLINE,
}

/** What the installed [versionCode] may do given the last known [release]. */
object UpdatePolicy {
    fun state(versionCode: Long, release: ReleaseInfo?): UpdateState = when {
        release == null || release.versionCode <= 0L -> UpdateState.UNKNOWN
        versionCode >= release.versionCode -> UpdateState.UP_TO_DATE
        versionCode < release.minOnlineVersionCode -> UpdateState.REQUIRED_FOR_ONLINE
        else -> UpdateState.AVAILABLE
    }

    /** Online features stay open when nothing is known yet: a GitHub hiccup must not lock players out. */
    fun onlineAllowed(versionCode: Long, release: ReleaseInfo?): Boolean = state(versionCode, release) != UpdateState.REQUIRED_FOR_ONLINE
}

/** Downloads and parses the release manifest, following [ReleaseInfo.movedTo] (at most twice). */
class UpdateChecker(private val http: Http, private val appId: String) {
    class Result(val release: ReleaseInfo, val manifestUrl: String)

    fun fetch(manifestUrl: String): Result? {
        var url = manifestUrl
        repeat(MAX_HOPS + 1) {
            val r = http.request("GET", url, timeoutMs = 8_000)
            if (!r.ok) return null
            val info = try {
                ReleaseInfo.parse(r.body, appId)
            } catch (e: JsonException) {
                return null
            }
            val next = info.movedTo ?: return Result(info, url)
            if (next == url) return null
            url = next
        }
        return null
    }

    private companion object {
        const val MAX_HOPS = 2
    }
}
