package com.dedonervoso.core.online

import com.dedonervoso.core.util.Json
import com.dedonervoso.core.util.JsonException
import java.net.URLEncoder

/**
 * Firebase Realtime Database over its REST API (no SDK): reads, writes with server timestamps
 * and live streams (Server-Sent Events), signed in with the player's Firebase ID token. Used by
 * live duels, where both phones must see each other's moves within a fraction of a second.
 *
 * Values are plain Kotlin as in [Json]: maps, lists, strings, longs, doubles, booleans, null.
 * Calls block — use a background thread; each [stream] runs on its own thread.
 */
class RealtimeDb(private val auth: FirebaseClient, private val http: Http) {
    private val config: FirebaseConfig get() = auth.config

    val configured: Boolean get() = config.duelsConfigured

    fun get(session: AuthSession, path: String): Any? = decode(call(session) { http.request("GET", url(path, it)) }, "get $path")

    /** Replaces the value at [path]; returns it as stored (server values resolved). */
    fun put(session: AuthSession, path: String, value: Any?): Any? =
        decode(call(session) { http.request("PUT", url(path, it), Json.write(value)) }, "put $path")

    fun delete(session: AuthSession, path: String) {
        decode(call(session) { http.request("DELETE", url(path, it)) }, "delete $path")
    }

    /**
     * Follows [path] live: [listener] gets the whole subtree at once, then again after every
     * change, on the stream's own thread. Reconnects by itself (with a fresh token) until closed.
     */
    fun stream(session: AuthSession, path: String, listener: Listener): Stream = Stream(session, path, listener).also { it.start() }

    interface Listener {
        /** The current value at the streamed path (null when nothing is there). */
        fun onValue(value: Any?)

        /** The server refused the stream (rules) or it could not connect; it keeps retrying unless [OnlineFailure.DENIED]. */
        fun onError(failure: OnlineFailure) = Unit
    }

    inner class Stream internal constructor(private val session: AuthSession, private val path: String, private val listener: Listener) {
        @Volatile private var closed = false
        @Volatile private var current: HttpStream? = null
        private val thread = Thread({ run() }, "rtdb-stream")
        private var tree: Any? = null

        internal fun start() {
            thread.isDaemon = true
            thread.start()
        }

        fun close() {
            closed = true
            current?.close()
            thread.interrupt()
        }

        private fun run() {
            var backoff = RETRY_MIN_MS
            while (!closed) {
                val token = try {
                    auth.freshToken(session)
                } catch (e: OnlineException) {
                    if (!pause(backoff, e.failure)) return
                    backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                    continue
                }
                val s = http.openStream(url(path, token))
                current = s
                if (closed) {
                    s.close()
                    return
                }
                if (s.code !in 200..299) {
                    s.close()
                    val failure = when (s.code) {
                        -1 -> OnlineFailure.NETWORK
                        401, 403 -> OnlineFailure.DENIED
                        404 -> OnlineFailure.NOT_FOUND
                        else -> OnlineFailure.SERVER
                    }
                    if (failure == OnlineFailure.DENIED) {
                        listener.onError(failure)
                        return
                    }
                    if (!pause(backoff, failure)) return
                    backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
                    continue
                }
                backoff = RETRY_MIN_MS
                when (readEvents(s)) {
                    End.CANCELLED -> {
                        listener.onError(OnlineFailure.DENIED)
                        return
                    }
                    End.AUTH_REVOKED -> session.expiresAtMs = 0L   // reconnect with a fresh token
                    End.DISCONNECTED -> if (!pause(backoff, OnlineFailure.NETWORK)) return
                }
            }
        }

        private fun pause(ms: Long, failure: OnlineFailure): Boolean {
            if (closed) return false
            listener.onError(failure)
            return try {
                Thread.sleep(ms)
                !closed
            } catch (e: InterruptedException) {
                false
            }
        }

        private fun readEvents(s: HttpStream): End {
            var event = ""
            val data = StringBuilder()
            try {
                while (!closed) {
                    val line = s.readLine() ?: return End.DISCONNECTED
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                        line.isEmpty() -> {
                            when (event) {
                                "put", "patch" -> apply(event, data.toString())
                                "cancel" -> return End.CANCELLED
                                "auth_revoked" -> return End.AUTH_REVOKED
                            }
                            event = ""
                            data.setLength(0)
                        }
                    }
                }
                return End.DISCONNECTED
            } finally {
                s.close()
            }
        }

