package com.dedonervoso.core.stage

import com.dedonervoso.core.engine.BossKind
import com.dedonervoso.core.engine.GameBalance
import com.dedonervoso.core.engine.PenaltyTier
import com.dedonervoso.core.engine.ZoneType

/** Stage objective families (spec §14). */
enum class StageType {
    /** Make X TAPs. */
    SPEED,
    /** Reach a score. */
    SCORE,
    /** Reach a combo. */
    COMBO,
    /** Hit hot zones X times. */
    PRECISION,
    /** Get through N STOPs (errors allowed = lives − 1). */
    SURVIVAL,
    /** Land X perfect taps. */
    PERFECT,
    /** Trigger FRENZY X times. */
    FRENZY,
    /** Beat the boss: bring its health (the score target) to zero and keep at least one life. */
    BOSS,
}

/** Mechanics that get an introduction banner the first time they appear (spec §31 "ensinar jogando"). */
enum class Mechanic {
    TAP, COMBO, COINS, FRENZY, STOP, BOSS, HOT_ZONES, PERFECT, REFLEX, SPECIAL_ZONES,
    MOVING_ZONES, LOCK_ZONES, FAKE_STOP, CRITICAL_ZONES,
}

class StopConfig(
    val countMin: Int,
    val countMax: Int,
    val durationMinMs: Long,
    val durationMaxMs: Long,
    /** Telegraph before the STOP becomes active (visual + audio warning). */
    val warningMs: Long,
    /** Reaction grace after STOP starts; taps inside it are ignored, never punished. */
    val graceMs: Long,
    /** Probability that an interrupt is a REFLEX (READY/WAIT/TAP) event. */
    val reflexChance: Float = 0f,
    /** Probability that an interrupt is a FAKE STOP (warning that resolves into GO!). */
    val fakeChance: Float = 0f,
    val minGapMs: Long = 5_000L,
    /** At least this many interrupts are holds (STOP or REFLEX), never FAKE (SURVIVAL stages). */
    val minHolds: Int = 0,
) {
    init {
        require(countMin in 0..countMax) { "bad STOP count range" }
        require(minHolds <= countMin) { "more holds required than planned" }
        require(durationMinMs in 1..durationMaxMs) { "bad STOP duration range" }
        require(graceMs >= GameBalance.MIN_REACTION_GRACE_MS) { "grace below human reaction floor" }
    }
}

class ZoneConfig(
    val spawnMinMs: Long,
    val spawnMaxMs: Long,
    val maxConcurrent: Int,
    val lifeMinMs: Long,
    val lifeMaxMs: Long,
    /** Spawn weights indexed by [ZoneType.ordinal]. */
    val weights: IntArray,
    val radiusScale: Float = 1f,
    val driftChance: Float = 0f,
    val orbitChance: Float = 0f,
    val teleportChance: Float = 0f,
    val shrinkChance: Float = 0f,
    /** Chance that a multiplier zone is mandatory: taps outside it are misses. */
    val lockChance: Float = 0f,
    val goldenChance: Float = 0f,
    /** Movement speed in arena widths per second. */
    val speed: Float = 0.18f,
    val perfectEnabled: Boolean = true,
) {
    init {
        require(weights.size == ZoneType.values().size) { "weights must cover every ZoneType" }
        require(maxConcurrent >= 1)
    }

    fun weight(type: ZoneType) = weights[type.ordinal]

    companion object {
        fun weights(vararg pairs: Pair<ZoneType, Int>): IntArray {
            val w = IntArray(ZoneType.values().size)
            for ((t, v) in pairs) w[t.ordinal] = v
            return w
        }
    }
}

class FrenzyConfig(
    /** Multiplies meter gain (FRENZY stages fill faster). */
    val fillScale: Float = 1f,
    val megaChance: Float = GameBalance.MEGA_FRENZY_CHANCE,
)

/** Fully resolved parameters for one stage. Built by [StageCatalog]. */
class StageConfig(
    /** 1-based stage number. Negative numbers are special modes (daily challenge). */
    val number: Int,
    val type: StageType,
    /** Objective target; for SURVIVAL it is the number of errors allowed. */
    val target: Int,
    /** BOSS stages also require this score (for others it is informative). */
    val scoreTarget: Int,
    val star2Score: Int,
    val star3Score: Int,
    val durationMs: Long = GameBalance.MATCH_DURATION_MS,
    /** 0 = no lives; otherwise the match fails when they run out. */
    val lives: Int = 0,
    val stop: StopConfig? = null,
    val zones: ZoneConfig? = null,
    val frenzy: FrenzyConfig? = null,
    val comboTimeoutMs: Long = GameBalance.COMBO_TIMEOUT_EASY_MS,
    val penaltyTier: PenaltyTier = PenaltyTier.LIGHT,
    val isBoss: Boolean = false,
    val introduces: Mechanic? = null,
    val seed: Long = number.toLong(),
    /** Custom label for special stages (e.g. daily challenge). */
    val customTitle: String? = null,
    /**
     * The match ends as soon as the objective is met (campaign and daily challenge): the time
     * limit is only a maximum. Arena and duels always play the full time.
     */
    val endOnObjective: Boolean = false,
    /**
     * Graded time (see [com.dedonervoso.core.engine.MatchResult.gradeTimeMs]) for 2 and 3 stars.
     * 0 = stars come from [star2Score]/[star3Score] instead.
     */
    val star2TimeMs: Long = 0L,
    val star3TimeMs: Long = 0L,
    /** The boss of a BOSS stage (null elsewhere). */
    val boss: BossKind? = null,
    val bossTier: Int = 0,
) {
    val timedStars: Boolean get() = star3TimeMs > 0L
    val hasStop: Boolean get() = stop != null
    val hasZones: Boolean get() = zones != null
    val hasFrenzy: Boolean get() = frenzy != null
    val hasPerfect: Boolean get() = zones?.perfectEnabled == true
}
