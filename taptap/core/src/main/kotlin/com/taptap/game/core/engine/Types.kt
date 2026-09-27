package com.taptap.game.core.engine

/** Top-level match state machine (spec §39). STOP and FRENZY are exclusive play phases. */
enum class GameState {
    READY,
    COUNTDOWN,
    PLAYING,
    STOP,
    FRENZY,
    PAUSED,
    FINISHED,
    ;

    val isActive: Boolean get() = this == PLAYING || this == STOP || this == FRENZY
}

/** Interrupt events that freeze or challenge tapping. */
enum class InterruptKind {
    /** Stop tapping until "TAP!" appears. */
    STOP,

    /** READY → WAIT… → TAP!: holding still, then reacting as fast as possible. */
    REFLEX,

    /** Same warning as STOP, but it resolves into a "GO!" bonus burst — keep tapping. */
    FAKE_STOP,
}

enum class ZoneType(val multiplier: Float) {
    X2(2f),
    X3(3f),
    X5(5f),
    COIN(1f),
    COMBO(1f),
    TIME(1f),
    CRITICAL(8f),
    GOLDEN(1f),
    ;

    val isMultiplier: Boolean get() = this == X2 || this == X3 || this == X5
    /** Zones that disappear after a limited number of hits. */
    val maxHits: Int
        get() = when (this) {
            TIME, GOLDEN -> 1
            CRITICAL -> 3
            COIN -> 8
            else -> Int.MAX_VALUE
        }
}

enum class ZoneMotion { STATIC, DRIFT, ORBIT, TELEPORT }

enum class BreakReason { TIMEOUT, STOP_FAULT, ZONE_MISS, FALSE_START }

enum class TapKind { NORMAL, ZONE, PERFECT }

enum class IgnoreReason { NOT_PLAYING, REACTION_GRACE, RATE_LIMIT, TOO_MANY_POINTERS, BAD_TIMESTAMP }

enum class ReflexGrade(val bonusTaps: Int) {
    LIGHTNING(40), GREAT(25), GOOD(12), SLOW(4), MISSED(0);

    companion object {
        fun of(reactionMs: Long): ReflexGrade = when {
            reactionMs <= 250 -> LIGHTNING
            reactionMs <= 350 -> GREAT
            reactionMs <= 500 -> GOOD
            else -> SLOW
        }
    }
}

enum class FailReason { NONE, OBJECTIVE, NO_LIVES, ABORTED }

/** How harsh STOP / false-start faults are (spec §8: initial, intermediate, advanced). */
enum class PenaltyTier { LIGHT, MEDIUM, HEAVY }

enum class ShieldType { COMBO, STOP }
