package com.dedonervoso.core.engine

/**
 * Effects of the player's permanent upgrades, as consumed by the engine. Built from upgrade
 * levels by [com.dedonervoso.core.progression.Upgrades.loadout].
 */
data class Loadout(
    /** Value of one tap: 1, 2 with DOUBLE TAP, 3 with TRIPLE TAP. */
    val tapValue: Int = 1,
    val comboBoostLevel: Int = 0,
    val comboShields: Int = 0,
    val stopShields: Int = 0,
    val coinBoostLevel: Int = 0,
    val zoneBoostLevel: Int = 0,
    val frenzyBoostLevel: Int = 0,
    val critBoostLevel: Int = 0,
) {
    val comboThresholdScale: Float get() = 1f - GameBalance.COMBO_BOOST_PER_LEVEL * comboBoostLevel
    val zoneMultiplierBonus: Float get() = 1f + GameBalance.ZONE_MULT_PER_BOOST_LEVEL * zoneBoostLevel
    val zoneRadiusScale: Float get() = 1f + GameBalance.ZONE_RADIUS_PER_BOOST_LEVEL * zoneBoostLevel
    val frenzyDurationMs: Long get() = GameBalance.FRENZY_DURATION_MS + GameBalance.FRENZY_BOOST_DURATION_MS * frenzyBoostLevel
    val frenzyMultiplier: Float get() = GameBalance.FRENZY_MULTIPLIER + GameBalance.FRENZY_BOOST_MULT * frenzyBoostLevel
    val frenzyFillScale: Float get() = 1f + GameBalance.FRENZY_BOOST_FILL * frenzyBoostLevel
    val perfectRadiusFraction: Float
        get() = GameBalance.PERFECT_RADIUS_FRACTION + GameBalance.PERFECT_RADIUS_PER_CRIT_LEVEL * critBoostLevel
    val perfectBonusScale: Float get() = 1f + GameBalance.PERFECT_BONUS_PER_CRIT_LEVEL * critBoostLevel

    companion object {
        val NONE = Loadout()
    }
}
