package com.taptap.game.core.stage

import com.taptap.game.core.engine.GameBalance
import com.taptap.game.core.engine.PenaltyTier
import com.taptap.game.core.engine.ZoneType
import com.taptap.game.core.util.Rng
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The stage ladder (spec §13–§15). Mechanics are introduced gradually:
 *
 *  1–5   TAP, COMBO, COINS, FRENZY
 *  6–10  STOP (boss at 10)
 *  11–20 HOT ZONES, PERFECT, REFLEX, special zones (boss at 20)
 *  21–30 moving/locked/critical zones, faster STOP, FAKE STOP (boss at 30)
 *  31+   everything mixed; procedurally generated, harder and less predictable
 *
 * Difficulty never grows by raising tap targets alone: warnings shorten, reaction grace
 * shrinks, zones move and shrink, penalties get harsher and objectives rotate.
 */
object StageCatalog {
    const val BOSS_EVERY = 10

    fun isBoss(number: Int) = number > 0 && number % BOSS_EVERY == 0

    fun stage(number: Int): StageConfig {
        require(number >= 1) { "Stage numbers start at 1" }
        return if (number <= HANDCRAFTED) handcrafted(number) else procedural(number)
    }

    // ---- expected-player model used to size targets and stars -------------------------------

    /** Physical taps per second an average improving player sustains around stage [n]. */
    fun expectedTps(n: Int): Float = 5.0f + 2.6f * (1f - exp(-(n - 1) / 15f))

    /** Tap value we assume the player owns by stage [n] (DOUBLE TAP is cheap, TRIPLE is late). */
    fun expectedTapValue(n: Int): Float = when {
        n < 8 -> 1f
        n < 35 -> 1.5f
        else -> 2.2f
    }

    /** Average multiplier (combo, zones, frenzy) for a decent run. */
    fun expectedMultiplier(n: Int): Float = 1.45f + 1.7f * (1f - exp(-(n - 1) / 12f))

    fun expectedScore(n: Int): Int {
        val interruptLoss = if (n >= 6) min(0.12f, 0.04f + n * 0.002f) else 0f
        val touches = 58f * expectedTps(n) * (1f - interruptLoss)
        return roundTo(touches * expectedTapValue(n) * expectedMultiplier(n), 10)
    }

    // ---- building blocks ------------------------------------------------------------------------

    private fun comboTimeout(n: Int): Long = when {
        n <= 5 -> GameBalance.COMBO_TIMEOUT_EASY_MS
        n <= 10 -> 1_700L
        n <= 20 -> 1_500L
        n <= 30 -> 1_300L
        else -> max(GameBalance.COMBO_TIMEOUT_HARD_MS, 1_300L - (n - 30) * 10L)
    }

    private fun stopFor(n: Int, boss: Boolean): StopConfig? = when {
        n < 6 -> null
        n == 6 -> StopConfig(2, 2, 1_600, 2_200, warningMs = 700, graceMs = 320, minGapMs = 8_000)
        n < 10 -> StopConfig(3, 3, 1_400, 2_200, warningMs = 550, graceMs = 300, minGapMs = 6_000)
        n == 10 -> StopConfig(5, 5, 1_000, 2_000, warningMs = 450, graceMs = 280, minGapMs = 4_500)
        n < 16 -> StopConfig(2, 3, 1_200, 2_000, warningMs = 500, graceMs = 280, minGapMs = 6_000)
        n <= 20 -> StopConfig(
            if (boss) 5 else 3, if (boss) 5 else 4, 1_100, 2_000, warningMs = 420, graceMs = 260,
            reflexChance = 0.3f, minGapMs = if (boss) 4_000 else 5_000,
        )
        n <= 30 -> StopConfig(
            if (boss) 5 else 3, if (boss) 6 else 5, 900, 2_000, warningMs = 320, graceMs = 240,
            reflexChance = 0.2f, fakeChance = if (n >= 24) 0.25f else 0f, minGapMs = 3_800,
        )
        else -> {
            val k = n - 30
            StopConfig(
                if (boss) 6 else 4, if (boss) 7 else 6, 800, 2_200,
                warningMs = max(160L, 320L - k * 4L),
                graceMs = max(190L, 240L - k),
                reflexChance = 0.2f, fakeChance = 0.2f,
                minGapMs = max(2_800L, 3_800L - k * 20L),
            )
        }
    }

