package com.taptap.game.core.engine

/**
 * Every gameplay tuning parameter in one place (spec §42). Stage specific values (STOP
 * frequency, zone rates, targets…) are derived in [com.taptap.game.core.stage.StageCatalog]
 * from the curves below; economy and XP live in [com.taptap.game.core.progression.Economy].
 */
object GameBalance {
    // ---- Match -----------------------------------------------------------------------------
    const val MATCH_DURATION_MS = 60_000L
    const val COUNTDOWN_STEP_MS = 800L
    const val COUNTDOWN_MS = COUNTDOWN_STEP_MS * 3
    const val TIME_BONUS_MS = 3_000L
    const val TIME_BONUS_CAP_MS = 9_000L
    val TIME_WARNINGS_S = intArrayOf(10, 5, 4, 3, 2, 1)

    // ---- Input / anti-cheat (spec §37, §50) ----------------------------------------------
    /** Taps from a third simultaneous finger are ignored (two thumbs is the natural maximum). */
    const val MAX_ACTIVE_POINTERS = 2
    /** Token bucket: short bursts allowed, sustained rate capped at 1000 / [TAP_REFILL_MS] taps/s. */
    const val TAP_BUCKET_CAPACITY = 4
    const val TAP_REFILL_MS = 40L
    const val MAX_HUMAN_TPS = 25f
    const val SUSPICIOUS_DROPPED_TAPS = 40
    /** Events stamped further than this in the future are rejected as inconsistent. */
    const val MAX_FUTURE_EVENT_MS = 100L
    const val TPS_WINDOW_MS = 1_500L
    /** Minimum taps in the window before a TPS value counts for records. */
    const val TPS_MIN_SAMPLES = 6

    // ---- Combo (spec §7) -------------------------------------------------------------------
    val COMBO_THRESHOLDS = intArrayOf(0, 10, 25, 50, 100, 200, 350)
    val COMBO_MULTIPLIERS = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f)
    const val COMBO_BOOST_PER_LEVEL = 0.08f
    const val COMBO_ZONE_EXTRA = 3
    const val PERFECT_COMBO_EXTRA = 2

    // ---- Hot zones (spec §10, §12) ---------------------------------------------------------
    /** Finger-size generosity applied to zone radii for hit tests. */
    const val ZONE_HIT_TOLERANCE = 1.12f
    const val PERFECT_RADIUS_FRACTION = 0.30f
    const val PERFECT_RADIUS_PER_CRIT_LEVEL = 0.03f
    const val PERFECT_BONUS_TAPS = 2
    const val PERFECT_BONUS_PER_CRIT_LEVEL = 0.15f
    const val PERFECTS_PER_COIN = 4
    const val ZONE_MULT_PER_BOOST_LEVEL = 0.10f
    const val ZONE_RADIUS_PER_BOOST_LEVEL = 0.04f
    const val ZONE_EXPIRE_GRACE_MS = 120L
    const val ZONE_BASE_RADIUS = 0.13f
    const val ZONE_CRITICAL_RADIUS = 0.07f
    const val ZONE_GOLDEN_RADIUS = 0.085f
    /** Zones spawn outside this disc around the arena centre, where the HUD core shows the combo (moving zones may still cross it). */
    const val CORE_CLEAR_RADIUS = 0.15f
    const val GOLDEN_COINS_MIN = 25
    const val GOLDEN_COINS_MAX = 60
    const val GOLDEN_LIFETIME_MS = 2_200L
    const val TELEPORT_PERIOD_MS = 1_300L

    // ---- Frenzy (spec §11, §57) ------------------------------------------------------------
    const val FRENZY_DURATION_MS = 5_000L
    const val FRENZY_MULTIPLIER = 2f
    const val FRENZY_BOOST_DURATION_MS = 500L
    const val FRENZY_BOOST_MULT = 0.25f
    const val FRENZY_BOOST_FILL = 0.05f
    const val MEGA_FRENZY_DURATION_MS = 6_500L
    const val MEGA_FRENZY_MULTIPLIER = 4f
    const val MEGA_FRENZY_CHANCE = 0.03f
    const val MEGA_FRENZY_TAPS_PER_COIN = 4
    const val FRENZY_FILL_TAP = 0.0055f
    const val FRENZY_FILL_ZONE = 0.012f
    const val FRENZY_FILL_PERFECT = 0.035f
    const val FRENZY_FILL_COMBO_TIER = 0.05f
    const val FRENZY_DECAY_DELAY_MS = 1_200L
    const val FRENZY_DECAY_PER_SECOND = 0.06f

    // ---- STOP & interrupts (spec §8, §9, §41) ---------------------------------------------
    /** No interrupt warns before this much play time… */
    const val INTERRUPT_EARLIEST_MS = 6_000L
    /** …and none starts in the final stretch. */
    const val INTERRUPT_END_MARGIN_MS = 2_000L
    const val INTERRUPT_AFTER_FRENZY_MS = 1_500L
    /** Physiological floor for reaction grace: taps this soon after STOP are never punished. */
    const val MIN_REACTION_GRACE_MS = 150L
    const val FAKE_STOP_BONUS_MS = 2_000L
    const val FAKE_STOP_MULTIPLIER = 2f
    const val REFLEX_READY_MS = 700L
    const val REFLEX_WAIT_MIN_MS = 700L
    const val REFLEX_WAIT_MAX_MS = 2_200L
    const val REFLEX_GO_WINDOW_MS = 1_500L
    /** Penalty = max(min points, fraction of score). */
    val STOP_PENALTY_FRACTION = floatArrayOf(0.05f, 0.08f, 0.12f)
    val STOP_PENALTY_MIN_POINTS = intArrayOf(10, 25, 50)
    const val STOP_REPEAT_PENALTY_POINTS = 5
    /** TAPs lost on MEDIUM/HEAVY faults, multiplied by the tap value (spec: "perder TAPs"). */
    const val STOP_TAP_LOSS = 5

    // ---- Combo timer -------------------------------------------------------------------------
    const val COMBO_TIMEOUT_EASY_MS = 2_000L
    const val COMBO_TIMEOUT_HARD_MS = 900L
}
