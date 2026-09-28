package com.dedonervoso.core.online

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Result of an HTTP call; [code] is -1 when the network failed before a response. */
class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/** An open streaming response (Server-Sent Events), read line by line; [close] works from any thread. */
interface HttpStream : Closeable {
    /** HTTP status, or -1 when the connection failed. */
    val code: Int

    /** Next line of the body, or null at the end of the stream (or after [close]). */
    fun readLine(): String?
}

/** Minimal blocking HTTP client (call it off the UI thread). Swappable for tests. */
interface Http {
    fun request(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 10_000,
    ): HttpResponse

    /** Opens a long-lived `text/event-stream` GET. */
    fun openStream(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): HttpStream =
        throw UnsupportedOperationException("This Http does not stream")
}

/** [Http] over [HttpURLConnection], available on both Android and the JVM (no extra libraries). */
class JavaNetHttp : Http {
    override fun request(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResponse(-1, e.message ?: "")
        }
        return try {
            conn.requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (!headers.containsKey("Content-Type")) conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            HttpResponse(code, text)
        } catch (e: IOException) {
            HttpResponse(-1, e.message ?: "")
        } finally {
            conn.disconnect()
        }
    }

    override fun openStream(url: String, headers: Map<String, String>, timeoutMs: Int): HttpStream {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return FailedStream
        }
        return try {
            conn.connectTimeout = timeoutMs
            // Servers send keep-alives (the Realtime Database every ~30 s); silence this long means a dead link.
            conn.readTimeout = STREAM_READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("Accept", "text/event-stream")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            val reader = if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8) else null
            if (reader == null) conn.disconnect()
            ConnectionStream(conn, code, reader)
        } catch (e: IOException) {
            conn.disconnect()
            FailedStream
        }
    }

    private class ConnectionStream(private val conn: HttpURLConnection, override val code: Int, private val reader: BufferedReader?) : HttpStream {
        @Volatile private var closed = false

        override fun readLine(): String? = if (closed || reader == null) null else try {
            reader.readLine()
        } catch (e: IOException) {
            null
        }

        override fun close() {
            closed = true
            conn.disconnect()
        }
    }

    private object FailedStream : HttpStream {
        override val code: Int get() = -1
        override fun readLine(): String? = null
        override fun close() = Unit
    }

    private companion object {
        const val STREAM_READ_TIMEOUT_MS = 75_000
    }
}
