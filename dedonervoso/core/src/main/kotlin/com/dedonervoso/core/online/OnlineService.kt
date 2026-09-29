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
    /** Best score of a match before 1.3 (the "classic record"): kept on the server, never raised again. */
    var bestScore: Long = 0L,
    var bestTaps: Long = 0L,
    var bestTps10: Long = 0L,
    /** Highest stage cleared. */
    var bestStage: Long = 0L,
    /** Arena bests: what the rankings show since 1.3. */
    var arenaBest: Long = 0L,
    var arenaTaps: Long = 0L,
    var arenaTps10: Long = 0L,
    /** ISO week of [arenaWeekBest] on the weekly Arena board. */
    var arenaWeekId: String = "",
    var arenaWeekBest: Long = 0L,
    /** Bests or friends changed locally but aren't on the server yet (retried on the next sync). */
    var dirty: Boolean = false,
) {
    val exists: Boolean get() = uid.isNotEmpty() && refreshToken.isNotEmpty()

    /** Independent copy, so a background sync never races the UI thread's view of the save. */
    fun copy() = OnlineAccount(
        uid, refreshToken, code, ArrayList(friends), bestScore, bestTaps, bestTps10, bestStage,
        arenaBest, arenaTaps, arenaTps10, arenaWeekId, arenaWeekBest, dirty,
    )

    fun set(from: OnlineAccount) {
        uid = from.uid; refreshToken = from.refreshToken; code = from.code
        friends.clear(); friends.addAll(from.friends)
        bestScore = from.bestScore; bestTaps = from.bestTaps; bestTps10 = from.bestTps10; bestStage = from.bestStage
        arenaBest = from.arenaBest; arenaTaps = from.arenaTaps; arenaTps10 = from.arenaTps10
        arenaWeekId = from.arenaWeekId; arenaWeekBest = from.arenaWeekBest; dirty = from.dirty
    }

    fun clear() {
        uid = ""; refreshToken = ""; code = ""; friends.clear()
        bestScore = 0L; bestTaps = 0L; bestTps10 = 0L; bestStage = 0L
        arenaBest = 0L; arenaTaps = 0L; arenaTps10 = 0L; arenaWeekId = ""; arenaWeekBest = 0L; dirty = false
    }
}

/** How the player appears online (nickname and avatar come from the local profile). */
class OnlineProfile(val nick: String, val avatar: Int, val level: Int)

/**
 * A match to publish. Only Arena matches ([arena]) reach the rankings; a campaign match only
 * raises the highest stage cleared ([stage]).
 */
class OnlineResult(val score: Long, val taps: Long, val tps: Float, val stage: Int, val arena: Boolean = true)

enum class OnlineBoard { GLOBAL, WEEK, FRIENDS }
enum class OnlineMetric { SCORE, TAPS }

class OnlineEntry(
    val uid: String, val nick: String, val avatar: Int, val score: Long, val taps: Long,
    val tps: Float, val stage: Long, val isMe: Boolean,
)

