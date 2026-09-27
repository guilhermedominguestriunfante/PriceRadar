package com.taptap.game.core.balance

import com.taptap.game.core.engine.GameState
import com.taptap.game.core.engine.Loadout
import com.taptap.game.core.stage.StageCatalog
import com.taptap.game.core.stage.StageConfig

/**
 * Raw performance of the simulated players per stage (objectives ignored), used to size stage
 * targets and star thresholds. Run: ./gradlew :core:test --tests '*CalibrationReport*' -Dcalibrate=true
 */
object Calibration {
    class Stat(val mean: Double, val p25: Double)

    fun measure(stage: StageConfig, loadout: Loadout, profile: BotProfile, runs: Int): Map<String, Stat> {
        val values = HashMap<String, MutableList<Double>>()
        fun add(k: String, v: Number) = values.getOrPut(k) { ArrayList() }.add(v.toDouble())
        for (i in 0 until runs) {
            val r = BotPlayer.play(stage, loadout, profile, seed = stage.number * 7_919L + i)
            add("taps", r.taps)
            add("score", r.score)
            add("combo", r.maxCombo)
            add("zones", r.zoneHits)
            add("perfects", r.perfects)
            add("frenzies", r.frenzies)
            add("errors", r.stopErrors)
            add("alive", if (r.livesLeft > 0 || stage.lives == 0) 1 else 0)
        }
        return values.mapValues { (_, l) ->
            l.sort()
            Stat(l.average(), l[(l.size * 0.25).toInt().coerceAtMost(l.lastIndex)])
        }
    }
}

class CalibrationReport {
    @org.junit.jupiter.api.Test
    fun report() {
        if (System.getProperty("calibrate") != "true") return
        val sb = StringBuilder()
        sb.append("stage type       | profile  taps(mean/p25)  score(mean/p25)  combo  zones  perf  frz  err  alive\n")
        for (n in 1..60) {
            val st = StageCatalog.stage(n)
            val lo = BalanceSimulationTest.expectedLoadout(n)
            for (p in listOf(BotProfile.CASUAL, BotProfile.AVERAGE, BotProfile.SKILLED)) {
                val m = Calibration.measure(st, lo, p, 24)
                fun f(k: String) = "%.0f/%.0f".format(m[k]!!.mean, m[k]!!.p25)
                sb.append(
                    "%5d %-10s | %-8s %-15s %-16s %5.0f %6.1f %5.1f %4.1f %4.2f %4.2f\n".format(
                        n, st.type.name, p.name, f("taps"), f("score"), m["combo"]!!.mean, m["zones"]!!.mean,
                        m["perfects"]!!.mean, m["frenzies"]!!.mean, m["errors"]!!.mean, m["alive"]!!.mean,
                    ),
                )
            }
        }
        java.io.File(System.getProperty("java.io.tmpdir"), "taptap-calibration.txt").writeText(sb.toString())
        println(sb)
        check(GameState.values().isNotEmpty())
    }
}
