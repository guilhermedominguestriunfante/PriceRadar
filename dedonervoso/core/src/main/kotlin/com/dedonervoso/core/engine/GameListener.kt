package com.dedonervoso.core.engine

/**
 * Presentation hooks fired synchronously by [GameSession] (visual effects, audio, haptics).
 * All methods have empty defaults; implementations must not allocate heavily — taps can fire
 * 20+ times per second.
 */
interface GameListener {
    /** 3, 2, 1 during countdowns; 0 when play (re)starts ("TAP!"). */
    fun onCountdown(value: Int, resuming: Boolean) {}
    fun onTap(x: Float, y: Float, points: Int, kind: TapKind, zone: Zone?, comboTier: Int) {}
    fun onTapIgnored(x: Float, y: Float, reason: IgnoreReason) {}
    fun onMiss(x: Float, y: Float) {}
    fun onComboTier(tier: Int, multiplier: Float) {}
    fun onComboBreak(combo: Int, reason: BreakReason) {}
    fun onShieldUsed(type: ShieldType) {}
    fun onInterruptWarning(kind: InterruptKind) {}
    fun onInterruptStart(kind: InterruptKind) {}
    /** STOP finished (or REFLEX said "TAP!"); [clean] = no unshielded fault. */
    fun onInterruptEnd(kind: InterruptKind, clean: Boolean) {}
    fun onFakeStopReveal() {}
    fun onStopFault(x: Float, y: Float, penaltyPoints: Int, lifeLost: Boolean, repeat: Boolean) {}
    fun onReflexResult(reactionMs: Long, bonusPoints: Int, grade: ReflexGrade) {}
    fun onZoneSpawn(zone: Zone) {}
    fun onZoneHit(zone: Zone, perfect: Boolean) {}
    fun onZoneExpire(zone: Zone) {}
    /** Zone consumed by its last allowed hit (TIME, GOLDEN, CRITICAL, COIN). */
    fun onZonePop(zone: Zone) {}
    fun onCoins(amount: Int, x: Float, y: Float) {}
    fun onTimeBonus(ms: Long) {}
    fun onFrenzyReady() {}
    fun onFrenzyStart(mega: Boolean) {}
    fun onFrenzyEnd() {}
    fun onLifeLost(livesLeft: Int) {}
    fun onObjectiveComplete() {}
    fun onTimeWarning(secondsLeft: Int) {}
    fun onPaused() {}
    fun onFinished(result: MatchResult) {}

    companion object {
        val NONE: GameListener = object : GameListener {}
    }
}
