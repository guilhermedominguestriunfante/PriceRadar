package com.dedonervoso.core

import com.dedonervoso.core.engine.FailReason
import com.dedonervoso.core.engine.MatchResult
import com.dedonervoso.core.progression.GameClock
import com.dedonervoso.core.stage.StageType

/** Fixed clock: day index advances every 86 400 000 ms of [now]. */
class FakeClock(var now: Long = 1_700_000_000_000L) : GameClock {
    override fun nowMs() = now
    override fun dayIndex(ms: Long) = Math.floorDiv(ms, 86_400_000L)
    fun advanceDays(days: Int) { now += days * 86_400_000L }
}

fun result(
    stage: Int = 1,
    won: Boolean = true,
    stars: Int = if (won) 1 else 0,
    score: Long = 500,
    taps: Long = 300,
    touches: Int = 300,
    tapValue: Int = 1,
    maxCombo: Int = 80,
    maxTps: Float = 8f,
    perfects: Int = 0,
    zoneHits: Int = 0,
    interrupts: Int = 0,
    stopsSurvived: Int = 0,
    stopErrors: Int = 0,
    frenzies: Int = 0,
    coins: Int = 0,
    isBoss: Boolean = false,
    type: StageType = StageType.SPEED,
    playedMs: Long = 60_000,
    failReason: FailReason = if (won) FailReason.NONE else FailReason.OBJECTIVE,
) = MatchResult(
    stageNumber = stage, stageType = type, isBoss = isBoss, won = won, failReason = failReason, stars = stars,
    score = score, taps = taps, touches = touches, tapValue = tapValue, maxCombo = maxCombo, maxTps = maxTps,
    avgTps = touches * 1000f / playedMs, perfects = perfects, zoneHits = zoneHits, interrupts = interrupts,
    stopsSurvived = stopsSurvived, stopErrors = stopErrors, frenzies = frenzies, megaFrenzies = 0,
    coinsCollected = coins, goldenHits = 0, reflexBestMs = -1, timeBonusMs = 0, playedMs = playedMs, livesLeft = 0,
    objectiveProgress = taps, objectiveTarget = 100, droppedTaps = 0, badTimestamps = 0,
)
