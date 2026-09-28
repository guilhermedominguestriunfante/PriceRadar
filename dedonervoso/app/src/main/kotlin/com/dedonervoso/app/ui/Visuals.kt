package com.dedonervoso.app.ui

import com.dedonervoso.core.engine.ZoneType
import com.dedonervoso.core.progression.MissionKind
import com.dedonervoso.core.progression.UpgradeId
import com.dedonervoso.core.stage.StageType

/** Colour and icon language shared by every screen. */
object Visuals {
    fun typeColor(t: StageType): Int = when (t) {
        StageType.SPEED -> Palette.CYAN
        StageType.SCORE -> Palette.GOLD
        StageType.COMBO -> Palette.MAGENTA
        StageType.PRECISION -> Palette.GREEN
        StageType.SURVIVAL -> Palette.ORANGE
        StageType.PERFECT -> Palette.GOLD
        StageType.FRENZY -> Palette.PURPLE
        StageType.BOSS -> Palette.RED
    }

    fun typeIcon(t: StageType): Icon = when (t) {
        StageType.SPEED -> Icon.BOLT
        StageType.SCORE -> Icon.TROPHY
        StageType.COMBO -> Icon.COMBO
        StageType.PRECISION -> Icon.TARGET
        StageType.SURVIVAL -> Icon.SHIELD
        StageType.PERFECT -> Icon.SPARKLE
        StageType.FRENZY -> Icon.FLAME
        StageType.BOSS -> Icon.SKULL
    }

    fun zoneColor(t: ZoneType): Int = when (t) {
        ZoneType.X2 -> Palette.CYAN
        ZoneType.X3 -> Palette.MAGENTA
        ZoneType.X5 -> Palette.ORANGE
        ZoneType.COIN -> Palette.GOLD
        ZoneType.COMBO -> Palette.GREEN
        ZoneType.TIME -> Palette.BLUE
        ZoneType.CRITICAL -> Palette.RED
        ZoneType.GOLDEN -> Palette.GOLD
    }

    fun zoneSprite(t: ZoneType): Int = when (t) {
        ZoneType.X2 -> Palette.S_CYAN
        ZoneType.X3 -> Palette.S_MAGENTA
        ZoneType.X5 -> Palette.S_ORANGE
        ZoneType.COIN, ZoneType.GOLDEN -> Palette.S_GOLD
        ZoneType.COMBO -> Palette.S_GREEN
        ZoneType.TIME -> Palette.S_BLUE
        ZoneType.CRITICAL -> Palette.S_RED
    }

    fun upgradeIcon(id: UpgradeId): Icon = when (id) {
        UpgradeId.DOUBLE_TAP, UpgradeId.TRIPLE_TAP -> Icon.BOLT
        UpgradeId.COMBO_BOOST -> Icon.COMBO
        UpgradeId.COMBO_SHIELD -> Icon.SHIELD
        UpgradeId.STOP_SHIELD -> Icon.HAND
        UpgradeId.COIN_BOOST -> Icon.COIN
        UpgradeId.HOT_ZONE_BOOST -> Icon.TARGET
        UpgradeId.FRENZY_BOOST -> Icon.FLAME
        UpgradeId.CRITICAL_BOOST -> Icon.SPARKLE
    }

    fun upgradeColor(id: UpgradeId): Int = when (id) {
        UpgradeId.DOUBLE_TAP -> Palette.CYAN
        UpgradeId.TRIPLE_TAP -> Palette.MAGENTA
        UpgradeId.COMBO_BOOST -> Palette.MAGENTA
        UpgradeId.COMBO_SHIELD -> Palette.GREEN
        UpgradeId.STOP_SHIELD -> Palette.RED
        UpgradeId.COIN_BOOST -> Palette.GOLD
        UpgradeId.HOT_ZONE_BOOST -> Palette.GREEN
        UpgradeId.FRENZY_BOOST -> Palette.PURPLE
        UpgradeId.CRITICAL_BOOST -> Palette.ORANGE
    }

    fun missionIcon(k: MissionKind): Icon = when (k) {
        MissionKind.TAPS -> Icon.BOLT
        MissionKind.PLAY_MATCHES -> Icon.PLAY
        MissionKind.WIN_STAGES -> Icon.TROPHY
        MissionKind.REACH_COMBO -> Icon.COMBO
        MissionKind.SCORE_IN_MATCH -> Icon.STAR
        MissionKind.REACH_TPS -> Icon.BOLT
        MissionKind.EARN_STARS -> Icon.STAR
        MissionKind.FRENZIES -> Icon.FLAME
        MissionKind.SURVIVE_STOPS -> Icon.HAND
        MissionKind.ZONE_HITS -> Icon.TARGET
        MissionKind.PERFECTS -> Icon.SPARKLE
    }

    fun achievementIcon(name: String): Icon = when (name) {
        "tap", "bolt" -> Icon.BOLT
        "trophy" -> Icon.TROPHY
        "stop" -> Icon.HAND
        "shield" -> Icon.SHIELD
        "target" -> Icon.TARGET
        "combo" -> Icon.COMBO
        "flame" -> Icon.FLAME
        "coin" -> Icon.COIN
        "skull" -> Icon.SKULL
        "star" -> Icon.STAR
        "flag" -> Icon.CROWN
        "level" -> Icon.UP
        "clock" -> Icon.CLOCK
        "calendar" -> Icon.CALENDAR
        else -> Icon.MEDAL
    }

    /** Avatar colours (the avatar is a glyph drawn in this colour). */
    val AVATAR_COLORS = intArrayOf(
        Palette.CYAN, Palette.MAGENTA, Palette.GOLD, Palette.GREEN, Palette.PURPLE, Palette.ORANGE,
        Palette.RED, Palette.BLUE, Palette.CYAN, Palette.MAGENTA, Palette.GOLD, Palette.GREEN,
    )
    val AVATAR_ICONS = arrayOf(
        Icon.BOLT, Icon.FLAME, Icon.CROWN, Icon.STAR, Icon.SKULL, Icon.SPARKLE,
        Icon.HEART, Icon.TARGET, Icon.SHIELD, Icon.MUSIC, Icon.TROPHY, Icon.COMBO,
    )
}
