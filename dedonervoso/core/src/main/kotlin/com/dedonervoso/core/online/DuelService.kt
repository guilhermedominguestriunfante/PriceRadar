package com.dedonervoso.core.online

import java.security.SecureRandom

/** A challenge waiting for this player: [from] invited them to the duel [duelId]. */
class DuelInvite(val from: String, val nick: String, val avatar: Int, val duelId: String, val atMs: Long)

/** One player's live state in a duel room. */
class DuelPlayer(
    val uid: String,
    val nick: String,
    val avatar: Int,
    val score: Long,
    val taps: Long,
    val tps10: Long,
    val done: Boolean,
    /** Server time of their last report. */
    val atMs: Long,
)

/** A sabotage item thrown in a duel ([key] = `<uid>-<n>`, unique per thrower). */
class DuelThrow(val key: String, val from: String, val type: String, val atMs: Long)

/** Snapshot of `duels/<id>` in the Realtime Database. */
class DuelRoom(
    val id: String,
    val host: String,
    val guest: String,
    val seed: Long,
    val createdAtMs: Long,
    /** null until the guest answers. */
    val accepted: Boolean?,
    /** Server time when play starts (after the 3-2-1), once the host set it. */
    val startAtMs: Long?,
    val cancelled: Boolean,
    val players: Map<String, DuelPlayer>,
    val throws: List<DuelThrow>,
) {
    fun opponentOf(uid: String): String = if (uid == host) guest else host

    companion object {
        /** Decodes the streamed/read value; null when the room is gone. */
        fun parse(id: String, value: Any?): DuelRoom? {
            val m = value as? Map<*, *> ?: return null
            val host = m["host"] as? String ?: return null
            val guest = m["guest"] as? String ?: return null
            val players = HashMap<String, DuelPlayer>()
            (m["players"] as? Map<*, *>)?.forEach { (k, v) ->
                val p = v as? Map<*, *> ?: return@forEach
                val uid = k.toString()
                players[uid] = DuelPlayer(
                    uid = uid,
                    nick = p["nick"] as? String ?: "",
                    avatar = (p["avatar"] as? Number)?.toInt() ?: 0,
                    score = (p["score"] as? Number)?.toLong() ?: 0L,
                    taps = (p["taps"] as? Number)?.toLong() ?: 0L,
                    tps10 = (p["tps10"] as? Number)?.toLong() ?: 0L,
                    done = p["done"] as? Boolean ?: false,
                    atMs = (p["at"] as? Number)?.toLong() ?: 0L,
                )
            }
            val throws = ArrayList<DuelThrow>()
            (m["items"] as? Map<*, *>)?.forEach { (k, v) ->
                val t = v as? Map<*, *> ?: return@forEach
                val key = k.toString()
                throws += DuelThrow(key, key.substringBeforeLast('-'), t["type"] as? String ?: "", (t["at"] as? Number)?.toLong() ?: 0L)
            }
            throws.sortWith(compareBy({ it.atMs }, { it.key }))
            return DuelRoom(
                id = id,
                host = host,
                guest = guest,
                seed = (m["seed"] as? Number)?.toLong() ?: 0L,
                createdAtMs = (m["createdAt"] as? Number)?.toLong() ?: 0L,
                accepted = m["accepted"] as? Boolean,
                startAtMs = (m["startAt"] as? Number)?.toLong(),
                cancelled = m["cancelled"] == true,
                players = players,
                throws = throws,
            )
        }
    }
}

/**
 * Live duels on the Realtime Database (see firebase/database.rules.json):
 *
 *  - `duels/<id>`: host, guest, seed, createdAt; then `accepted` (guest), `startAt` (host),
 *    `players/<uid>` (each writes its own score reports) and `items/<uid>-<n>` (throws);
 *  - `invites/<guest>/<host>`: the challenge, shown in the guest's app while it's open.
 *
 * Blocking calls — use a background thread. Times from the server are server-clock ms; [join]
 * and [report] measure the offset between that clock and this device's.
 */
