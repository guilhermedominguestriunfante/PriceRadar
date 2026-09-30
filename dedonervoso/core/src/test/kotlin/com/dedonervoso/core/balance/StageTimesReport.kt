package com.dedonervoso.core.balance

import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.stage.StageType
import kotlin.math.ceil

/**
 * Measures what [com.dedonervoso.core.stage.StageTimes] and
 * [StageCatalog.BOSS_HEALTH_FACTOR] should be: the simulated players' graded times per stage
 * (2 stars ≈ average player's median, 3 stars ≈ skilled player's median) and the score an
 * average player makes against a boss in 45 s.
 *
 * Run: ./gradlew :core:test --tests '*StageTimesReport*' -Dcalibrate=true --rerun
 * (the report is printed and written to the temp dir as dedo-stage-times.txt).
 */
class StageTimesReport {
    @org.junit.jupiter.api.Test
    fun report() {
        if (System.getProperty("calibrate") != "true") return
        val runs = 40
        val sb = StringBuilder()
        val t2s = ArrayList<Int>()
        val t3s = ArrayList<Int>()
        val fractions = HashMap<StageType, MutableList<Pair<Double, Double>>>()
        sb.append("stage type       | avg pass  avg t   | skilled pass  t   | casual pass\n")
        for (n in 1..LAST) {
            val st = StageCatalog.stage(n)
            val lo = BalanceSimulationTest.expectedLoadout(n)
            fun times(p: BotProfile): Pair<Double, List<Long>> {
                val wins = ArrayList<Long>()
                for (i in 0 until runs) {
                    val r = BotPlayer.play(st, lo, p, seed = n * 7_919L + i)
                    if (r.won) wins += r.gradeTimeMs
                }
                wins.sort()
                return wins.size.toDouble() / runs to wins
            }
            val (avgPass, avg) = times(BotProfile.AVERAGE)
            val (skPass, sk) = times(BotProfile.SKILLED)
            val (caPass, _) = times(BotProfile.CASUAL)
            fun median(l: List<Long>) = if (l.isEmpty()) 60_000L else l[l.size / 2]
            // Tenths of a second, rounded up to half seconds; 3 stars strictly faster than 2.
            val limit = (st.durationMs / 100).toInt()
            val t2 = (ceil(median(avg) / 500.0).toInt() * 5).coerceAtMost(limit)
            val t3 = (ceil(median(sk) / 500.0).toInt() * 5).coerceAtMost(t2 - 5)
            t2s += t2
            t3s += t3
            if (n > 40 && st.type != StageType.SURVIVAL) fractions.getOrPut(st.type) { ArrayList() } += (t2 / 600.0) to (t3 / 600.0)
            sb.append(
                "%5d %-10s | %4.0f%%  %5.1fs | %4.0f%%  %5.1fs | %4.0f%%\n".format(
                    n, st.type.name, avgPass * 100, t2 / 10.0, skPass * 100, t3 / 10.0, caPass * 100,
                ),
            )
        }
        sb.append("\nSTAR2 (tenths of s) = ").append(t2s.joinToString(", ")).append('\n')
        sb.append("STAR3 (tenths of s) = ").append(t3s.joinToString(", ")).append('\n')
        sb.append("\nper-type fractions of 60 s (stages 41..$LAST):\n")
        for ((type, l) in fractions) {
            val f2 = l.map { it.first }.sorted()[l.size / 2]
            val f3 = l.map { it.second }.sorted()[l.size / 2]
            sb.append("  %-10s 2★ %.3f  3★ %.3f\n".format(type.name, f2, f3))
        }

        // Boss health: the score an average player makes in 45 s, relative to expectedScore.
        sb.append("\nboss: avg score at 45 s / expectedScore\n")
        val ratios = ArrayList<Double>()
        for (n in listOf(10, 20, 30, 40, 50, 60)) {
            val st = endless(StageCatalog.stage(n))
            val lo = BalanceSimulationTest.expectedLoadout(n)
            val scores = (0 until runs).map { BotPlayer.play(st, lo, BotProfile.AVERAGE, n * 31L + it, abortAtMs = 45_000L).score }.sorted()
            val ratio = scores[scores.size / 2].toDouble() / StageCatalog.expectedScore(n)
            ratios += ratio
            sb.append("  %3d %s: %.3f\n".format(n, st.boss, ratio))
        }
        sb.append("  suggested BOSS_HEALTH_FACTOR = %.2f\n".format(ratios.sorted()[ratios.size / 2]))

        java.io.File(System.getProperty("java.io.tmpdir"), "dedo-stage-times.txt").writeText(sb.toString())
        println(sb)
    }

    /** The same boss stage with a boss that can't be beaten (to sample scores over time). */
    private fun endless(s: StageConfig) = StageConfig(
        number = s.number, type = s.type, target = Int.MAX_VALUE / 2, scoreTarget = Int.MAX_VALUE / 2,
        star2Score = s.star2Score, star3Score = s.star3Score, lives = 99, stop = s.stop, zones = s.zones,
        frenzy = s.frenzy, comboTimeoutMs = s.comboTimeoutMs, penaltyTier = s.penaltyTier, isBoss = true,
        seed = s.seed, endOnObjective = true, boss = s.boss, bossTier = s.bossTier,
    )

    private companion object {
        const val LAST = 60
    }
}
