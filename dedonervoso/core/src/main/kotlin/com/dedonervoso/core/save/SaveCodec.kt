package com.dedonervoso.core.save

import com.dedonervoso.core.progression.Mission
import com.dedonervoso.core.progression.MissionKind
import com.dedonervoso.core.progression.RankEntry
import com.dedonervoso.core.progression.Stats
import com.dedonervoso.core.progression.UpgradeId
import com.dedonervoso.core.progression.Upgrades
import com.dedonervoso.core.stage.Mechanic
import com.dedonervoso.core.util.JObj
import com.dedonervoso.core.util.Json

/**
 * JSON (de)serialisation of [SaveData]. Loading is lenient: unknown keys are ignored, missing
 * ones take defaults and absurd values are clamped (spec §37 "valores absurdos"), so an old or
 * hand-edited file never crashes the game. [SaveData.VERSION] gates future migrations.
 */
object SaveCodec {

    fun encode(d: SaveData): String = Json.write(
        linkedMapOf(
            "version" to SaveData.VERSION,
            "profile" to mapOf("nickname" to d.profile.nickname, "avatar" to d.profile.avatar, "createdAt" to d.profile.createdAt),
            "coins" to d.coins,
            "totalXp" to d.totalXp,
            "highestUnlocked" to d.highestUnlocked,
            "selectedStage" to d.selectedStage,
            "stageStars" to d.stageStars.entries.sortedBy { it.key }.associate { it.key.toString() to it.value },
            "stageBest" to d.stageBest.entries.sortedBy { it.key }.associate { it.key.toString() to it.value },
            "stageFailStreak" to d.stageFailStreak.entries.filter { it.value > 0 }.associate { it.key.toString() to it.value },
            "upgrades" to d.upgrades.entries.associate { it.key.name to it.value },
            "stats" to encodeStats(d.stats),
            "achievements" to d.achievements,
            "missions" to d.missions.map {
                mapOf(
                    "id" to it.id, "kind" to it.kind.name, "target" to it.target, "progress" to it.progress,
                    "coins" to it.rewardCoins, "xp" to it.rewardXp, "claimed" to it.claimed,
                )
            },
            "missionCounter" to d.missionCounter,
            "dailyLastDay" to d.dailyLastDay,
            "dailyStreak" to d.dailyStreak,
            "ranking" to d.ranking.map {
                mapOf(
                    "score" to it.score, "stage" to it.stage, "combo" to it.maxCombo, "tps" to it.maxTps.toDouble(),
                    "taps" to it.taps, "ts" to it.timestamp, "day" to it.dayIndex, "daily" to it.daily, "sus" to it.suspicious,
                )
            },
            "settings" to mapOf(
                "music" to d.settings.music, "sfx" to d.settings.sfx, "vibration" to d.settings.vibration,
                "reduceEffects" to d.settings.reduceEffects, "showFps" to d.settings.showFps, "language" to d.settings.language,
            ),
            "onboardingDone" to d.onboardingDone,
            "seenIntros" to d.seenIntros.map { it.name }.sorted(),
            "release" to mapOf("manifest" to d.releaseCache, "url" to d.releaseManifestUrl, "checkedAt" to d.releaseCheckedAt),
        ),
    )

    private fun encodeStats(s: Stats) = linkedMapOf(
        "totalTaps" to s.totalTaps, "totalTouches" to s.totalTouches, "matches" to s.matches, "wins" to s.wins,
        "bestScore" to s.bestScore, "maxCombo" to s.maxCombo, "maxTps" to s.maxTps.toDouble(), "perfects" to s.perfects,
        "stopErrors" to s.stopErrors, "stopsSurvived" to s.stopsSurvived, "zoneHits" to s.zoneHits,
        "coinsEarned" to s.coinsEarned, "playTimeMs" to s.playTimeMs, "frenzies" to s.frenzies,
        "megaFrenzies" to s.megaFrenzies, "bossesDefeated" to s.bossesDefeated, "goldenHits" to s.goldenHits,
        "reflexBestMs" to s.reflexBestMs, "flawlessStages" to s.flawlessStages, "dailyCompleted" to s.dailyCompleted,
    )

