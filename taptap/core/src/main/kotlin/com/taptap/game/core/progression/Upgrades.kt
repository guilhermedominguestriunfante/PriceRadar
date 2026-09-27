package com.taptap.game.core.progression

import com.taptap.game.core.engine.Loadout

/** Permanent upgrades sold in the shop (spec §17). The enum name is the persisted id. */
enum class UpgradeId {
    DOUBLE_TAP, TRIPLE_TAP, COMBO_BOOST, COMBO_SHIELD, STOP_SHIELD, COIN_BOOST, HOT_ZONE_BOOST,
    FRENZY_BOOST, CRITICAL_BOOST,
}

class UpgradeDef(
    val id: UpgradeId,
    /** Price of each level: index 0 buys level 1. Grows steeply (spec §18). */
    val costs: IntArray,
    /** Visible/buyable once the player reached this stage (the mechanic exists). */
    val unlockStage: Int = 1,
    /** Minimum player level to buy. */
    val requiresLevel: Int = 1,
    /** Must own this upgrade first. */
    val requires: UpgradeId? = null,
) {
    val maxLevel: Int get() = costs.size
}

enum class BuyCheck { OK, MAXED, LOCKED_STAGE, LOCKED_LEVEL, REQUIRES_OTHER, NOT_ENOUGH_COINS }

object Upgrades {
    val ALL: List<UpgradeDef> = listOf(
        UpgradeDef(UpgradeId.DOUBLE_TAP, intArrayOf(250)),
        UpgradeDef(UpgradeId.COMBO_BOOST, intArrayOf(60, 180, 450, 1_100, 2_500)),
        UpgradeDef(UpgradeId.COIN_BOOST, intArrayOf(80, 240, 600, 1_400, 3_200)),
        UpgradeDef(UpgradeId.COMBO_SHIELD, intArrayOf(150, 500, 1_500)),
        UpgradeDef(UpgradeId.FRENZY_BOOST, intArrayOf(120, 350, 900, 2_100, 4_500), unlockStage = 4),
        UpgradeDef(UpgradeId.STOP_SHIELD, intArrayOf(200, 650, 1_800), unlockStage = 6),
        UpgradeDef(UpgradeId.HOT_ZONE_BOOST, intArrayOf(150, 400, 1_000, 2_400, 5_000), unlockStage = 11),
        UpgradeDef(UpgradeId.CRITICAL_BOOST, intArrayOf(180, 500, 1_200, 2_800, 6_000), unlockStage = 13),
        UpgradeDef(UpgradeId.TRIPLE_TAP, intArrayOf(3_000), requiresLevel = 10, requires = UpgradeId.DOUBLE_TAP),
    )

    private val byId = ALL.associateBy { it.id }

    fun def(id: UpgradeId): UpgradeDef = byId.getValue(id)

    /** Price of the next level, or null when maxed. */
    fun nextCost(id: UpgradeId, level: Int): Int? = def(id).costs.getOrNull(level)

    fun check(id: UpgradeId, levels: Map<UpgradeId, Int>, coins: Long, highestStage: Int, playerLevel: Int): BuyCheck {
        val d = def(id)
        val level = levels[id] ?: 0
        if (level >= d.maxLevel) return BuyCheck.MAXED
        if (highestStage < d.unlockStage) return BuyCheck.LOCKED_STAGE
        if (d.requires != null && (levels[d.requires] ?: 0) == 0) return BuyCheck.REQUIRES_OTHER
        if (playerLevel < d.requiresLevel) return BuyCheck.LOCKED_LEVEL
        if (coins < d.costs[level]) return BuyCheck.NOT_ENOUGH_COINS
        return BuyCheck.OK
    }

    fun loadout(levels: Map<UpgradeId, Int>): Loadout {
        fun lv(id: UpgradeId) = (levels[id] ?: 0).coerceIn(0, def(id).maxLevel)
        val tapValue = when {
            lv(UpgradeId.TRIPLE_TAP) > 0 -> 3
            lv(UpgradeId.DOUBLE_TAP) > 0 -> 2
            else -> 1
        }
        return Loadout(
            tapValue = tapValue,
            comboBoostLevel = lv(UpgradeId.COMBO_BOOST),
            comboShields = lv(UpgradeId.COMBO_SHIELD),
            stopShields = lv(UpgradeId.STOP_SHIELD),
            coinBoostLevel = lv(UpgradeId.COIN_BOOST),
            zoneBoostLevel = lv(UpgradeId.HOT_ZONE_BOOST),
            frenzyBoostLevel = lv(UpgradeId.FRENZY_BOOST),
            critBoostLevel = lv(UpgradeId.CRITICAL_BOOST),
        )
    }

    /** Total coins needed to max everything (for tests/balance reports). */
    val totalCost: Int get() = ALL.sumOf { it.costs.sum() }
}