/** Where a score stands on this week's Arena board: [above] players scored more, out of [total]. */
class BoardStanding(val above: Long, val total: Long) {
    /** "Top X%": 1–100, the share of the board at or above this score. */
    val topPercent: Int get() = if (total <= 0L) 100 else (((above + 1) * 100 + total - 1) / total).toInt().coerceIn(1, 100)
}

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
        account.arenaBest = maxOf(account.arenaBest, doc.long("arenaBest"))
        account.arenaTaps = maxOf(account.arenaTaps, doc.long("arenaTaps"))
        account.arenaTps10 = maxOf(account.arenaTps10, doc.long("arenaTps10"))
        if (localBest != null && improves(account, localBest)) {
            raiseBests(account, localBest)
            account.dirty = true
        }
        val serverBehind = doc.long("bestScore") < account.bestScore || doc.long("bestTaps") < account.bestTaps ||
            doc.long("bestTps10") < account.bestTps10 || doc.long("bestStage") < account.bestStage ||
            doc.long("arenaBest") < account.arenaBest || doc.long("arenaTaps") < account.arenaTaps || doc.long("arenaTps10") < account.arenaTps10
        val changed = doc.string("nick") != profile.nick || doc.long("avatar").toInt() != profile.avatar ||
            doc.long("level").toInt() != profile.level || doc.long("appVersion") != appVersion
        if (changed || serverBehind || account.dirty) writePlayer(s, account, profile)
    }

    /**
     * Publishes a finished match. An Arena match raises the player's Arena bests and this week's
     * Arena board; a campaign match only raises the highest stage cleared.
     */
    fun submit(account: OnlineAccount, profile: OnlineProfile, result: OnlineResult) {
        val s = signIn(account)
        if (improves(account, result)) {
            raiseBests(account, result)
            account.dirty = true
        }
        if (account.dirty) writePlayer(s, account, profile)
        if (!result.arena) return
        val week = isoWeek(clock())
        if (account.arenaWeekId != week) {
            account.arenaWeekId = week
            account.arenaWeekBest = 0L
        }
        if (result.score > account.arenaWeekBest) {
            client.commit(
                s,
                listOf(
                    FsWrite(
                        "$ARENA_WEEKS/$week/scores/${s.uid}",
                        mapOf(
                            "nick" to profile.nick, "avatar" to profile.avatar, "score" to result.score, "stage" to account.bestStage,
                            "taps" to result.taps.coerceIn(0L, 3_000L), "tps10" to tps10(result.tps), "appVersion" to appVersion,
                        ),
                        serverTimestamps = listOf("updatedAt"),
                    ),
                ),
            )
            account.arenaWeekBest = result.score
        }
    }

    /** Rankings of the Arena: all-time bests, this week's board, or friends by their bests. */
    fun leaderboard(account: OnlineAccount, board: OnlineBoard, metric: OnlineMetric, limit: Int = 50): List<OnlineEntry> {
        val s = signIn(account)
        return when (board) {
            OnlineBoard.GLOBAL -> client.topBy(s, "", "players", if (metric == OnlineMetric.TAPS) "arenaTaps" else "arenaBest", limit)
                .map { playerEntry(it, s.uid) }
            OnlineBoard.WEEK -> client.topBy(s, "$ARENA_WEEKS/${isoWeek(clock())}", "scores", if (metric == OnlineMetric.TAPS) "taps" else "score", limit)
                .map { d ->
                    OnlineEntry(d.id, d.string("nick"), d.long("avatar").toInt(), d.long("score"), d.long("taps"), d.long("tps10") / 10f, d.long("stage"), d.id == s.uid)
                }
            OnlineBoard.FRIENDS -> client.batchGet(s, (listOf(s.uid) + account.friends).distinct().map { "players/$it" })
                .map { playerEntry(it, s.uid) }
                .sortedByDescending { if (metric == OnlineMetric.TAPS) it.taps else it.score }
        }
    }

    /** Where [score] stands on this week's Arena board ("top X%"). */
    fun weekStanding(account: OnlineAccount, score: Long): BoardStanding {
        val s = signIn(account)
        val parent = "$ARENA_WEEKS/${isoWeek(clock())}"
        val above = client.count(s, parent, "scores", "score", score)
        val total = client.count(s, parent, "scores", "score", 0L)
        return BoardStanding(above, maxOf(total, above + 1))
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
        writes += FsWrite("$ARENA_WEEKS/${isoWeek(clock())}/scores/${s.uid}", emptyMap(), delete = true)
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

    private fun playerWrite(s: AuthSession, account: OnlineAccount, profile: OnlineProfile, code: String, create: Boolean): FsWrite {
        val fields = linkedMapOf<String, Any?>(
            "nick" to profile.nick, "code" to code, "avatar" to profile.avatar,
            "bestScore" to account.bestScore, "bestTaps" to account.bestTaps.coerceIn(0L, 3_000L),
            "bestTps10" to account.bestTps10.coerceIn(0L, 250L), "bestStage" to account.bestStage.coerceIn(0L, 9_999L),
            "level" to profile.level.coerceIn(0, 9_999), "appVersion" to appVersion, "friends" to account.friends.toList(),
        )
        // Only players who played the Arena carry its fields, so only they are on its boards.
        if (account.arenaBest > 0L) {
            fields["arenaBest"] = account.arenaBest
            fields["arenaTaps"] = account.arenaTaps.coerceIn(0L, 3_000L)
            fields["arenaTps10"] = account.arenaTps10.coerceIn(0L, 250L)
        }
        return FsWrite("players/${s.uid}", fields, mustExist = if (create) false else null, serverTimestamps = listOf("updatedAt"))
    }

    private fun improves(account: OnlineAccount, r: OnlineResult) = r.stage > account.bestStage ||
        r.arena && (r.score > account.arenaBest || r.taps > account.arenaTaps || tps10(r.tps) > account.arenaTps10)

    private fun raiseBests(account: OnlineAccount, r: OnlineResult) {
        account.bestStage = maxOf(account.bestStage, r.stage.toLong())
        if (!r.arena) return
        account.arenaBest = maxOf(account.arenaBest, r.score)
        account.arenaTaps = maxOf(account.arenaTaps, r.taps)
        account.arenaTps10 = maxOf(account.arenaTps10, tps10(r.tps))
    }

    private fun playerEntry(d: FsDoc, me: String) = OnlineEntry(
        d.id, d.string("nick"), d.long("avatar").toInt(), d.long("arenaBest"), d.long("arenaTaps"),
        d.long("arenaTps10") / 10f, d.long("bestStage"), d.id == me,
    )

    private fun newCode(): String = String(CharArray(6) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

    companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val MAX_FRIENDS = 100
        private const val CODE_ATTEMPTS = 6

        /** Weekly Arena boards: `arenaWeeks/{week}/scores/{uid}`. */
        const val ARENA_WEEKS = "arenaWeeks"

        fun tps10(tps: Float): Long = (tps * 10f).toLong().coerceIn(0L, 250L)

        /** ISO week in UTC, e.g. "2026-W40": weekly boards reset on Monday 00:00 UTC. */
        fun isoWeek(epochMs: Long): String {
            val d = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
            return String.format(java.util.Locale.ROOT, "%04d-W%02d", d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        }

        /** The same ISO week as a number (e.g. 202640): the Arena's seed for that week. */
        fun weekIndex(epochMs: Long): Long {
            val d = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
            return d.get(IsoFields.WEEK_BASED_YEAR) * 100L + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }

        /** "k7p-3qx", "K7P 3QX" → "K7P3QX"; null when it can't be a code. */
        fun normalizeCode(input: String): String? {
            val c = input.uppercase().filter { it.isLetterOrDigit() }
            return c.takeIf { it.length == 6 && it.all { ch -> ch in CODE_ALPHABET } }
        }

        fun formatCode(code: String): String = if (code.length == 6) code.substring(0, 3) + "-" + code.substring(3) else code
    }
}
