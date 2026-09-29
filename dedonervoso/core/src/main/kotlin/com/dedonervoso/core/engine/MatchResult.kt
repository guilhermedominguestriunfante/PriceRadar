package com.dedonervoso.core.engine

import com.dedonervoso.core.stage.StageType

/** Immutable summary of a finished match, consumed by progression, records and the result screen. */
class MatchResult(
    val stageNumber: Int,
    val stageType: StageType,
    val isBoss: Boolean,
    val won: Boolean,
    val failReason: FailReason,
    val stars: Int,
    val score: Long,
    /** TAPs made (each tap counts its value: 2 with DOUBLE TAP). */
    val taps: Long,
    /** Physical valid touches. */
    val touches: Int,
    val tapValue: Int,
    val maxCombo: Int,
    val maxTps: Float,
    val avgTps: Float,
    val perfects: Int,
    val zoneHits: Int,
    val interrupts: Int,
    val stopsSurvived: Int,
    val stopErrors: Int,
    val frenzies: Int,
    val megaFrenzies: Int,
    /** Coins collected during play (zones, perfects, golden, mega frenzy). */
    val coinsCollected: Int,
    val goldenHits: Int,
    /** Best REFLEX reaction in ms, or -1. */
    val reflexBestMs: Long,
    val timeBonusMs: Long,
    val playedMs: Long,
    val livesLeft: Int,
    val objectiveProgress: Long,
    val objectiveTarget: Long,
    val droppedTaps: Int,
    val badTimestamps: Int,
    /** Touches that were errors: outside a mandatory zone (STOP faults are [stopErrors]). */
    val misses: Int = 0,
    val bossHits: Int = 0,
    /** The time limit when the match ended (60 s, plus TIME zones, minus stolen time). */
    val limitMs: Long = playedMs,
    /** The match ended the moment the objective was met, before the time limit. */
    val endedEarly: Boolean = false,
) {
    /** Set by [com.dedonervoso.core.progression.ResultValidator]. */
    var suspicious: Boolean = false

    /** The time that grades a stage: play time plus [GameBalance.STOP_ERROR_TIME_PENALTY_MS] per STOP error. */
    val gradeTimeMs: Long get() = playedMs + GameBalance.STOP_ERROR_TIME_PENALTY_MS * stopErrors

    /** Share of touches that were not errors, 0..1 (1 when nothing was touched). */
    val precision: Float
        get() {
            val errors = stopErrors + misses
            val total = touches + errors
            return if (total <= 0) 1f else touches.toFloat() / total
        }
}
