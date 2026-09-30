package com.dedonervoso.core.progression

/** One ranked result (spec §22): an Arena match. */
class RankEntry(
    val score: Long,
    val stage: Int,
    val maxCombo: Int,
    val maxTps: Float,
    val taps: Long,
    val timestamp: Long,
    val dayIndex: Long,
    val daily: Boolean = false,
    /** Flagged by [ResultValidator]; shown but marked. */
    val suspicious: Boolean = false,
)

enum class RankScope { ALL_TIME, WEEK, TODAY }

/**
 * Source of ranked results. The first version only has [LocalLeaderboard]; a global, friends or
 * seasonal source can implement this interface later without touching the UI (spec §22).
 */
interface LeaderboardSource {
    fun top(scope: RankScope, today: Long, limit: Int): List<RankEntry>
}

class LocalLeaderboard(private val entries: MutableList<RankEntry>) : LeaderboardSource {

    /** Inserts [entry]; returns its all-time rank (1-based) or -1 if it did not make the list. */
    fun submit(entry: RankEntry): Int {
        if (entry.score <= 0) return -1
        val idx = entries.indexOfFirst { entry.score > it.score }.let { if (it < 0) entries.size else it }
        entries.add(idx, entry)
        // Keep the best MAX entries overall plus recent ones so daily/weekly views stay populated.
        while (entries.size > MAX_ENTRIES) {
            val removable = entries.indices.reversed().firstOrNull { entries[it].dayIndex < entry.dayIndex - 7 }
            entries.removeAt(removable ?: entries.lastIndex)
        }
        val rank = entries.indexOf(entry)
        return if (rank in 0 until MAX_ALL_TIME_RANKED) rank + 1 else -1
    }

    override fun top(scope: RankScope, today: Long, limit: Int): List<RankEntry> = entries.asSequence()
        .filter {
            when (scope) {
                RankScope.ALL_TIME -> true
                RankScope.WEEK -> it.dayIndex > today - 7
                RankScope.TODAY -> it.dayIndex == today
            }
        }
        .take(limit)
        .toList()

    val best: RankEntry? get() = entries.firstOrNull { !it.suspicious }

    companion object {
        const val MAX_ENTRIES = 60
        const val MAX_ALL_TIME_RANKED = 50
    }
}
