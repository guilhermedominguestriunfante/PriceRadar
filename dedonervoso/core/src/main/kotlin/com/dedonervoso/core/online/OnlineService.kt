package com.dedonervoso.core.online

import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields
import java.util.Random

/** Online identity and what was last synced, persisted in the save (see SaveData.online). */
class OnlineAccount(
    var uid: String = "",
    /** Long-lived Firebase refresh token (private app storage). */
    var refreshToken: String = "",
    /** Friend code, 6 characters without look-alikes (shown as K7P-3QX). */
    var code: String = "",
    val friends: MutableList<String> = ArrayList(),
    var bestScore: Long = 0L,
    var bestTaps: Long = 0L,
    var bestTps10: Long = 0L,
    var bestStage: Long = 0L,
    var weekId: String = "",
    var weekBest: Long = 0L,
    /** Bests or friends changed locally but aren't on the server yet (retried on the next sync). */
    var dirty: Boolean = false,
) {
    val exists: Boolean get() = uid.isNotEmpty() && refreshToken.isNotEmpty()

    /** Independent copy, so a background sync never races the UI thread's view of the save. */
    fun copy() = OnlineAccount(uid, refreshToken, code, ArrayList(friends), bestScore, bestTaps, bestTps10, bestStage, weekId, weekBest, dirty)

    fun set(from: OnlineAccount) {
        uid = from.uid; refreshToken = from.refreshToken; code = from.code
        friends.clear(); friends.addAll(from.friends)
        bestScore = from.bestScore; bestTaps = from.bestTaps; bestTps10 = from.bestTps10; bestStage = from.bestStage
        weekId = from.weekId; weekBest = from.weekBest; dirty = from.dirty
    }

    fun clear() {
        uid = ""; refreshToken = ""; code = ""; friends.clear()
        bestScore = 0L; bestTaps = 0L; bestTps10 = 0L; bestStage = 0L; weekId = ""; weekBest = 0L; dirty = false
    }
}

/** How the player appears online (nickname and avatar come from the local profile). */
class OnlineProfile(val nick: String, val avatar: Int, val level: Int)

/** A match to publish. */
class OnlineResult(val score: Long, val taps: Long, val tps: Float, val stage: Int)

enum class OnlineBoard { GLOBAL, WEEK, FRIENDS }
enum class OnlineMetric { SCORE, TAPS }

class OnlineEntry(
    val uid: String, val nick: String, val avatar: Int, val score: Long, val taps: Long,
    val tps: Float, val stage: Long, val isMe: Boolean,
)

/**
 * Game-level online features on [FirebaseClient]: an anonymous account with a unique friend code,
 * best results and weekly bests, global / weekly / friends rankings and friends by code. The
 * Firestore rules (firebase/firestore.rules) enforce ownership and plausible values server-side.
 * Blocking — call from a background thread. Mutates the given [OnlineAccount] as it syncs.
 */