class DuelService(private val db: RealtimeDb, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    /** Opens a room and invites [friendUid]; returns the new duel's id. */
    fun challenge(session: AuthSession, me: OnlineProfile, friendUid: String): String {
        require(friendUid != session.uid) { "Cannot duel yourself" }
        val id = newId()
        db.put(
            session, "duels/$id",
            mapOf(
                "host" to session.uid,
                "guest" to friendUid,
                // Kept below 2^53 so it survives JSON number handling anywhere.
                "seed" to (random.nextLong() ushr 12),
                "createdAt" to RealtimeDb.SERVER_TIMESTAMP,
            ),
        )
        db.put(
            session, "invites/$friendUid/${session.uid}",
            mapOf("duel" to id, "nick" to me.nick, "avatar" to me.avatar, "at" to RealtimeDb.SERVER_TIMESTAMP),
        )
        return id
    }

    /** Host gives up before the start: the invite and the room disappear. */
    fun cancel(session: AuthSession, duelId: String, friendUid: String) {
        runCatching { db.delete(session, "invites/$friendUid/${session.uid}") }
        db.delete(session, "duels/$duelId")
    }

    /** Guest says yes; returns false when the room no longer exists (cancelled or expired). */
    fun accept(session: AuthSession, invite: DuelInvite): Boolean {
        val ok = try {
            db.put(session, "duels/${invite.duelId}/accepted", true)
            true
        } catch (e: OnlineException) {
            if (e.failure != OnlineFailure.DENIED && e.failure != OnlineFailure.NOT_FOUND) throw e
            false
        }
        runCatching { db.delete(session, "invites/${session.uid}/${invite.from}") }
        return ok
    }

    fun decline(session: AuthSession, invite: DuelInvite) {
        runCatching { db.put(session, "duels/${invite.duelId}/accepted", false) }
        db.delete(session, "invites/${session.uid}/${invite.from}")
    }

    /**
     * Puts this player in the room (score 0) and returns the server clock offset (server − local,
     * ms) measured on the way: the reply carries the server's timestamp for the write.
     */
    fun join(session: AuthSession, duelId: String, me: OnlineProfile): Long =
        report(session, duelId, DuelReport(me.nick, me.avatar, 0, 0, 0, false))

    /** Host only, after the guest accepted: play starts at [startAtServerMs] (server clock). */
    fun setStart(session: AuthSession, duelId: String, startAtServerMs: Long) {
        db.put(session, "duels/$duelId/startAt", startAtServerMs)
    }

    /** Publishes this player's progress; returns the clock offset measured by the write. */
    fun report(session: AuthSession, duelId: String, r: DuelReport): Long {
        val sent = clock()
        val stored = db.put(
            session, "duels/$duelId/players/${session.uid}",
            mapOf(
                "nick" to r.nick, "avatar" to r.avatar, "score" to r.score, "taps" to r.taps.coerceIn(0L, 3_000L),
                "tps10" to r.tps10.coerceIn(0L, 250L), "done" to r.done, "at" to RealtimeDb.SERVER_TIMESTAMP,
            ),
        )
        val received = clock()
        val serverAt = ((stored as? Map<*, *>)?.get("at") as? Number)?.toLong() ?: return 0L
        return serverAt - (sent + received) / 2
    }

    /** Throws item number [index] (0-based, per player) of [type] at the opponent. */
    fun throwItem(session: AuthSession, duelId: String, index: Int, type: String) {
        db.put(session, "duels/$duelId/items/${session.uid}-$index", mapOf("type" to type, "at" to RealtimeDb.SERVER_TIMESTAMP))
    }

    /** Removes the room (either player, once both are done or one gave up). */
    fun close(session: AuthSession, duelId: String) {
        db.delete(session, "duels/$duelId")
    }

    /** The room, or null when it is gone (a deleted room is no longer readable, so "denied" means gone too). */
    fun room(session: AuthSession, duelId: String): DuelRoom? = try {
        DuelRoom.parse(duelId, db.get(session, "duels/$duelId"))
    } catch (e: OnlineException) {
        if (e.failure != OnlineFailure.DENIED && e.failure != OnlineFailure.NOT_FOUND) throw e
        null
    }

    /**
     * Follows the room live; [onRoom] gets null once it's gone (the server then also revokes the
     * stream, since only participants of an existing room may read it).
     */
    fun watch(session: AuthSession, duelId: String, onRoom: (DuelRoom?) -> Unit, onError: (OnlineFailure) -> Unit = {}): RealtimeDb.Stream =
        db.stream(session, "duels/$duelId", object : RealtimeDb.Listener {
            override fun onValue(value: Any?) = onRoom(DuelRoom.parse(duelId, value))
            override fun onError(failure: OnlineFailure) {
                if (failure == OnlineFailure.DENIED) onRoom(null) else onError(failure)
            }
        })

    /** Follows the challenges sent to this player (newest first). */
    fun watchInvites(session: AuthSession, onInvites: (List<DuelInvite>) -> Unit, onError: (OnlineFailure) -> Unit = {}): RealtimeDb.Stream =
        db.stream(session, "invites/${session.uid}", object : RealtimeDb.Listener {
            override fun onValue(value: Any?) = onInvites(parseInvites(value))
            override fun onError(failure: OnlineFailure) = onError(failure)
        })

    private fun newId(): String {
        val sb = StringBuilder(ID_LENGTH)
        repeat(ID_LENGTH) { sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
        return sb.toString()
    }

    companion object {
        private const val ID_LENGTH = 20
        private const val ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun parseInvites(value: Any?): List<DuelInvite> {
            val m = value as? Map<*, *> ?: return emptyList()
            return m.mapNotNull { (k, v) ->
                val i = v as? Map<*, *> ?: return@mapNotNull null
                DuelInvite(
                    from = k.toString(),
                    nick = i["nick"] as? String ?: return@mapNotNull null,
                    avatar = (i["avatar"] as? Number)?.toInt() ?: 0,
                    duelId = i["duel"] as? String ?: return@mapNotNull null,
                    atMs = (i["at"] as? Number)?.toLong() ?: 0L,
                )
            }.sortedByDescending { it.atMs }
        }
    }
}

/** What a player publishes about their match (score so far, or final when [done]). */
class DuelReport(val nick: String, val avatar: Int, val score: Long, val taps: Long, val tps10: Long, val done: Boolean)