    private fun zonesFor(n: Int, boss: Boolean): ZoneConfig? {
        if (n < 11) return null
        val weights = ZoneConfig.weights(
            ZoneType.X2 to 10,
            ZoneType.X3 to if (n >= 12) 5 else 0,
            ZoneType.X5 to if (n >= 18) 2 else 0,
            ZoneType.COMBO to if (n >= 14) 3 else 0,
            ZoneType.COIN to if (n >= 17) 3 else 0,
            ZoneType.TIME to if (n >= 17) 1 else 0,
            ZoneType.CRITICAL to if (n >= 25) 2 else 0,
        )
        val k = max(0, n - 30)
        return when {
            n <= 20 -> ZoneConfig(
                spawnMinMs = 1_600, spawnMaxMs = 2_600, maxConcurrent = if (n >= 15 || boss) 2 else 1,
                lifeMinMs = 3_000, lifeMaxMs = 4_500, weights = weights,
                radiusScale = if (n < 13) 1.1f else 1f, goldenChance = if (n >= 12) 0.006f else 0f,
            )
            n <= 30 -> ZoneConfig(
                spawnMinMs = 1_300, spawnMaxMs = 2_300, maxConcurrent = if (boss) 3 else 2,
                lifeMinMs = 2_600, lifeMaxMs = 4_000, weights = weights,
                driftChance = 0.35f, orbitChance = if (n >= 25) 0.15f else 0f,
                teleportChance = if (n >= 27) 0.1f else 0f, shrinkChance = 0.2f,
                lockChance = if (n >= 23) 0.15f else 0f, goldenChance = 0.006f, speed = 0.18f,
            )
            else -> ZoneConfig(
                spawnMinMs = max(900L, 1_200L - k * 6L), spawnMaxMs = max(1_600L, 2_100L - k * 8L),
                maxConcurrent = if (boss) 3 else 2 + (k / 20).coerceAtMost(1),
                lifeMinMs = max(1_800L, 2_400L - k * 8L), lifeMaxMs = max(2_800L, 3_800L - k * 10L),
                weights = weights, radiusScale = max(0.8f, 1f - k * 0.004f),
                driftChance = 0.35f, orbitChance = 0.18f, teleportChance = 0.12f, shrinkChance = 0.3f,
                lockChance = 0.15f, goldenChance = 0.006f, speed = min(0.3f, 0.2f + k * 0.002f),
            )
        }
    }

    private fun frenzyFor(n: Int, type: StageType): FrenzyConfig? =
        if (n < 4) null else FrenzyConfig(fillScale = if (type == StageType.FRENZY) 1.6f else 1f)

    private fun penaltyFor(n: Int, boss: Boolean): PenaltyTier = when {
        n <= 10 -> if (boss) PenaltyTier.MEDIUM else PenaltyTier.LIGHT
        n <= 30 -> PenaltyTier.MEDIUM
        else -> PenaltyTier.HEAVY
    }

    private fun build(
        n: Int,
        type: StageType,
        target: Int,
        introduces: Mechanic? = null,
        seedSalt: Long = 0L,
    ): StageConfig {
        val boss = type == StageType.BOSS
        val expected = expectedScore(n)
        val scoreTarget = when (type) {
            StageType.SCORE -> target
            StageType.BOSS -> target
            else -> roundTo(expected * 0.75f, 10)
        }
        val star2 = when (type) {
            StageType.SCORE, StageType.BOSS -> roundTo(target * 1.25f, 10)
            else -> roundTo(expected * 0.95f, 10)
        }
        val star3 = when (type) {
            StageType.SCORE, StageType.BOSS -> roundTo(target * 1.6f, 10)
            else -> roundTo(expected * 1.35f, 10)
        }
        val stop = stopFor(n, boss)
        val lives = when {
            type == StageType.SURVIVAL -> target + 1
            boss -> 3
            n > 30 && stop != null -> 3
            else -> 0
        }
        return StageConfig(
            number = n,
            type = type,
            target = target,
            scoreTarget = scoreTarget,
            star2Score = star2,
            star3Score = max(star3, star2 + 10),
            lives = lives,
            stop = stop,
            zones = zonesFor(n, boss),
            frenzy = frenzyFor(n, type),
            comboTimeoutMs = comboTimeout(n),
            penaltyTier = penaltyFor(n, boss),
            isBoss = boss,
            introduces = introduces,
            seed = Rng.mix(0x7A97A9L, n.toLong() + seedSalt),
        )
    }

    private fun speedTarget(n: Int): Int {
        val loss = if (n >= 6) 0.1f else 0f
        return roundTo(56f * expectedTps(n) * expectedTapValue(n) * (1f - loss) * 0.82f, 10)
    }

