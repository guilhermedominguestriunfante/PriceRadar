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
) {
    /** Set by [com.dedonervoso.core.progression.ResultValidator]. */
    var suspicious: Boolean = false
}