        private fun apply(event: String, data: String) {
            val message = try {
                Json.parse(data) as? Map<*, *>
            } catch (e: JsonException) {
                null
            } ?: return
            val at = segments(message["path"] as? String ?: "/")
            val value = message["data"]
            if (event == "put") {
                tree = setAt(tree, at, value)
            } else {
                val children = value as? Map<*, *> ?: return
                for ((k, v) in children) tree = setAt(tree, at + segments(k.toString()), v)
            }
            if (!closed) listener.onValue(tree)
        }
    }

    private enum class End { DISCONNECTED, CANCELLED, AUTH_REVOKED }

    /** Runs [request] with a fresh token, once more after a refresh if the server says it expired. */
    private fun call(session: AuthSession, request: (String) -> HttpResponse): HttpResponse {
        if (!configured) throw OnlineException(OnlineFailure.NOT_CONFIGURED, "Realtime Database is not configured in this build")
        val r = request(auth.freshToken(session))
        if (r.code != 401 || r.body.contains("Permission denied", ignoreCase = true)) return r
        auth.refresh(session)
        return request(session.idToken)
    }

    private fun url(path: String, token: String): String {
        val encoded = segments(path).joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val sb = StringBuilder(config.databaseUrl.trimEnd('/')).append('/').append(encoded).append(".json?auth=")
            .append(URLEncoder.encode(token, "UTF-8"))
        if (config.databaseNs.isNotEmpty()) sb.append("&ns=").append(URLEncoder.encode(config.databaseNs, "UTF-8"))
        return sb.toString()
    }

    private fun decode(r: HttpResponse, what: String): Any? {
        if (!r.ok) {
            val failure = when {
                r.code < 0 -> OnlineFailure.NETWORK
                r.code == 401 || r.code == 403 -> if (r.body.contains("Permission denied", ignoreCase = true)) OnlineFailure.DENIED else OnlineFailure.AUTH
                r.code == 404 -> OnlineFailure.NOT_FOUND
                r.code == 412 -> OnlineFailure.CONFLICT
                else -> OnlineFailure.SERVER
            }
            throw OnlineException(failure, "$what failed: HTTP ${r.code} ${r.body.take(200)}")
        }
        return try {
            if (r.body.isBlank()) null else Json.parse(r.body)
        } catch (e: JsonException) {
            throw OnlineException(OnlineFailure.SERVER, "$what: bad response")
        }
    }

    companion object {
        /** Placeholder the server replaces with its own clock (ms since the epoch). */
        val SERVER_TIMESTAMP: Map<String, Any?> = mapOf(".sv" to "timestamp")

        private const val RETRY_MIN_MS = 500L
        private const val RETRY_MAX_MS = 8_000L

        internal fun segments(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

        /** [root] with [value] stored at [at] (null deletes, emptying parents disappear like on the server). */
        internal fun setAt(root: Any?, at: List<String>, value: Any?): Any? {
            if (at.isEmpty()) return value
            val map = LinkedHashMap<String, Any?>()
            (root as? Map<*, *>)?.forEach { (k, v) -> map[k.toString()] = v }
            val child = setAt(map[at[0]], at.subList(1, at.size), value)
            if (child == null || (child is Map<*, *> && child.isEmpty())) map.remove(at[0]) else map[at[0]] = child
            return if (map.isEmpty()) null else map
        }
    }
}