    private fun handcrafted(n: Int): StageConfig = when (n) {
        1 -> build(1, StageType.SPEED, 120, Mechanic.TAP)
        2 -> build(2, StageType.COMBO, 40, Mechanic.COMBO)
        3 -> build(3, StageType.SCORE, roundTo(expectedScore(3) * 0.7f, 10), Mechanic.COINS)
        4 -> build(4, StageType.FRENZY, 1, Mechanic.FRENZY)
        5 -> build(5, StageType.SPEED, speedTarget(5))
        6 -> build(6, StageType.SURVIVAL, 1, Mechanic.STOP)
        7 -> build(7, StageType.SCORE, roundTo(expectedScore(7) * 0.72f, 10))
        8 -> build(8, StageType.COMBO, 80)
        9 -> build(9, StageType.SPEED, speedTarget(9))
        10 -> build(10, StageType.BOSS, roundTo(expectedScore(10) * 0.72f, 10), Mechanic.BOSS)
        11 -> build(11, StageType.PRECISION, 35, Mechanic.HOT_ZONES)
        12 -> build(12, StageType.SCORE, roundTo(expectedScore(12) * 0.72f, 10))
        13 -> build(13, StageType.PERFECT, 10, Mechanic.PERFECT)
        14 -> build(14, StageType.COMBO, 150)
        15 -> build(15, StageType.SURVIVAL, 1)
        16 -> build(16, StageType.SCORE, roundTo(expectedScore(16) * 0.74f, 10), Mechanic.REFLEX)
        17 -> build(17, StageType.SPEED, speedTarget(17), Mechanic.SPECIAL_ZONES)
        18 -> build(18, StageType.PRECISION, 60)
        19 -> build(19, StageType.FRENZY, 3)
        20 -> build(20, StageType.BOSS, roundTo(expectedScore(20) * 0.74f, 10))
        21 -> build(21, StageType.COMBO, 200, Mechanic.MOVING_ZONES)
        22 -> build(22, StageType.SCORE, roundTo(expectedScore(22) * 0.75f, 10))
        23 -> build(23, StageType.PRECISION, 75, Mechanic.LOCK_ZONES)
        24 -> build(24, StageType.SURVIVAL, 0, Mechanic.FAKE_STOP)
        25 -> build(25, StageType.PERFECT, 20, Mechanic.CRITICAL_ZONES)
        26 -> build(26, StageType.SPEED, speedTarget(26))
        27 -> build(27, StageType.SCORE, roundTo(expectedScore(27) * 0.76f, 10))
        28 -> build(28, StageType.COMBO, 280)
        29 -> build(29, StageType.FRENZY, 4)
        else -> build(30, StageType.BOSS, roundTo(expectedScore(30) * 0.76f, 10))
    }

    private val PROCEDURAL_CYCLE = arrayOf(
        StageType.SCORE, StageType.SPEED, StageType.COMBO, StageType.PRECISION, StageType.SURVIVAL,
        StageType.SCORE, StageType.PERFECT, StageType.FRENZY, StageType.COMBO,
    )

    private fun procedural(n: Int): StageConfig {
        if (isBoss(n)) return build(n, StageType.BOSS, roundTo(expectedScore(n) * 0.78f, 10))
        val k = n - 30
        // Rotate objectives with a seeded offset per block so blocks don't repeat identically.
        val block = (n - 1) / BOSS_EVERY
        val offset = Rng(Rng.mix(0xB10CL, block.toLong())).nextInt(PROCEDURAL_CYCLE.size)
        val type = PROCEDURAL_CYCLE[((n - 1) % BOSS_EVERY + offset) % PROCEDURAL_CYCLE.size]
        val target = when (type) {
            StageType.SCORE -> roundTo(expectedScore(n) * 0.78f, 10)
            StageType.SPEED -> speedTarget(n)
            StageType.COMBO -> min(600, 280 + k * 6)
            StageType.PRECISION -> min(160, 75 + k * 2)
            StageType.SURVIVAL -> if (n % 2 == 0) 0 else 1
            StageType.PERFECT -> min(50, 20 + k / 2)
            StageType.FRENZY -> min(6, 4 + k / 15)
            StageType.BOSS -> roundTo(expectedScore(n) * 0.78f, 10)
        }
        return build(n, type, target)
    }

    fun roundTo(value: Float, step: Int): Int = max(step, (value / step).roundToInt() * step)

    const val HANDCRAFTED = 30
}
