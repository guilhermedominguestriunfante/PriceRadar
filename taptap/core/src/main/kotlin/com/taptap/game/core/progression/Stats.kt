package com.taptap.game.core.progression

/** Lifetime statistics (spec §20, §24). Mutable; persisted in the save file. */
class Stats {
    var totalTaps = 0L
    var totalTouches = 0L
    var matches = 0
    var wins = 0
    var bestScore = 0L
    var maxCombo = 0
    var maxTps = 0f
    var perfects = 0L
    var stopErrors = 0L
    var stopsSurvived = 0L
    var zoneHits = 0L
    var coinsEarned = 0L
    var playTimeMs = 0L
    var frenzies = 0L
    var megaFrenzies = 0L
    var bossesDefeated = 0
    var goldenHits = 0
    /** Best REFLEX reaction (ms), or -1 when never measured. */
    var reflexBestMs = -1L
    /** Stages won that had interrupts and zero STOP errors. */
    var flawlessStages = 0
    var dailyCompleted = 0

    val avgTps: Float get() = if (playTimeMs <= 0) 0f else totalTouches * 1000f / playTimeMs
}
