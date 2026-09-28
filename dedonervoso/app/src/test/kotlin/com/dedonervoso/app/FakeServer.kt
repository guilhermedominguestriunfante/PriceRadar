package com.dedonervoso.app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local HTTP server standing in for the internet in UI tests (release manifest, backend), so no
 * test depends on the real network. Unknown paths answer 404.
 */
class FakeServer : AutoCloseable {
    class Call(val method: String, val path: String, val body: String)

    /** Path (with query) → handler returning (status, body). */
    val routes = ConcurrentHashMap<String, (Call) -> Pair<Int, String>>()
    val calls = CopyOnWriteArrayList<Call>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            val path = ex.requestURI.rawPath + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val method = ex.requestHeaders.getFirst("X-HTTP-Method-Override") ?: ex.requestMethod
            val call = Call(method, path, body)
            calls += call
            val handler = routes[path] ?: routes[ex.requestURI.rawPath]
            val (status, text) = handler?.invoke(call) ?: (404 to "Not Found")
            val bytes = text.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    override fun close() = server.stop(0)
}
