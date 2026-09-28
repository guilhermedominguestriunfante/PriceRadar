package com.dedonervoso.core.online

import com.dedonervoso.core.util.JObj
import com.dedonervoso.core.util.Json
import com.dedonervoso.core.util.JsonException
import java.net.URLEncoder

/** Where the Firebase project lives. The base URLs are overridable for the local emulators. */
class FirebaseConfig(
    val projectId: String,
    val apiKey: String,
    val authBase: String = "https://identitytoolkit.googleapis.com/v1",
    val tokenBase: String = "https://securetoken.googleapis.com/v1",
    val firestoreBase: String = "https://firestore.googleapis.com/v1",
    /** Realtime Database URL (live duels), e.g. `https://<project>-default-rtdb.firebaseio.com`. */
    val databaseUrl: String = "",
    /** Database namespace passed as `?ns=` (the emulator serves every namespace on one host). */
    val databaseNs: String = "",
) {
    val configured: Boolean get() = projectId.isNotBlank() && apiKey.isNotBlank()

    /** Live duels need the Realtime Database as well. */
    val duelsConfigured: Boolean get() = configured && databaseUrl.isNotBlank()

    /** Resource name prefix of documents: `projects/<id>/databases/(default)/documents`. */
    val documentsRoot: String get() = "projects/$projectId/databases/(default)/documents"

    companion object {
        /** The Firebase Local Emulator Suite on [host] (`firebase emulators:start`). */
        fun emulator(
            projectId: String,
            host: String = "127.0.0.1",
            authPort: Int = 9099,
            firestorePort: Int = 8080,
            databasePort: Int = 9000,
        ) = FirebaseConfig(
            projectId = projectId,
            apiKey = "emulator",
            authBase = "http://$host:$authPort/identitytoolkit.googleapis.com/v1",
            tokenBase = "http://$host:$authPort/securetoken.googleapis.com/v1",
            firestoreBase = "http://$host:$firestorePort/v1",
            databaseUrl = "http://$host:$databasePort",
            databaseNs = "$projectId-default-rtdb",
        )
    }
}

/** Why an online call failed. */
enum class OnlineFailure { NETWORK, AUTH, DENIED, NOT_FOUND, CONFLICT, SERVER, NOT_CONFIGURED }

class OnlineException(val failure: OnlineFailure, message: String) : RuntimeException(message)

/** A signed-in Firebase user. [idToken] expires hourly and is refreshed transparently. */
class AuthSession(val uid: String, var idToken: String, var refreshToken: String, var expiresAtMs: Long)

/** A Firestore document: its path below the database root and its decoded fields. */
class FsDoc(val path: String, val fields: Map<String, Any?>) {
    val id: String get() = path.substringAfterLast('/')
    fun long(key: String): Long = (fields[key] as? Number)?.toLong() ?: 0L
    fun string(key: String): String = fields[key] as? String ?: ""

    @Suppress("UNCHECKED_CAST")
    fun strings(key: String): List<String> = (fields[key] as? List<Any?>)?.filterIsInstance<String>() ?: emptyList()
}

/** One write of a commit: set [fields] (only those listed when [mask] is given) with preconditions. */
class FsWrite(
    val path: String,
    val fields: Map<String, Any?>,
    val mask: List<String>? = null,
    val mustExist: Boolean? = null,
    /** Fields set to the server's request time (`request.time` in the rules). */
    val serverTimestamps: List<String> = emptyList(),
    val delete: Boolean = false,
)

/**
 * Firebase over its REST APIs (no SDK, no Google Play services): anonymous accounts on Identity
 * Toolkit and documents on Cloud Firestore. Blocking — call from a background thread.
 */
class FirebaseClient(val config: FirebaseConfig, private val http: Http, private val clock: () -> Long = System::currentTimeMillis) {

    // ---- accounts -------------------------------------------------------------------------------

    fun signUpAnonymously(): AuthSession {
        val o = auth("accounts:signUp", Json.write(mapOf("returnSecureToken" to true)))
        return AuthSession(o.string("localId"), o.string("idToken"), o.string("refreshToken"), expiry(o.string("expiresIn")))
    }

    /** Restores a session from its refresh token (e.g. at app start). */
    fun resume(uid: String, refreshToken: String): AuthSession =
        AuthSession(uid, "", refreshToken, 0L).also { refresh(it) }