    /** Parses a save; throws [com.dedonervoso.core.util.JsonException] only for malformed JSON. */
    fun decode(text: String): SaveData {
        val o = JObj.parse(text)
        val version = o.int("version", 1)
        // Future migrations: `if (version < 2) migrateToV2(o)` …
        check(version >= 1) { "Unknown save version $version" }

        val p = o.obj("profile")
        val d = SaveData(
            profile = Profile(
                nickname = sanitizeNickname(p.string("nickname", SaveData.defaultNickname(0L))),
                avatar = p.int("avatar").coerceIn(0, AVATAR_COUNT - 1),
                createdAt = p.long("createdAt"),
            ),
            coins = o.long("coins").coerceIn(0L, MAX_COINS),
            totalXp = o.long("totalXp").coerceIn(0L, MAX_XP),
            highestUnlocked = o.int("highestUnlocked", 1).coerceIn(1, MAX_STAGE),
            selectedStage = o.int("selectedStage", 1).coerceIn(1, MAX_STAGE),
        )
        d.selectedStage = d.selectedStage.coerceAtMost(d.highestUnlocked)
        for ((k, v) in o.obj("stageStars").map) {
            val stage = k.toIntOrNull() ?: continue
            d.stageStars[stage] = ((v as? Number)?.toInt() ?: 0).coerceIn(0, 3)
        }
        for ((k, v) in o.obj("stageBest").map) {
            val stage = k.toIntOrNull() ?: continue
            d.stageBest[stage] = ((v as? Number)?.toLong() ?: 0L).coerceIn(0L, MAX_SCORE)
        }
        for ((k, v) in o.obj("stageFailStreak").map) {
            val stage = k.toIntOrNull() ?: continue
            d.stageFailStreak[stage] = ((v as? Number)?.toInt() ?: 0).coerceIn(0, 99)
        }
        for ((k, v) in o.obj("upgrades").map) {
            val id = UpgradeId.values().firstOrNull { it.name == k } ?: continue
            d.upgrades[id] = ((v as? Number)?.toInt() ?: 0).coerceIn(0, Upgrades.def(id).maxLevel)
        }
        decodeStats(o.obj("stats"), d.stats)
        for ((k, v) in o.obj("achievements").map) d.achievements[k] = (v as? Number)?.toLong() ?: 0L
        for (m in o.objList("missions")) {
            val kind = MissionKind.values().firstOrNull { it.name == m.string("kind") } ?: continue
            d.missions += Mission(
                id = m.int("id"), kind = kind, target = m.int("target", 1).coerceAtLeast(1),
                progress = m.int("progress").coerceAtLeast(0), rewardCoins = m.int("coins").coerceIn(0, 100_000),
                rewardXp = m.int("xp").coerceIn(0, 100_000), claimed = m.bool("claimed"),
            )
        }
        d.missionCounter = o.int("missionCounter").coerceAtLeast(d.missions.maxOfOrNull { it.id } ?: 0)
        d.dailyLastDay = o.long("dailyLastDay", -1L)
        d.dailyStreak = o.int("dailyStreak").coerceIn(0, 100_000)
        for (r in o.objList("ranking")) {
            d.ranking += RankEntry(
                score = r.long("score").coerceIn(0L, MAX_SCORE), stage = r.int("stage"), maxCombo = r.int("combo"),
                maxTps = r.float("tps"), taps = r.long("taps"), timestamp = r.long("ts"), dayIndex = r.long("day"),
                daily = r.bool("daily"), suspicious = r.bool("sus"),
            )
        }
        d.ranking.sortByDescending { it.score }
        val s = o.obj("settings")
        d.settings.music = s.bool("music", true)
        d.settings.sfx = s.bool("sfx", true)
        d.settings.vibration = s.bool("vibration", true)
        d.settings.reduceEffects = s.bool("reduceEffects", false)
        d.settings.showFps = s.bool("showFps", false)
        d.settings.language = s.string("language", "auto").takeIf { it in LANGUAGES } ?: "auto"
        d.onboardingDone = o.bool("onboardingDone")
        val release = o.obj("release")
        d.releaseCache = release.string("manifest").take(MAX_RELEASE_JSON)
        d.releaseManifestUrl = release.string("url").takeIf { it.startsWith("https://") } ?: ""
        d.releaseCheckedAt = release.long("checkedAt").coerceAtLeast(0L)
        for (name in o.list("seenIntros")) {
            Mechanic.values().firstOrNull { it.name == name }?.let { d.seenIntros += it }
        }
        return d
    }

    private fun decodeStats(o: JObj, s: Stats) {
        s.totalTaps = o.long("totalTaps").coerceAtLeast(0)
        s.totalTouches = o.long("totalTouches").coerceAtLeast(0)
        s.matches = o.int("matches").coerceAtLeast(0)
        s.wins = o.int("wins").coerceAtLeast(0)
        s.bestScore = o.long("bestScore").coerceIn(0L, MAX_SCORE)
        s.maxCombo = o.int("maxCombo").coerceAtLeast(0)
        s.maxTps = o.float("maxTps").coerceIn(0f, 30f)
        s.perfects = o.long("perfects").coerceAtLeast(0)
        s.stopErrors = o.long("stopErrors").coerceAtLeast(0)
        s.stopsSurvived = o.long("stopsSurvived").coerceAtLeast(0)
        s.zoneHits = o.long("zoneHits").coerceAtLeast(0)
        s.coinsEarned = o.long("coinsEarned").coerceAtLeast(0)
        s.playTimeMs = o.long("playTimeMs").coerceAtLeast(0)
        s.frenzies = o.long("frenzies").coerceAtLeast(0)
        s.megaFrenzies = o.long("megaFrenzies").coerceAtLeast(0)
        s.bossesDefeated = o.int("bossesDefeated").coerceAtLeast(0)
        s.goldenHits = o.int("goldenHits").coerceAtLeast(0)
        s.reflexBestMs = o.long("reflexBestMs", -1L)
        s.flawlessStages = o.int("flawlessStages").coerceAtLeast(0)
        s.dailyCompleted = o.int("dailyCompleted").coerceAtLeast(0)
    }

    fun sanitizeNickname(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' }
            .trim().take(MAX_NICKNAME)
        return cleaned.ifEmpty { SaveData.defaultNickname(0L) }
    }

    const val AVATAR_COUNT = 12
    const val MAX_NICKNAME = 14
    private const val MAX_COINS = 999_999_999L
    private const val MAX_XP = 9_999_999_999L
    private const val MAX_SCORE = 99_999_999L
    private const val MAX_STAGE = 9_999
    private const val MAX_RELEASE_JSON = 16_384
    private val LANGUAGES = setOf("auto", "pt", "en")
}
