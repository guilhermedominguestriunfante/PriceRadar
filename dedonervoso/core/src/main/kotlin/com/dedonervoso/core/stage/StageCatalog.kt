package com.dedonervoso.core.stage

import com.dedonervoso.core.engine.BossKind
import com.dedonervoso.core.engine.GameBalance
import com.dedonervoso.core.engine.PenaltyTier
import com.dedonervoso.core.engine.ZoneType
import com.dedonervoso.core.util.Rng
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

    /** Stage number of live duels (outside the ladder). */
    const val DUEL = -2

    /**
     * The live duel arena, played by both players with the same seed and no upgrades: a
     * mid-ladder mix of STOP, REFLEX, FAKE STOP, multiplier zones and frenzy, plus the duel orbs.
     * No TIME or COIN zones, so both matches last as long and stay about the duel.
     */
    fun duel(): StageConfig = StageConfig(
        number = DUEL,
        type = StageType.SCORE,
        target = 0,
        scoreTarget = 0,
        star2Score = 0,
        star3Score = 0,
        stop = StopConfig(3, 4, 1_000, 2_000, warningMs = 450, graceMs = 260, reflexChance = 0.15f, fakeChance = 0.2f, minGapMs = 6_000),
        zones = ZoneConfig(
            spawnMinMs = 3_000, spawnMaxMs = 4_600, maxConcurrent = 2, lifeMinMs = 2_800, lifeMaxMs = 4_200,
            weights = ZoneConfig.weights(ZoneType.X2 to 10, ZoneType.X3 to 5, ZoneType.X5 to 2, ZoneType.COMBO to 3),
            driftChance = 0.25f,
        ),
        frenzy = FrenzyConfig(),
        comboTimeoutMs = 1_500L,
        penaltyTier = PenaltyTier.MEDIUM,
        seed = Rng.mix(0x7A97A9L, 0xD0E1L),
    )

    /** Stage number of the Arena (outside the ladder). */
    const val ARENA = -3

    /** Stage number of the daily challenge. */
    const val DAILY = -1

    /**
     * The weekly Arena: always the full 60 s (no early end, no TIME zones), no upgrades, and the
     * same seed for everyone during an ISO week ([weekIndex], e.g. from
     * [com.dedonervoso.core.online.OnlineService.weekIndex]), so scores compare fairly. It feeds the
     * online rankings.
     */
    fun arena(weekIndex: Long): StageConfig = StageConfig(
        number = ARENA,
        type = StageType.SCORE,
        target = 0,
        scoreTarget = 0,
        star2Score = 0,
        star3Score = 0,
        stop = StopConfig(4, 5, 1_000, 2_000, warningMs = 420, graceMs = 250, reflexChance = 0.2f, fakeChance = 0.2f, minGapMs = 5_000),
        zones = ZoneConfig(
            spawnMinMs = 2_600, spawnMaxMs = 4_000, maxConcurrent = 2, lifeMinMs = 2_600, lifeMaxMs = 4_000,
            weights = ZoneConfig.weights(ZoneType.X2 to 10, ZoneType.X3 to 5, ZoneType.X5 to 2, ZoneType.COMBO to 3, ZoneType.CRITICAL to 2),
            driftChance = 0.35f, orbitChance = 0.15f, shrinkChance = 0.2f, lockChance = 0.12f,
        ),
        frenzy = FrenzyConfig(),
        comboTimeoutMs = 1_400L,
        penaltyTier = PenaltyTier.MEDIUM,
        seed = Rng.mix(0xA7E7AL, weekIndex),
    )

    fun isBoss(number: Int) = number > 0 && number % BOSS_EVERY == 0

    fun stage(number: Int): StageConfig {
        require(number >= 1) { "Stage numbers start at 1" }
        return if (number <= HANDCRAFTED) handcrafted(number) else procedural(number)
    }

    // ---- expected-player model used to size targets and stars -------------------------------
    //
    // Calibrated from BotPlayer simulations (core/src/test/.../balance): the "average" profile
    // (7 TAP/s, 270 ms reactions, aims at zones about half the time) with the upgrades a typical
    // player owns at that point. Re-run CalibrationReport after changing gameplay numbers.

    /** Physical valid touches per match of an average player. */
    fun expectedTouches(n: Int): Float = when {
        n <= 5 -> 414f
        n <= 10 -> 372f
        else -> max(275f, 300f - (n - 11) * 0.45f)
    }

    /** Tap value we assume the player owns by stage [n] (DOUBLE TAP is cheap, TRIPLE is late). */
    fun expectedTapValue(n: Int): Int = when {
        n < 8 -> 1
        n < 35 -> 2
        else -> 3
    }

    /** Average points per TAP (combo tiers, frenzy, zones, perfects) for a decent run. */
    fun expectedMultiplier(n: Int): Float = when {
        n <= 3 -> 3.4f
        n <= 5 -> 4.1f
        n <= 10 -> 3.8f
        n <= 30 -> 10.4f + (n - 11) * 0.27f
        else -> 15.3f + (n - 30) * 0.1f
    }

    fun expectedScore(n: Int): Int = roundTo(expectedTouches(n) * expectedTapValue(n) * expectedMultiplier(n), 10)

    /** Average player's physical taps per second (for daily challenge sizing). */
    fun expectedTps(n: Int): Float = expectedTouches(n) / 58f

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
                spawnMinMs = 3_200, spawnMaxMs = 5_000, maxConcurrent = if (n >= 15 || boss) 2 else 1,
                lifeMinMs = 3_000, lifeMaxMs = 4_500, weights = weights,
                radiusScale = if (n < 13) 1.1f else 1f, goldenChance = if (n >= 12) 0.006f else 0f,
            )
            n <= 30 -> ZoneConfig(
                spawnMinMs = 2_800, spawnMaxMs = 4_400, maxConcurrent = if (boss) 3 else 2,
                lifeMinMs = 2_600, lifeMaxMs = 4_000, weights = weights,
                driftChance = 0.35f, orbitChance = if (n >= 25) 0.15f else 0f,
                teleportChance = if (n >= 27) 0.1f else 0f, shrinkChance = 0.2f,
                lockChance = if (n >= 23) 0.15f else 0f, goldenChance = 0.006f, speed = 0.18f,
            )
            else -> ZoneConfig(
                spawnMinMs = max(2_000L, 2_600L - k * 10L), spawnMaxMs = max(3_400L, 4_200L - k * 12L),
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

    /**
     * SURVIVAL is a STOP gauntlet: two more STOPs than the stage would have, closer together. The
     * objective is to get through all of them (one may be a FAKE where fakes exist).
     */
    private fun survivalStop(s: StopConfig): StopConfig {
        val count = s.countMin + 2
        return StopConfig(
            count, count, s.durationMinMs, s.durationMaxMs, s.warningMs, s.graceMs, s.reflexChance, s.fakeChance,
            minGapMs = max(2_500L, s.minGapMs - 1_500L), minHolds = if (s.fakeChance > 0f) count - 1 else count,
        )
    }

    /**
     * Builds a ladder stage. [target] is the objective (the boss's health for BOSS; ignored for
     * SURVIVAL, whose target is its number of holds); [allowedErrors] only matters for SURVIVAL.
     */
    private fun build(
        n: Int,
        type: StageType,
        target: Int,
        introduces: Mechanic? = null,
        seedSalt: Long = 0L,
        allowedErrors: Int = 1,
    ): StageConfig {
        val boss = type == StageType.BOSS
        val expected = expectedScore(n)
        val scoreTarget = when (type) {
            StageType.SCORE, StageType.BOSS -> target
            else -> roundTo(expected * 0.7f, 10)
        }
        // Score stars only grade matches that play the full time (see StageConfig.timedStars).
        val star2 = max(roundTo(expected * 1.0f, 10), if (type == StageType.SCORE || type == StageType.BOSS) roundTo(target * 1.2f, 10) else 0)
        val star3 = max(roundTo(expected * 1.35f, 10), star2 + 10)
        val baseStop = stopFor(n, boss)
        val stop = if (type == StageType.SURVIVAL && baseStop != null) survivalStop(baseStop) else baseStop
        val lives = when {
            type == StageType.SURVIVAL -> allowedErrors + 1
            boss -> 3
            n > 30 && stop != null -> 3
            else -> 0
        }
        val times = StageTimes.of(n, type)
        return StageConfig(
            number = n,
            type = type,
            target = if (type == StageType.SURVIVAL) stop?.minHolds ?: 0 else target,
            scoreTarget = scoreTarget,
            star2Score = star2,
            star3Score = max(star3, star2 + 10),
            durationMs = if (type == StageType.SURVIVAL) SURVIVAL_DURATION_MS else GameBalance.MATCH_DURATION_MS,
            lives = lives,
            stop = stop,
            zones = zonesFor(n, boss),
            frenzy = frenzyFor(n, type),
            comboTimeoutMs = comboTimeout(n),
            penaltyTier = penaltyFor(n, boss),
            isBoss = boss,
            introduces = introduces,
            seed = Rng.mix(0x7A97A9L, n.toLong() + seedSalt),
            endOnObjective = true,
            star2TimeMs = times.star2Ms,
            star3TimeMs = times.star3Ms,
            boss = if (boss) BossKind.of(n) else null,
            bossTier = if (boss) BossKind.tier(n) else 0,
        )
    }

    /**
     * The boss's health: points to make, sized so an average player needs about 40–45 s. Taps on
     * the boss are worth ×3; the first boss (no zones yet) gets its own factor. Calibrated with
     * StageTimesReport.
     */
    fun bossHealth(n: Int): Int = roundTo(expectedScore(n) * (if (n == BOSS_EVERY) FIRST_BOSS_HEALTH_FACTOR else BOSS_HEALTH_FACTOR), 10)

    private fun speedTarget(n: Int): Int {
        val share = when {
            n <= 5 -> 0.68f
            n <= 10 -> 0.72f
            n <= 30 -> 0.8f
            else -> 0.82f
        }
        return roundTo(expectedTouches(n) * expectedTapValue(n) * share, 10)
    }

    private fun scoreTarget(n: Int): Int {
        val share = when {
            n <= 10 -> 0.62f
            n <= 30 -> 0.62f
            else -> 0.68f
        }
        return roundTo(expectedScore(n) * share, 10)
    }

    private fun handcrafted(n: Int): StageConfig = when (n) {
        1 -> build(1, StageType.SPEED, 150, Mechanic.TAP)
        2 -> build(2, StageType.COMBO, 60, Mechanic.COMBO)
        3 -> build(3, StageType.SCORE, scoreTarget(3), Mechanic.COINS)
        4 -> build(4, StageType.FRENZY, 1, Mechanic.FRENZY)
        5 -> build(5, StageType.SPEED, speedTarget(5))
        6 -> build(6, StageType.SURVIVAL, 0, Mechanic.STOP, allowedErrors = 2)
        7 -> build(7, StageType.SCORE, scoreTarget(7))
        8 -> build(8, StageType.COMBO, 200)
        9 -> build(9, StageType.SPEED, speedTarget(9))
        10 -> build(10, StageType.BOSS, bossHealth(10), Mechanic.BOSS)
        11 -> build(11, StageType.PRECISION, 50, Mechanic.HOT_ZONES)
        12 -> build(12, StageType.SCORE, scoreTarget(12))
        13 -> build(13, StageType.PERFECT, 15, Mechanic.PERFECT)
        14 -> build(14, StageType.COMBO, 330)
        15 -> build(15, StageType.SURVIVAL, 0, allowedErrors = 2)
        16 -> build(16, StageType.SCORE, scoreTarget(16), Mechanic.REFLEX)
        17 -> build(17, StageType.SPEED, speedTarget(17), Mechanic.SPECIAL_ZONES)
        18 -> build(18, StageType.PRECISION, 70)
        19 -> build(19, StageType.FRENZY, 3)
        20 -> build(20, StageType.BOSS, bossHealth(20))
        21 -> build(21, StageType.COMBO, 350, Mechanic.MOVING_ZONES)
        22 -> build(22, StageType.SCORE, scoreTarget(22))
        23 -> build(23, StageType.PRECISION, 80, Mechanic.LOCK_ZONES)
        24 -> build(24, StageType.SURVIVAL, 0, Mechanic.FAKE_STOP, allowedErrors = 1)
        25 -> build(25, StageType.PERFECT, 25, Mechanic.CRITICAL_ZONES)
        26 -> build(26, StageType.SPEED, speedTarget(26))
        27 -> build(27, StageType.SCORE, scoreTarget(27))
        28 -> build(28, StageType.COMBO, 360)
        29 -> build(29, StageType.FRENZY, 4)
        else -> build(30, StageType.BOSS, bossHealth(30))
    }

    private val PROCEDURAL_CYCLE = arrayOf(
        StageType.SCORE, StageType.SPEED, StageType.COMBO, StageType.PRECISION, StageType.SURVIVAL,
        StageType.SCORE, StageType.PERFECT, StageType.FRENZY, StageType.COMBO,
    )

    private fun procedural(n: Int): StageConfig {
        if (isBoss(n)) return build(n, StageType.BOSS, bossHealth(n))
        val k = n - 30
        // Rotate objectives with a seeded offset per block so blocks don't repeat identically.
        val block = (n - 1) / BOSS_EVERY
        val offset = Rng(Rng.mix(0xB10CL, block.toLong())).nextInt(PROCEDURAL_CYCLE.size)
        val type = PROCEDURAL_CYCLE[((n - 1) % BOSS_EVERY + offset) % PROCEDURAL_CYCLE.size]
        val target = when (type) {
            StageType.SCORE -> scoreTarget(n)
            StageType.SPEED -> speedTarget(n)
            StageType.COMBO -> min(600, 340 + k * 2)
            StageType.PRECISION -> min(110, 80 + k / 2)
            StageType.SURVIVAL -> 0
            StageType.PERFECT -> min(45, 25 + k / 3)
            StageType.FRENZY -> min(6, 4 + k / 20)
            StageType.BOSS -> bossHealth(n)
        }
        return build(n, type, target, allowedErrors = if (n % 2 == 0) 1 else 2)
    }

    fun roundTo(value: Float, step: Int): Int = max(step, (value / step).roundToInt() * step)

    const val HANDCRAFTED = 30

    /** Boss health relative to [expectedScore] (see [bossHealth]). */
    const val BOSS_HEALTH_FACTOR = 0.8f
    const val FIRST_BOSS_HEALTH_FACTOR = 1.6f

    /** SURVIVAL stages are shorter: their STOPs are packed into this limit. */
    const val SURVIVAL_DURATION_MS = 48_000L
}