    fun refresh(session: AuthSession) {
        requireConfigured()
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(session.refreshToken, "UTF-8")
        val r = http.request(
            "POST", "${config.tokenBase}/token?key=${config.apiKey}", body,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        val o = parse(r, "token refresh")
        if (o.string("user_id").isNotEmpty() && o.string("user_id") != session.uid) throw OnlineException(OnlineFailure.AUTH, "Token belongs to another user")
        session.idToken = o.string("id_token")
        session.refreshToken = o.string("refresh_token").ifEmpty { session.refreshToken }
        session.expiresAtMs = expiry(o.string("expires_in"))
    }

    fun deleteAccount(session: AuthSession) {
        ensureFresh(session)
        auth("accounts:delete", Json.write(mapOf("idToken" to session.idToken)))
    }

    private fun auth(method: String, body: String): JObj {
        requireConfigured()
        return parse(http.request("POST", "${config.authBase}/$method?key=${config.apiKey}", body), method)
    }

    private fun expiry(expiresIn: String) = clock() + ((expiresIn.toLongOrNull() ?: 3600L) - 120L) * 1000L

    private fun ensureFresh(session: AuthSession) {
        if (session.idToken.isEmpty() || clock() >= session.expiresAtMs) refresh(session)
    }

    /** A valid ID token for [session] (refreshed when it is about to expire). */
    fun freshToken(session: AuthSession): String {
        requireConfigured()
        ensureFresh(session)
        return session.idToken
    }

    // ---- documents ------------------------------------------------------------------------------

    /** The document at [path] (e.g. `players/abc`), or null when it doesn't exist. */
    fun get(session: AuthSession, path: String): FsDoc? {
        val r = authorized(session) { http.request("GET", "${config.firestoreBase}/${config.documentsRoot}/${encodePath(path)}", headers = it) }
        if (r.code == 404) return null
        return decodeDocument(parse(r, "get $path"))
    }

    /** Documents at [paths] that exist (missing ones are skipped), in request order. */
    fun batchGet(session: AuthSession, paths: List<String>): List<FsDoc> {
        if (paths.isEmpty()) return emptyList()
        val names = paths.map { "${config.documentsRoot}/$it" }
        val r = authorized(session) {
            http.request("POST", "${config.firestoreBase}/${config.documentsRoot}:batchGet", Json.write(mapOf("documents" to names)), it)
        }
        val found = HashMap<String, FsDoc>()
        for (item in parseArray(r, "batchGet")) {
            val doc = (item as? Map<*, *>)?.get("found") as? Map<*, *> ?: continue
            val d = decodeDocument(JObj(doc.mapKeys { it.key.toString() }))
            found[d.path] = d
        }
        return paths.mapNotNull { found[it] }
    }

    /** Top [limit] documents of [collection] (under [parent], "" for the root) by [orderBy] descending. */
    fun topBy(session: AuthSession, parent: String, collection: String, orderBy: String, limit: Int): List<FsDoc> {
        val query = mapOf(
            "structuredQuery" to mapOf(
                "from" to listOf(mapOf("collectionId" to collection)),
                "orderBy" to listOf(mapOf("field" to mapOf("fieldPath" to orderBy), "direction" to "DESCENDING")),
                "limit" to limit,
            ),
        )
        val base = if (parent.isEmpty()) config.documentsRoot else "${config.documentsRoot}/${encodePath(parent)}"
        val r = authorized(session) { http.request("POST", "${config.firestoreBase}/$base:runQuery", Json.write(query), it) }
        return parseArray(r, "runQuery").mapNotNull { item ->
            val doc = (item as? Map<*, *>)?.get("document") as? Map<*, *> ?: return@mapNotNull null
            decodeDocument(JObj(doc.mapKeys { it.key.toString() }))
        }
    }

    /** Applies [writes] atomically: all succeed or none does (e.g. a rule or precondition fails). */
    fun commit(session: AuthSession, writes: List<FsWrite>) {
        val body = Json.write(mapOf("writes" to writes.map { encodeWrite(it) }))
        val r = authorized(session) { http.request("POST", "${config.firestoreBase}/${config.documentsRoot}:commit", body, it) }
        parse(r, "commit")
    }

    private fun encodeWrite(w: FsWrite): Map<String, Any?> {
        val name = "${config.documentsRoot}/${w.path}"
        if (w.delete) return mapOf("delete" to name)
        val m = linkedMapOf<String, Any?>("update" to mapOf("name" to name, "fields" to FirestoreValues.encodeFields(w.fields)))
        if (w.mask != null) m["updateMask"] = mapOf("fieldPaths" to w.mask)
        if (w.serverTimestamps.isNotEmpty()) {
            m["updateTransforms"] = w.serverTimestamps.map { mapOf("fieldPath" to it, "setToServerValue" to "REQUEST_TIME") }
        }
        if (w.mustExist != null) m["currentDocument"] = mapOf("exists" to w.mustExist)
        return m
    }

    /** Runs [call] with a fresh bearer token, refreshing once more if the server says it expired. */
    private fun authorized(session: AuthSession, call: (Map<String, String>) -> HttpResponse): HttpResponse {
        requireConfigured()
        ensureFresh(session)
        val r = call(mapOf("Authorization" to "Bearer ${session.idToken}"))
        if (r.code != 401) return r
        refresh(session)
        return call(mapOf("Authorization" to "Bearer ${session.idToken}"))
    }

    private fun decodeDocument(o: JObj): FsDoc {
        val name = o.string("name")
        val path = name.substringAfter("/documents/", name)
        return FsDoc(path, FirestoreValues.decodeFields(o.obj("fields").map))
    }

    private fun requireConfigured() {
        if (!config.configured) throw OnlineException(OnlineFailure.NOT_CONFIGURED, "Firebase is not configured in this build")
    }

    private fun encodePath(path: String) = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun parse(r: HttpResponse, what: String): JObj {
        check(r, what)
        return try {
            if (r.body.isBlank()) JObj(emptyMap()) else JObj.parse(r.body)
        } catch (e: JsonException) {
            throw OnlineException(OnlineFailure.SERVER, "$what: bad response")
        }
    }

    private fun parseArray(r: HttpResponse, what: String): List<Any?> {
        check(r, what)
        return try {
            Json.parse(r.body) as? List<Any?> ?: emptyList()
        } catch (e: JsonException) {
            throw OnlineException(OnlineFailure.SERVER, "$what: bad response")
        }
    }

    private fun check(r: HttpResponse, what: String) {
        if (r.ok) return
        val failure = when {
            r.code < 0 -> OnlineFailure.NETWORK
            r.code == 400 && r.body.contains("TOKEN") -> OnlineFailure.AUTH
            r.code == 401 -> OnlineFailure.AUTH
            r.code == 403 -> OnlineFailure.DENIED
            r.code == 404 -> OnlineFailure.NOT_FOUND
            r.code == 409 || r.body.contains("ALREADY_EXISTS") || r.body.contains("FAILED_PRECONDITION") -> OnlineFailure.CONFLICT
            else -> OnlineFailure.SERVER
        }
        throw OnlineException(failure, "$what failed: HTTP ${r.code} ${r.body.take(200)}")
    }
}

/** Firestore's typed JSON values (`integerValue`, `stringValue`, …) to and from plain Kotlin. */
object FirestoreValues {
    fun encodeFields(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { encode(it.value) }

    fun encode(v: Any?): Map<String, Any?> = when (v) {
        null -> mapOf("nullValue" to null)
        is Boolean -> mapOf("booleanValue" to v)
        is Int, is Long, is Short, is Byte -> mapOf("integerValue" to v.toString())
        is Float, is Double -> mapOf("doubleValue" to (v as Number).toDouble())
        is String -> mapOf("stringValue" to v)
        is List<*> -> mapOf("arrayValue" to mapOf("values" to v.map { encode(it) }))
        is Map<*, *> -> mapOf("mapValue" to mapOf("fields" to v.entries.associate { it.key.toString() to encode(it.value) }))
        else -> throw IllegalArgumentException("Unsupported Firestore value ${v::class}")
    }

    fun decodeFields(fields: Map<String, Any?>): Map<String, Any?> = fields.mapValues { decode(it.value) }

    fun decode(v: Any?): Any? {
        val m = v as? Map<*, *> ?: return null
        return when {
            m.containsKey("integerValue") -> (m["integerValue"] as? String)?.toLongOrNull() ?: (m["integerValue"] as? Number)?.toLong()
            m.containsKey("doubleValue") -> (m["doubleValue"] as? Number)?.toDouble()
            m.containsKey("stringValue") -> m["stringValue"] as? String
            m.containsKey("booleanValue") -> m["booleanValue"] as? Boolean
            m.containsKey("timestampValue") -> m["timestampValue"] as? String
            m.containsKey("arrayValue") -> ((m["arrayValue"] as? Map<*, *>)?.get("values") as? List<*>)?.map { decode(it) } ?: emptyList<Any?>()
            m.containsKey("mapValue") -> ((m["mapValue"] as? Map<*, *>)?.get("fields") as? Map<*, *>)
                ?.entries?.associate { it.key.toString() to decode(it.value) } ?: emptyMap<String, Any?>()
            else -> null
        }
    }
}
