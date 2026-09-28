package com.dedonervoso.core.online

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Result of an HTTP call; [code] is -1 when the network failed before a response. */
class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
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
}
