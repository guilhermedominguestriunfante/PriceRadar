package com.taptap.game.core.i18n

import com.taptap.game.core.engine.ReflexGrade
import com.taptap.game.core.progression.DailyTemplate
import com.taptap.game.core.progression.MissionKind
import com.taptap.game.core.progression.UpgradeId
import com.taptap.game.core.stage.Mechanic
import com.taptap.game.core.stage.StageConfig
import com.taptap.game.core.stage.StageType

/**
 * All player-facing text. Game terms (TAP, STOP, COMBO, FRENZY, PERFECT, SCORE…) are kept in
 * English in every language on purpose — they are part of the arcade identity.
 */
abstract class Strings {
    abstract val code: String
    abstract val thousandsSeparator: Char
    abstract val decimalSeparator: Char

    // ---- navigation / common
    abstract val play: String
    abstract val playAgain: String
    abstract val nextStage: String
    abstract val home: String
    abstract val upgrades: String
    abstract val ranking: String
    abstract val achievements: String
    abstract val profile: String
    abstract val settings: String
    abstract val missions: String
    abstract val daily: String
    abstract val stage: String
    abstract val level: String
    abstract val back: String
    abstract val close: String
    abstract val ok: String
    abstract val cancel: String
    abstract val skip: String
    abstract val start: String
    abstract val tapToContinue: String
    abstract val stats: String
    abstract val locked: String
    abstract val boss: String
    abstract val stageSelect: String
    abstract val bestLabel: String
    abstract val objective: String

    // ---- home
    abstract val developer: String
    abstract val developerSubtitle: String
    abstract val tagline: String

    // ---- onboarding
    abstract val onboarding1: String
    abstract val onboarding2: String
    abstract val onboarding3: String
    abstract val ready: String

    // ---- gameplay
    abstract val paused: String
    abstract val resume: String
    abstract val restart: String
    abstract val quit: String
    abstract val wait: String
    abstract val go: String
    abstract val frenzy: String
    abstract val megaFrenzy: String
    abstract val frenzyReady: String
    abstract val perfect: String
    abstract val miss: String
    abstract val comboLost: String
    abstract val shield: String
    abstract val onlyInZone: String
    abstract val objectiveDone: String
    abstract val lastSeconds: String
    abstract val lifeLost: String
    abstract val tpsUnit: String
    abstract fun reflexGrade(grade: ReflexGrade): String

    // ---- results
    abstract val stageClear: String
    abstract val stageFailed: String
    abstract val outOfLives: String
    abstract val newRecord: String
    abstract fun levelUp(level: Int): String
    abstract fun stageUnlocked(stage: Int): String
    abstract fun canBuy(name: String): String
    abstract val dailyDone: String
    abstract val coinsLabel: String
    abstract val xpLabel: String

    // ---- stage objectives
    fun objectiveText(stage: StageConfig): String = objective(stage.type, stage.target, stage.scoreTarget)
    abstract fun objective(type: StageType, target: Int, scoreTarget: Int): String
    fun typeName(type: StageType): String = type.name
    abstract fun mechanicTitle(m: Mechanic): String
    abstract fun mechanicText(m: Mechanic): String

    // ---- shop
    fun upgradeName(id: UpgradeId): String = id.name.replace('_', ' ')
    abstract fun upgradeDescription(id: UpgradeId): String
    abstract val buy: String
    abstract val max: String
    abstract fun upgradeLevel(level: Int, max: Int): String
    abstract fun unlocksAtStage(stage: Int): String
    abstract fun requiresLevel(level: Int): String
    abstract fun requiresUpgrade(name: String): String

    // ---- missions & daily
    abstract fun missionText(kind: MissionKind, target: Int): String
    abstract val claim: String
    abstract fun dailyTitle(t: DailyTemplate): String
    abstract fun dailyReward(coins: Int): String
    abstract fun dailyStreak(days: Int): String
    abstract val dailyCompletedToday: String

    // ---- achievements
    abstract fun achievementTitle(id: String): String
    abstract fun achievementText(id: String): String

    // ---- ranking
    abstract val allTime: String
    abstract val week: String
    abstract val today: String
    abstract val noResults: String
    abstract val flagged: String

    // ---- profile / stats
    abstract val nickname: String
    abstract val avatar: String
    abstract val editName: String
    abstract val statTotalTaps: String
    abstract val statBestScore: String
    abstract val statMaxCombo: String
    abstract val statMaxTps: String
    abstract val statAvgTps: String
    abstract val statPerfects: String
    abstract val statStopErrors: String
    abstract val statStopsSurvived: String
    abstract val statZoneHits: String
    abstract val statCoins: String
    abstract val statPlayTime: String
    abstract val statStages: String
    abstract val statMatches: String
    abstract val statWins: String
    abstract val statFrenzies: String
    abstract val statBosses: String
    abstract val statReflex: String
    abstract val statStars: String

    // ---- settings
    abstract val music: String
    abstract val sfx: String
    abstract val vibration: String
    abstract val reduceEffects: String
    abstract val showFps: String
    abstract val language: String
    abstract val languageAuto: String
    abstract val resetProgress: String
    abstract val resetConfirm: String
    abstract val resetConfirm2: String
    abstract val resetDone: String
    abstract val about: String
    abstract val version: String
    abstract val credits: String
    abstract val fontsCredit: String
    abstract val privacyNote: String
    abstract val on: String
    abstract val off: String

    // ---- formatting ---------------------------------------------------------------------------------

    /** 18420 → "18.420" (pt) / "18,420" (en). */
    fun num(value: Long): String {
        val negative = value < 0
        val digits = (if (negative) -value else value).toString()
        val sb = StringBuilder(digits.length + digits.length / 3 + 1)
        if (negative) sb.append('-')
        for (i in digits.indices) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(thousandsSeparator)
            sb.append(digits[i])
        }
        return sb.toString()
    }

    fun num(value: Int): String = num(value.toLong())

    /** One decimal: 12.7 → "12,7" (pt) / "12.7" (en). */
    fun dec1(value: Float): String {
        val tenths = Math.round(value * 10f)
        return "${tenths / 10}$decimalSeparator${Math.abs(tenths % 10)}"
    }

    /** 3725000 ms → "1h 02m" or "12m 05s". */
    fun duration(ms: Long): String {
        val totalS = ms / 1000
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) "${h}h ${pad2(m)}m" else "${m}m ${pad2(s)}s"
    }

    private fun pad2(v: Long) = if (v < 10) "0$v" else v.toString()

    companion object {
        /** "pt*" device locales get Portuguese, everything else English, unless overridden. */
        fun forLanguage(setting: String, deviceLanguage: String): Strings = when (setting) {
            "pt" -> PtStrings
            "en" -> EnStrings
            else -> if (deviceLanguage.startsWith("pt")) PtStrings else EnStrings
        }
    }
}
