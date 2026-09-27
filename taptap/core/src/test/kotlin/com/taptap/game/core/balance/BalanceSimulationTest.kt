package com.taptap.game.core.balance

import com.taptap.game.core.engine.Loadout
import com.taptap.game.core.stage.StageCatalog
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Balance report: simulated players of three skill levels play every stage many times.
 * Prints pass rates and stars (see build output / test report) and checks the curve's shape:
 * early stages are welcoming, nothing is impossible for skilled players, and skill matters.
 */
class BalanceSimulationTest {

    companion object {
    /** Upgrades a typical player owns around stage [n]. */
    fun expectedLoadout(n: Int) = Loadout(
        tapValue = if (n >= 35) 3 else if (n >= 8) 2 else 1,
        comboBoostLevel = (n / 8).coerceAtMost(4),
        comboShields = if (n >= 12) 1 else 0,
        stopShields = if (n >= 15) 1 else 0,
        zoneBoostLevel = if (n >= 16) (n - 12) / 8 else 0,
        frenzyBoostLevel = if (n >= 8) (n / 10).coerceAtMost(3) else 0,
        critBoostLevel = if (n >= 16) 1 else 0,
    )
    }

    private class Row(val stage: Int, val pass: Map<String, Float>, val stars: Map<String, Float>, val score: Map<String, Long>)

    private fun simulate(stages: IntRange, runs: Int): List<Row> = stages.map { n ->
        val stage = StageCatalog.stage(n)
        val pass = HashMap<String, Float>()
        val stars = HashMap<String, Float>()
        val score = HashMap<String, Long>()
        for (p in listOf(BotProfile.CASUAL, BotProfile.AVERAGE, BotProfile.SKILLED)) {
            var wins = 0
            var starSum = 0
            var scoreSum = 0L
            for (i in 0 until runs) {
                val r = BotPlayer.play(stage, expectedLoadout(n), p, seed = n * 1_000L + i)
                if (r.won) wins++
                starSum += r.stars
                scoreSum += r.score
            }
            pass[p.name] = wins.toFloat() / runs
            stars[p.name] = starSum.toFloat() / runs
            score[p.name] = scoreSum / runs
        }
        Row(n, pass, stars, score)
    }

    @Test
    fun difficultyCurveIsFairAndSkillMatters() {
        val rows = simulate(1..45, runs = 16)
        val sb = StringBuilder("\nstage type        target  | pass% casual/avg/skilled | stars c/a/s     | score avg\n")
        for (r in rows) {
            val st = StageCatalog.stage(r.stage)
            sb.append(
                String.format(
                    "%5d %-10s %7d  | %4.0f %4.0f %4.0f            | %.1f %.1f %.1f     | %d\n",
                    r.stage, st.type.name, if (st.type.name == "BOSS") st.scoreTarget else st.target,
                    r.pass["casual"]!! * 100, r.pass["average"]!! * 100, r.pass["skilled"]!! * 100,
                    r.stars["casual"], r.stars["average"], r.stars["skilled"], r.score["average"],
                ),
            )
        }
        println(sb)
        java.io.File(System.getProperty("java.io.tmpdir"), "taptap-balance.txt").writeText(sb.toString())

        val first = rows.take(5)
        assertTrue(first.all { it.pass["casual"]!! >= 0.6f }, "first stages must welcome casual players")
        assertTrue(first.all { it.pass["average"]!! >= 0.85f }, "first stages are easy for average players")
        assertTrue(rows.all { it.pass["skilled"]!! >= 0.5f }, "no stage is a wall for skilled players")
        assertTrue(rows.count { it.pass["average"]!! >= 0.5f } >= rows.size * 0.75, "average players progress")
        assertTrue(rows.sumOf { it.stars["skilled"]!!.toDouble() } > rows.sumOf { it.stars["casual"]!!.toDouble() }, "skill matters")
    }
}