class OnlineService(
    private val client: FirebaseClient,
    private val appVersion: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = SecureRandom(),
) {
    private var session: AuthSession? = null

    /**
     * Signs in, creating the account (and friend code) on first use or when the old one is gone,
     * and publishes the profile plus the bests the account doesn't have yet.
     */
    fun connect(account: OnlineAccount, profile: OnlineProfile, localBest: OnlineResult?) {
        val s = signIn(account)
        val doc = client.get(s, "players/${s.uid}")
        if (doc == null) {
            createPlayer(s, account, profile, localBest)
            return
        }
        account.code = doc.string("code")
        account.friends.clear()
        account.friends.addAll(doc.strings("friends"))
        account.bestScore = maxOf(account.bestScore, doc.long("bestScore"))
        account.bestTaps = maxOf(account.bestTaps, doc.long("bestTaps"))
        account.bestTps10 = maxOf(account.bestTps10, doc.long("bestTps10"))
        account.bestStage = maxOf(account.bestStage, doc.long("bestStage"))
        if (localBest != null && improves(account, localBest)) {
            raiseBests(account, localBest)
            account.dirty = true
        }
        val serverBehind = doc.long("bestScore") < account.bestScore || doc.long("bestTaps") < account.bestTaps ||
            doc.long("bestTps10") < account.bestTps10 || doc.long("bestStage") < account.bestStage
        val changed = doc.string("nick") != profile.nick || doc.long("avatar").toInt() != profile.avatar ||
            doc.long("level").toInt() != profile.level || doc.long("appVersion") != appVersion
        if (changed || serverBehind || account.dirty) writePlayer(s, account, profile)
    }

    /** Publishes a finished match: raises the player's bests and this week's best when beaten. */
    fun submit(account: OnlineAccount, profile: OnlineProfile, result: OnlineResult) {
        val s = signIn(account)
        if (improves(account, result)) {
            raiseBests(account, result)
            account.dirty = true
        }
        if (account.dirty) writePlayer(s, account, profile)
        val week = isoWeek(clock())
        if (account.weekId != week) {
            account.weekId = week
            account.weekBest = 0L
        }
        if (result.score > account.weekBest) {
            client.commit(
                s,
                listOf(
                    FsWrite(
                        "weeks/$week/scores/${s.uid}",
                        mapOf(
                            "nick" to profile.nick, "avatar" to profile.avatar, "score" to result.score, "stage" to result.stage,
                            "taps" to result.taps.coerceIn(0L, 3_000L), "tps10" to tps10(result.tps), "appVersion" to appVersion,
                        ),
                        serverTimestamps = listOf("updatedAt"),
                    ),
                ),
            )
            account.weekBest = result.score
        }
    }

    fun leaderboard(account: OnlineAccount, board: OnlineBoard, metric: OnlineMetric, limit: Int = 50): List<OnlineEntry> {
        val s = signIn(account)
        return when (board) {
            OnlineBoard.GLOBAL -> client.topBy(s, "", "players", if (metric == OnlineMetric.TAPS) "bestTaps" else "bestScore", limit)
                .map { playerEntry(it, s.uid) }
            OnlineBoard.WEEK -> client.topBy(s, "weeks/${isoWeek(clock())}", "scores", if (metric == OnlineMetric.TAPS) "taps" else "score", limit)
                .map { d ->
                    OnlineEntry(d.id, d.string("nick"), d.long("avatar").toInt(), d.long("score"), d.long("taps"), d.long("tps10") / 10f, d.long("stage"), d.id == s.uid)
                }
            OnlineBoard.FRIENDS -> client.batchGet(s, (listOf(s.uid) + account.friends).distinct().map { "players/$it" })
                .map { playerEntry(it, s.uid) }
                .sortedByDescending { if (metric == OnlineMetric.TAPS) it.taps else it.score }
        }
    }

    /** Adds the player with friend [code]; returns their nickname. */
    fun addFriend(account: OnlineAccount, profile: OnlineProfile, code: String): String {
        val s = signIn(account)
        val normalized = normalizeCode(code) ?: throw OnlineException(OnlineFailure.NOT_FOUND, "Invalid code")
        val owner = client.get(s, "codes/$normalized")?.string("uid")?.takeIf { it.isNotEmpty() }
            ?: throw OnlineException(OnlineFailure.NOT_FOUND, "Unknown code")
        if (owner == s.uid) throw OnlineException(OnlineFailure.CONFLICT, "Own code")
        val friend = client.get(s, "players/$owner") ?: throw OnlineException(OnlineFailure.NOT_FOUND, "Player gone")
        if (owner !in account.friends) {
            if (account.friends.size >= MAX_FRIENDS) throw OnlineException(OnlineFailure.CONFLICT, "Too many friends")
            account.friends += owner
            account.dirty = true
            writePlayer(s, account, profile)
        }
        return friend.string("nick")
    }

    fun removeFriend(account: OnlineAccount, profile: OnlineProfile, uid: String) {
        val s = signIn(account)
        if (account.friends.remove(uid)) {
            account.dirty = true
            writePlayer(s, account, profile)
        }
    }

    /** Deletes everything online (profile, friend code, this week's score and the account) and forgets it. */
    fun deleteAccount(account: OnlineAccount) {
        if (!account.exists) {
            account.clear()
            return
        }
        val s = signIn(account)
        val writes = ArrayList<FsWrite>()
        writes += FsWrite("players/${s.uid}", emptyMap(), delete = true)
        if (account.code.isNotEmpty()) writes += FsWrite("codes/${account.code}", emptyMap(), delete = true)
        writes += FsWrite("weeks/${isoWeek(clock())}/scores/${s.uid}", emptyMap(), delete = true)
        client.commit(s, writes)
        client.deleteAccount(s)
        session = null
        account.clear()
    }

    // ---- internals ------------------------------------------------------------------------------

    private fun signIn(account: OnlineAccount): AuthSession {
        session?.let { if (it.uid == account.uid) return it }
        if (account.exists) {
            try {
                val s = client.resume(account.uid, account.refreshToken)
                account.refreshToken = s.refreshToken
                session = s
                return s
            } catch (e: OnlineException) {
                if (e.failure == OnlineFailure.NETWORK || e.failure == OnlineFailure.NOT_CONFIGURED) throw e
                // The account was deleted or its token revoked: start over with a new one.
                account.clear()
            }
        }
        val s = client.signUpAnonymously()
        account.uid = s.uid
        account.refreshToken = s.refreshToken
        session = s
        return s
    }

    private fun createPlayer(s: AuthSession, account: OnlineAccount, profile: OnlineProfile, localBest: OnlineResult?) {
        if (localBest != null) raiseBests(account, localBest)
        repeat(CODE_ATTEMPTS) {
            val code = newCode()
            try {
                client.commit(
                    s,
                    listOf(
                        FsWrite("codes/$code", mapOf("uid" to s.uid), mustExist = false),
                        playerWrite(s, account, profile, code, create = true),
                    ),
                )
                account.code = code
                account.dirty = false
                return
            } catch (e: OnlineException) {
                // A taken code fails the whole commit (precondition or rules); try another one.
                if (e.failure != OnlineFailure.CONFLICT && e.failure != OnlineFailure.DENIED) throw e
            }
        }
        throw OnlineException(OnlineFailure.CONFLICT, "Could not reserve a friend code")
    }

    private fun writePlayer(s: AuthSession, account: OnlineAccount, profile: OnlineProfile) {
        client.commit(s, listOf(playerWrite(s, account, profile, account.code, create = false)))
        account.dirty = false
    }

    private fun playerWrite(s: AuthSession, account: OnlineAccount, profile: OnlineProfile, code: String, create: Boolean) = FsWrite(
        "players/${s.uid}",
        mapOf(
            "nick" to profile.nick, "code" to code, "avatar" to profile.avatar,
            "bestScore" to account.bestScore, "bestTaps" to account.bestTaps.coerceIn(0L, 3_000L),
            "bestTps10" to account.bestTps10.coerceIn(0L, 250L), "bestStage" to account.bestStage.coerceIn(0L, 9_999L),
            "level" to profile.level.coerceIn(0, 9_999), "appVersion" to appVersion, "friends" to account.friends.toList(),
        ),
        mustExist = if (create) false else null,
        serverTimestamps = listOf("updatedAt"),
    )

    private fun improves(account: OnlineAccount, r: OnlineResult) =
        r.score > account.bestScore || r.taps > account.bestTaps || tps10(r.tps) > account.bestTps10 || r.stage > account.bestStage

    private fun raiseBests(account: OnlineAccount, r: OnlineResult) {
        account.bestScore = maxOf(account.bestScore, r.score)
        account.bestTaps = maxOf(account.bestTaps, r.taps)
        account.bestTps10 = maxOf(account.bestTps10, tps10(r.tps))
        account.bestStage = maxOf(account.bestStage, r.stage.toLong())
    }

    private fun playerEntry(d: FsDoc, me: String) = OnlineEntry(
        d.id, d.string("nick"), d.long("avatar").toInt(), d.long("bestScore"), d.long("bestTaps"),
        d.long("bestTps10") / 10f, d.long("bestStage"), d.id == me,
    )

    private fun newCode(): String = String(CharArray(6) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val MAX_FRIENDS = 100
        private const val CODE_ATTEMPTS = 6

        fun tps10(tps: Float): Long = (tps * 10f).toLong().coerceIn(0L, 250L)

        /** ISO week in UTC, e.g. "2026-W40": weekly boards reset on Monday 00:00 UTC. */
        fun isoWeek(epochMs: Long): String {
            val d = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
            return "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }

        /** "k7p-3qx", "K7P 3QX" → "K7P3QX"; null when it can't be a code. */
        fun normalizeCode(input: String): String? {
            val c = input.uppercase().filter { it.isLetterOrDigit() }
            return c.takeIf { it.length == 6 && it.all { ch -> ch in CODE_ALPHABET } }
        }

        fun formatCode(code: String): String = if (code.length == 6) code.substring(0, 3) + "-" + code.substring(3) else code
    }
}
