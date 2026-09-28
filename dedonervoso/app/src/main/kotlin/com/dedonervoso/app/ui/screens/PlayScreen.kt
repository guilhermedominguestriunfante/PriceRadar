package com.dedonervoso.app.ui.screens

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.dedonervoso.app.GameApp
import com.dedonervoso.app.platform.Duel
import com.dedonervoso.core.audio.MusicMode
import com.dedonervoso.core.audio.Sfx
import com.dedonervoso.core.engine.BreakReason
import com.dedonervoso.core.engine.DuelItem
import com.dedonervoso.core.engine.DuelOrb
import com.dedonervoso.core.engine.FailReason
import com.dedonervoso.core.engine.GameBalance
import com.dedonervoso.core.engine.GameListener
import com.dedonervoso.core.engine.GameSession
import com.dedonervoso.core.engine.GameState
import com.dedonervoso.core.engine.IgnoreReason
import com.dedonervoso.core.engine.InterruptKind
import com.dedonervoso.core.engine.Loadout
import com.dedonervoso.core.engine.MatchResult
import com.dedonervoso.core.engine.ReflexGrade
import com.dedonervoso.core.engine.ShieldType
import com.dedonervoso.core.engine.TapKind
import com.dedonervoso.core.engine.Zone
import com.dedonervoso.core.engine.ZoneMotion
import com.dedonervoso.core.engine.ZoneType
import com.dedonervoso.core.stage.Mechanic
import com.dedonervoso.core.stage.StageCatalog
import com.dedonervoso.core.stage.StageConfig
import com.dedonervoso.core.util.Ease
import com.dedonervoso.app.ui.Button
import com.dedonervoso.app.ui.Dialog
import com.dedonervoso.app.ui.DuelArt
import com.dedonervoso.app.ui.Icon
import com.dedonervoso.app.ui.NumText
import com.dedonervoso.app.ui.Palette
import com.dedonervoso.app.ui.PlusCache
import com.dedonervoso.app.ui.Screen
import com.dedonervoso.app.ui.Visuals
import com.dedonervoso.app.ui.fx.FloatingTexts
import com.dedonervoso.app.ui.fx.Particles
import com.dedonervoso.app.ui.fx.Shake
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The match (spec §4–§15). Input goes straight into [GameSession.tap] with the touch's own
 * timestamp; the session registers the tap first and then fires [GameListener] callbacks,
 * which only *schedule* feedback (particles, popups, sounds, haptics) — so effects can never
 * delay or drop a tap (spec §78: INPUT > VISUAL).
 *
 * With [duel] it is a live duel: the match starts at the time agreed with the rival, there is no
 * pause (back asks to give up; leaving the app doesn't stop the clock), the rival's score takes
 * the objective's place, orbs fall to be captured and the held item has its own button.
 */
class PlayScreen(
    app: GameApp,
    private val stage: StageConfig,
    private val daily: Boolean = false,
    private val dailyTitle: String? = null,
    private val duel: Duel.Match? = null,
) : Screen(app), GameListener {

    private enum class Phase { INTRO, PLAY, PAUSED, ENDING }

    private var phase = Phase.INTRO
    private var session: GameSession? = null

    /** Exposed for instrumentation-style tests (Robolectric). */
    internal val sessionForTest: GameSession? get() = session
    internal val arenaForTest: RectF get() = arena
    internal val inIntro: Boolean get() = phase == Phase.INTRO
    internal val isPaused: Boolean get() = phase == Phase.PAUSED
    internal val itemRectForTest: RectF get() = itemRect
    private var result: MatchResult? = null
    private var endAge = 0f
    private var introMechanic: Mechanic? = null
    private val seed = duel?.seed ?: (SystemClock.uptimeMillis() xor stage.seed)

    // ---- layout
    private val arena = RectF()
    private val pauseRect = RectF()
    private val itemRect = RectF()
    private val progressRect = RectF()
    private val frenzyBarRect = RectF()
    private var timerX = 0f
    private var timerY = 0f
    private var timerR = 0f
    private var coreX = 0f
    private var coreY = 0f
    private var coreR = 0f
    private var hudScoreY = 0f
    private var bottomY = 0f

    // ---- presentation state
    private val shake = Shake(app.ui.density)
    private var corePulse = 0f
    private var flashColor = Palette.RED
    private var flashAlpha = 0f
    private var bannerText = ""
    private var bannerColor = Palette.WHITE
    private var bannerAge = 10f
    private var bannerDuration = 1f
    private var bannerBig = true
    private var tapBannerAge = 10f
    private var countdownValue = 0
    private var countdownAge = 10f
    private var lastCoinSound = 0L
    private var lastTier = 0
    private var reduce = false
    private val zoneHitAge = FloatArray(GameSession.MAX_ZONES) { 10f }
    private val zoneSpawnAge = FloatArray(GameSession.MAX_ZONES) { 10f }
    private val zoneId = IntArray(GameSession.MAX_ZONES)
    private var timerPulse = 0f
    private var timeBonusAge = 10f
    private var displayTps = 0f
    private var lifeLostAge = 10f
    private var pausePointer = -1
    private val lockPath = Path()
    private val tmp = RectF()
    private val overlayPaint = Paint()

    // ---- cached texts (no per-frame allocation)
    private val scoreText = NumText { app.strings.num(it) }
    private val tapsText = NumText { app.strings.num(it) }
    private val comboText = NumText { it.toString() }
    private val timeText = NumText { (it / 10).toString() }
    private val timeDecText = NumText { "${it / 10}${app.strings.decimalSeparator}${it % 10}" }
    private val progressText = NumText { app.strings.num(it) }
    private val tpsText = NumText { "${app.strings.dec1(it / 10f)} ${app.strings.tpsUnit}" }
    private val multText = NumText { "x" + app.strings.dec1(it / 10f) }
    private var targetText = ""
    private var stageLabel = ""

    // ---- live duel texts (built once per match)
    private val rivalScoreText = NumText { app.strings.num(it) }
    private var rivalLabel = ""
    private var rivalLabelNick = ""
    private var myNick = ""
    private var slowLabel = ""
    private var paceLabels = emptyArray<String>()
    private var throwLabels = emptyArray<String>()
    private var readyLabels = emptyArray<String>()
    private var thrownLabels = emptyArray<String>()
    private var hitLabels = emptyArray<String>()

    private lateinit var pauseButtons: List<Button>

    override val musicMode: MusicMode get() = if (stage.isBoss) MusicMode.BOSS else MusicMode.GAME

    override fun onEnter() {
        reduce = app.settings.reduceEffects
        shake.enabled = !reduce
        app.keepScreenOn(true)
        ui.particles.clear()
        ui.popups.clear()
        ui.rings.clear()
        stageLabel = when {
            duel != null -> s.duel
            daily -> dailyTitle ?: s.daily
            else -> "${s.stage} ${stage.number}"
        }
        targetText = "/ " + s.num(if (stage.type == com.dedonervoso.core.stage.StageType.BOSS) stage.scoreTarget.toLong() else stage.target.toLong())
        if (duel != null) duelTexts()
        val m = stage.introduces
        introMechanic = if (!daily && duel == null && m != null && m !in app.progression.save.seenIntros) m else null
        if (introMechanic == null) beginMatch()
    }

    private fun duelTexts() {
        val items = DuelItem.values()
        myNick = app.progression.save.profile.nickname
        slowLabel = s.itemName(DuelItem.SLOW)
        paceLabels = Array(items.size) { "${items[it].requiredTps.toInt()}/s" }
        throwLabels = Array(items.size) { "${s.throwItem} ${s.itemName(items[it])}" }
        readyLabels = Array(items.size) { s.itemReady(items[it]) }
        thrownLabels = Array(items.size) { s.itemThrown(items[it]) }
        hitLabels = Array(items.size) { s.itemHit(items[it]) }
    }

    override fun onExit() {
        app.keepScreenOn(false)
        app.haptics.suppressTaps = false
        val e = app.music.engine
        e.stopped = false
        e.frenzy = false
        e.intensity = 0
    }

    override fun layout() {
        val top = safe.top
        val side = safe.left + 4f * u
        val right = safe.right - 4f * u
        pauseRect.set(right - 44f * u, top, right, top + 44f * u)
        timerR = 30f * u
        timerX = width / 2f
        timerY = top + 34f * u
        progressRect.set(side, top + 30f * u, min(side + 150f * u, timerX - timerR - 14f * u), top + 38f * u)
        hudScoreY = top + 86f * u
        if (duel != null) {
            // The item button takes the bottom strip; the frenzy bar moves under it.
            bottomY = safe.bottom - 4f * u
            itemRect.set(width / 2f - 110f * u, safe.bottom - 66f * u, width / 2f + 110f * u, safe.bottom - 16f * u)
            arena.set(safe.left, top + 104f * u, safe.right, itemRect.top - 8f * u)
        } else {
            bottomY = safe.bottom - 20f * u
            arena.set(safe.left, top + 104f * u, safe.right, safe.bottom - 48f * u)
        }
        frenzyBarRect.set(side, bottomY - 5f * u, right, bottomY + 5f * u)
        coreX = arena.centerX()
        coreY = arena.centerY()
        coreR = min(arena.width(), arena.height()) * 0.13f
        if (session == null) {
            session = if (duel != null) {
                GameSession(stage, Loadout.NONE, arena.height() / arena.width(), seed, this, duel = true).also { duel.attach(it) }
            } else {
                GameSession(stage, app.progression.loadout, arena.height() / arena.width(), seed, this)
            }
        }
        // Pause overlay buttons.
        val bw = min(width - 80f * u, 280f * u)
        val cx = width / 2f
        val y0 = height / 2f - 40f * u
        val resume = button(s.resume, Icon.PLAY, Button.Style.PRIMARY, Palette.CYAN) { resume() }
        resume.rect.set(cx - bw / 2f, y0, cx + bw / 2f, y0 + 60f * u)
        val restart = button(s.restart, Icon.RETRY, Button.Style.SECONDARY, Palette.MAGENTA) { restart() }
        restart.rect.set(cx - bw / 2f, y0 + 76f * u, cx + bw / 2f, y0 + 76f * u + 52f * u)
        val quit = button(s.quit, Icon.HOME, Button.Style.SECONDARY, Palette.DIM) { quit() }
        quit.rect.set(cx - bw / 2f, y0 + 142f * u, cx + bw / 2f, y0 + 142f * u + 52f * u)
        val tw = (bw - 24f * u) / 3f
        val ty = y0 + 214f * u
        val music = button("", Icon.MUSIC, Button.Style.ICON, Palette.CYAN) { toggle { it.music = !it.music } }
        music.rect.set(cx - bw / 2f + tw / 2f - 24f * u, ty, cx - bw / 2f + tw / 2f + 24f * u, ty + 48f * u)
        val sfx = button("", Icon.SOUND, Button.Style.ICON, Palette.CYAN) { toggle { it.sfx = !it.sfx } }
        sfx.rect.set(cx - 24f * u, ty, cx + 24f * u, ty + 48f * u)
        val vib = button("", Icon.VIBRATION, Button.Style.ICON, Palette.CYAN) { toggle { it.vibration = !it.vibration } }
        vib.rect.set(cx + bw / 2f - tw / 2f - 24f * u, ty, cx + bw / 2f - tw / 2f + 24f * u, ty + 48f * u)
        pauseButtons = listOf(resume, restart, quit, music, sfx, vib)
        refreshToggleColors()
        for (b in pauseButtons) b.visible = phase == Phase.PAUSED
    }

    private fun toggle(change: (com.dedonervoso.core.save.Settings) -> Unit) {
        change(app.settings)
        app.settingsChanged()
        refreshToggleColors()
    }

    private fun refreshToggleColors() {
        if (!::pauseButtons.isInitialized) return
        val st = app.settings
        pauseButtons[3].accent = if (st.music) Palette.CYAN else Palette.MUTED
        pauseButtons[4].accent = if (st.sfx) Palette.CYAN else Palette.MUTED
        pauseButtons[5].accent = if (st.vibration) Palette.CYAN else Palette.MUTED
    }

    private fun beginMatch() {
        phase = Phase.PLAY
        // A duel starts at the time agreed with the rival (see Duel.Match.drive).
        if (duel == null) session?.start(SystemClock.uptimeMillis())
    }

    /** Duels have no pause: leaving is giving up, after a confirmation (the match goes on meanwhile). */
    private fun askGiveUp() {
        val m = duel ?: return
        if (phase != Phase.PLAY) return
        app.host.showDialog(
            Dialog(s.giveUpTitle, s.giveUpText, listOf(s.keepPlaying to {}, s.giveUp to { m.giveUp() }), danger = true),
        )
    }

    private fun throwItem() {
        val m = duel ?: return
        val item = session?.useItem(SystemClock.uptimeMillis()) ?: return
        m.throwItem(item)
    }

    private fun pause() {
        val sess = session ?: return
        if (phase != Phase.PLAY) return
        sess.pause(SystemClock.uptimeMillis())
        if (sess.state != GameState.PAUSED) return
        phase = Phase.PAUSED
        app.music.engine.stopped = true
        for (b in pauseButtons) b.visible = true
    }

    private fun resume() {
        if (phase != Phase.PAUSED) return
        phase = Phase.PLAY
        for (b in pauseButtons) b.visible = false
        app.music.engine.stopped = false
        session?.resume(SystemClock.uptimeMillis())
    }

    private fun restart() {
        session?.abort(SystemClock.uptimeMillis())
        app.host.replace(if (daily) daily(app) else forStage(app, stage.number))
    }

    private fun quit() {
        session?.abort(SystemClock.uptimeMillis())
        app.host.pop()
    }

    override fun onBack(): Boolean {
        if (duel != null) {
            askGiveUp()
            return true
        }
        when (phase) {
            Phase.PLAY -> pause()
            Phase.PAUSED -> resume()
            Phase.INTRO -> app.host.pop()
            Phase.ENDING -> Unit
        }
        return true
    }

    override fun onAppPause() {
        // A duel keeps running while the app is away (Duel ticks it); only solo matches pause.
        if (phase == Phase.PLAY && duel == null) pause()
    }

    // ============================================================================================
    // Input
    // ============================================================================================

    override fun onTouch(e: MotionEvent): Boolean {
        when (phase) {
            Phase.PAUSED -> return super.onTouch(e)
            Phase.INTRO -> {
                if (e.actionMasked == MotionEvent.ACTION_UP && age > 0.5f) {
                    introMechanic?.let { app.progression.markIntroSeen(it) }
                    introMechanic = null
                    app.sfx.play(Sfx.UI_CLICK)
                    beginMatch()
                }
                return true
            }
            Phase.ENDING -> return true
            Phase.PLAY -> Unit
        }
        val sess = session ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i)
                val y = e.getY(i)
                if (x >= pauseRect.left - 8f * u && y <= pauseRect.bottom + 8f * u && x <= pauseRect.right + 8f * u) {
                    pausePointer = e.getPointerId(i)
                    return true
                }
                // The item button throws; its touches are not taps of the game.
                if (duel != null && itemRect.contains(x, y)) {
                    throwItem()
                    return true
                }
                // Register the tap first; all feedback follows from listener callbacks.
                sess.tap(e.eventTime, toArenaX(x), toArenaY(y), e.pointerCount, SystemClock.uptimeMillis())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                if (e.getPointerId(i) == pausePointer) {
                    pausePointer = -1
                    if (pauseRect.contains(e.getX(i), e.getY(i)) || abs(e.getX(i) - pauseRect.centerX()) < 40f * u) {
                        app.sfx.play(Sfx.UI_CLICK)
                        if (duel != null) askGiveUp() else pause()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pausePointer = -1
        }
        return true
    }

    private fun toArenaX(px: Float) = (px - arena.left) / arena.width()
    private fun toArenaY(py: Float) = (py - arena.top) / arena.width()
    private fun toScreenX(ax: Float) = arena.left + ax * arena.width()
    private fun toScreenY(ay: Float) = arena.top + ay * arena.width()

    // ============================================================================================
    // Update
    // ============================================================================================

    override fun update(now: Long, dt: Float) {
        super.update(now, dt)
        val sess = session
        if (duel != null) duel.drive(now) else if (sess != null && phase == Phase.PLAY) sess.update(now)
        val st = sess?.state
        val fxScale = if (st == GameState.STOP) 0.15f else 1f
        ui.particles.update(dt * fxScale)
        ui.rings.update(dt)
        ui.popups.update(dt)
        shake.update(dt)
        corePulse = max(0f, corePulse - dt * 6f)
        flashAlpha = max(0f, flashAlpha - dt * 2.8f)
        bannerAge += dt
        tapBannerAge += dt
        countdownAge += dt
        timerPulse = max(0f, timerPulse - dt * 3f)
        timeBonusAge += dt
        lifeLostAge += dt
        for (i in zoneHitAge.indices) {
            zoneHitAge[i] += dt
            zoneSpawnAge[i] += dt
        }
        if (sess != null) {
            val tps = if (st?.isActive == true) sess.tps.rate(now) else 0f
            displayTps += (tps - displayTps) * min(1f, dt * 5f)
            updateBackground(sess, dt)
            val e = app.music.engine
            e.stopped = phase == Phase.PAUSED || st == GameState.STOP
            e.frenzy = st == GameState.FRENZY
            e.intensity = when (sess.comboTier) {
                0 -> 0
                1, 2 -> 1
                3 -> 2
                4 -> 3
                else -> 4
            }
        }
        if (phase == Phase.ENDING && age - endAge > END_DELAY_S) finishToResults()
    }

    private fun updateBackground(sess: GameSession, dt: Float) {
        val bg = ui.background
        bg.update(dt * (if (sess.state == GameState.STOP) 0f else 1f))
        when {
            sess.state == GameState.STOP -> {
                val reflex = sess.holdKind == InterruptKind.REFLEX
                bg.tint = if (reflex) Palette.ORANGE else Palette.RED
                bg.tint2 = if (reflex) Palette.GOLD else Palette.ORANGE
                bg.speed = 0f
            }
            sess.state == GameState.FRENZY -> {
                bg.tint = Palette.rainbow(ui.time * 0.35f)
                bg.tint2 = Palette.rainbow(ui.time * 0.35f + 0.33f)
                bg.speed = 3.2f
            }
            else -> {
                val warn = if (sess.warningActive) sess.warningFraction else 0f
                bg.tint = Palette.mix(if (stage.isBoss) Palette.RED else Palette.MAGENTA, Palette.RED, warn)
                bg.tint2 = Palette.mix(if (stage.isBoss) Palette.PURPLE else Palette.CYAN, Palette.ORANGE, warn)
                bg.speed = 1f + sess.comboTier * 0.3f
            }
        }
    }

    private fun finishToResults() {
        val r = result ?: return
        result = null
        if (duel != null) {
            // Duels leave progression alone: the result screen waits for the rival's score.
            app.host.replace(DuelResultScreen(app, duel))
            return
        }
        val outcome = app.progression.applyMatch(r, stage, daily)
        app.online.submit(outcome)
        app.host.replace(ResultScreen(app, outcome, dailyTitle))
    }

    // ============================================================================================
    // Game events → feedback
    // ============================================================================================

    private fun banner(text: String, color: Int, duration: Float = 0.9f, big: Boolean = true) {
        bannerText = text
        bannerColor = color
        bannerAge = 0f
        bannerDuration = duration
        bannerBig = big
    }

    private fun flash(color: Int, alpha: Float) {
        if (reduce) return
        flashColor = color
        flashAlpha = max(flashAlpha, alpha)
    }

    override fun onCountdown(value: Int, resuming: Boolean) {
        countdownValue = value
        countdownAge = 0f
        if (value > 0) {
            app.sfx.play(Sfx.COUNT_BEEP, 0.9f, 1f + (3 - value) * 0.06f)
        } else {
            app.sfx.play(Sfx.GO)
            tapBannerAge = 0f
            app.haptics.click()
        }
    }

    override fun onTap(x: Float, y: Float, points: Int, kind: TapKind, zone: Zone?, comboTier: Int) {
        val px = toScreenX(x)
        val py = toScreenY(y)
        corePulse = 1f
        val sprite: Int
        val color: Int
        when (kind) {
            TapKind.PERFECT -> {
                sprite = Palette.S_GOLD
                color = Palette.GOLD
                app.sfx.play(Sfx.PERFECT, 0.85f, 1f + comboTier * 0.02f)
                app.haptics.perfect()
                ui.popups.addMerged(POPUP_PERFECT, 1, perfectLabel, px, py - 46f * u, Palette.GOLD, 17f, 0.7f, 50f, 0)
                ui.particles.burst(px, py, 16, Palette.S_GOLD, 120f, 320f, 3f, 7f, 0.45f, Particles.SPARK)
                shake.add(0.08f)
            }
            TapKind.ZONE -> {
                sprite = Visuals.zoneSprite(zone!!.type)
                color = Visuals.zoneColor(zone.type)
                app.sfx.play(Sfx.TAP_ZONE, 0.6f, 0.97f + comboTier * 0.03f)
                app.haptics.tap()
            }
            TapKind.NORMAL -> {
                sprite = if (session?.state == GameState.FRENZY) Palette.S_MAGENTA + (points and 3) else Palette.S_CYAN
                color = if (session?.state == GameState.FRENZY) Palette.rainbow(ui.time) else Palette.WHITE
                val variant = when (points and 3) {
                    0 -> Sfx.TAP_1
                    1 -> Sfx.TAP_2
                    2 -> Sfx.TAP_3
                    else -> Sfx.TAP_4
                }
                app.sfx.play(variant, 0.5f, 0.96f + comboTier * 0.035f + (touchJitter() * 0.05f))
                app.haptics.tap()
            }
        }
        if (zone != null) zoneHitAge[zone.slot()] = 0f
        val size = 16f + min(14f, points * 0.35f)
        ui.popups.addMerged(POPUP_POINTS, points, plusLabel, px, py - 18f * u, color, size, 0.6f, 80f, 0)
        ui.rings.add(px, py, 8f, if (kind == TapKind.NORMAL) 42f else 60f, color, 0.32f, 3f)
        ui.particles.burst(px, py, if (kind == TapKind.NORMAL) 5 else 9, sprite, 60f, 220f, 3f, 7f, 0.38f)
    }

    private val plusLabel = FloatingTexts.Label { PlusCache.get(it) }
    private val perfectLabels = arrayOfNulls<String>(64)
    private val perfectLabel = FloatingTexts.Label { n ->
        when {
            n <= 1 -> s.perfect
            n < perfectLabels.size -> perfectLabels[n] ?: "${s.perfect} x$n".also { perfectLabels[n] = it }
            else -> "${s.perfect} x$n"
        }
    }

    private var jitterSeed = 12345
    private fun touchJitter(): Float {
        jitterSeed = jitterSeed * 1103515245 + 12345
        return ((jitterSeed ushr 16) and 0x7FFF) / 32767f - 0.5f
    }

    private fun Zone.slot(): Int = session?.zones?.indexOf(this)?.coerceAtLeast(0) ?: 0

    override fun onTapIgnored(x: Float, y: Float, reason: IgnoreReason) = Unit

    override fun onMiss(x: Float, y: Float) {
        val px = toScreenX(x)
        val py = toScreenY(y)
        app.sfx.play(Sfx.MISS, 0.8f)
        app.haptics.error()
        ui.popups.add(s.miss, px, py - 20f * u, Palette.RED, 18f, 0.6f, 50f, 0)
        ui.rings.add(px, py, 6f, 34f, Palette.RED, 0.3f, 2.5f)
    }

    override fun onComboTier(tier: Int, multiplier: Float) {
        lastTier = tier
        val sfx = when (tier) {
            1 -> Sfx.COMBO_1
            2 -> Sfx.COMBO_2
            3 -> Sfx.COMBO_3
            4 -> Sfx.COMBO_4
            5 -> Sfx.COMBO_5
            else -> Sfx.COMBO_6
        }
        app.sfx.play(sfx, 0.75f)
        ui.popups.add(multText.of((multiplier * 10).toInt()) + "!", coreX, coreY - coreR - 20f * u, Palette.MAGENTA, 24f, 0.9f, 40f, 0)
        ui.rings.add(coreX, coreY, coreR / ui.density, coreR * 2.4f / ui.density, Palette.MAGENTA, 0.6f, 4f)
        ui.particles.burst(coreX, coreY, 18 + tier * 4, Palette.S_MAGENTA, 150f, 380f, 3f, 7f, 0.6f, Particles.SPARK)
        if (tier >= 4) shake.add(0.12f)
    }

    override fun onComboBreak(combo: Int, reason: BreakReason) {
        if (combo >= 10) {
            ui.popups.add(s.comboLost, coreX, coreY - coreR - 16f * u, Palette.RED, 16f, 0.9f, 30f, 1)
            if (reason == BreakReason.TIMEOUT) app.sfx.play(Sfx.FRENZY_END, 0.5f, 1.3f)
        }
        lastTier = 0
    }

    override fun onShieldUsed(type: ShieldType) {
        app.sfx.play(Sfx.SHIELD, 0.9f)
        val color = if (type == ShieldType.COMBO) Palette.GREEN else Palette.CYAN
        ui.popups.add(s.shield, coreX, coreY + coreR + 28f * u, color, 18f, 0.9f, 30f, 0)
        ui.rings.add(coreX, coreY, coreR / ui.density, coreR * 2f / ui.density, color, 0.5f, 5f)
    }

    override fun onInterruptWarning(kind: InterruptKind) {
        app.sfx.play(Sfx.STOP_WARN, 0.9f)
    }

    override fun onInterruptStart(kind: InterruptKind) {
        if (kind == InterruptKind.REFLEX) {
            app.sfx.play(Sfx.REFLEX_READY)
            app.haptics.click()
        } else {
            app.sfx.play(Sfx.STOP)
            app.haptics.stop()
            shake.add(0.25f)
            flash(Palette.RED, 0.35f)
        }
    }

    override fun onInterruptEnd(kind: InterruptKind, clean: Boolean) {
        app.sfx.play(Sfx.GO, 0.9f)
        tapBannerAge = 0f
        if (clean && kind == InterruptKind.STOP) {
            ui.rings.add(coreX, coreY, coreR / ui.density, coreR * 3f / ui.density, Palette.CYAN, 0.5f, 4f)
        }
    }

    override fun onFakeStopReveal() {
        app.sfx.play(Sfx.GO)
        banner(s.go, Palette.GREEN, 1.2f)
        ui.popups.add("x2", coreX, coreY + coreR + 30f * u, Palette.GREEN, 26f, 1.4f, 20f, 0)
        ui.particles.burst(coreX, coreY, 30, Palette.S_GREEN, 150f, 420f, 3f, 7f, 0.6f, Particles.SPARK)
    }

    override fun onStopFault(x: Float, y: Float, penaltyPoints: Int, lifeLost: Boolean, repeat: Boolean) {
        val px = toScreenX(x)
        val py = toScreenY(y)
        app.sfx.play(Sfx.FAULT, if (repeat) 0.5f else 0.9f)
        if (!repeat) {
            app.haptics.error()
            flash(Palette.RED, if (lifeLost) 0.55f else 0.35f)
            shake.add(if (lifeLost) 0.4f else 0.2f)
        }
        if (penaltyPoints > 0) ui.popups.add("-${s.num(penaltyPoints)}", px, py - 20f * u, Palette.RED, if (repeat) 15f else 22f, 0.8f, 60f, 0)
    }

    override fun onReflexResult(reactionMs: Long, bonusPoints: Int, grade: ReflexGrade) {
        if (grade == ReflexGrade.MISSED) {
            ui.popups.add(s.reflexGrade(grade), coreX, coreY - coreR - 24f * u, Palette.DIM, 18f, 0.9f, 30f, 1)
            return
        }
        val color = when (grade) {
            ReflexGrade.LIGHTNING -> Palette.GOLD
            ReflexGrade.GREAT -> Palette.GREEN
            ReflexGrade.GOOD -> Palette.CYAN
            else -> Palette.DIM
        }
        app.sfx.play(if (grade == ReflexGrade.LIGHTNING || grade == ReflexGrade.GREAT) Sfx.PERFECT else Sfx.TAP_ZONE)
        banner(s.reflexGrade(grade), color, 1.1f)
        ui.popups.add("${reactionMs} ms  ${PlusCache.get(bonusPoints)}", coreX, coreY + coreR + 34f * u, color, 18f, 1.3f, 30f, 1)
        if (grade == ReflexGrade.LIGHTNING) {
            ui.particles.burst(coreX, coreY, 36, Palette.S_GOLD, 150f, 450f, 3f, 8f, 0.7f, Particles.SPARK)
            shake.add(0.2f)
        }
    }

    override fun onZoneSpawn(zone: Zone) {
        val slot = zone.slot()
        zoneSpawnAge[slot] = 0f
        zoneId[slot] = zone.id
        app.sfx.play(Sfx.ZONE_SPAWN, 0.6f)
    }

    override fun onZonePop(zone: Zone) {
        val sess = session ?: return
        val t = sess.activeTimeMs
        val px = toScreenX(zone.x(t))
        val py = toScreenY(zone.y(t))
        val sprite = Visuals.zoneSprite(zone.type)
        ui.rings.add(px, py, zone.radius(t) * arena.width() / ui.density, zone.radius(t) * arena.width() * 2.2f / ui.density, Visuals.zoneColor(zone.type), 0.45f, 5f)
        ui.particles.burst(px, py, 22, sprite, 120f, 360f, 3f, 8f, 0.55f)
        when (zone.type) {
            ZoneType.CRITICAL -> {
                app.sfx.play(Sfx.CRIT)
                banner("CRITICAL!", Palette.RED, 0.8f, big = false)
                shake.add(0.15f)
            }
            ZoneType.GOLDEN -> {
                app.sfx.play(Sfx.GOLDEN)
                app.haptics.celebrate()
                banner("GOLDEN!", Palette.GOLD, 1.2f)
                ui.particles.burst(px, py, 60, Palette.S_GOLD, 150f, 520f, 4f, 9f, 0.9f)
                shake.add(0.3f)
            }
            ZoneType.TIME -> app.sfx.play(Sfx.TIME_BONUS)
            else -> app.sfx.play(Sfx.ZONE_POP, 0.8f)
        }
    }

    override fun onCoins(amount: Int, x: Float, y: Float) {
        val px = toScreenX(x)
        val py = toScreenY(y)
        val now = SystemClock.uptimeMillis()
        if (now - lastCoinSound > 90) {
            lastCoinSound = now
            app.sfx.play(Sfx.COIN, 0.55f)
        }
        ui.particles.burst(px, py, 4 + min(20, amount), Palette.S_GOLD, 80f, 260f, 3f, 6f, 0.5f, gravityDp = 300f, angle = -1.57f, spread = 2.2f)
        if (amount > 1) ui.popups.add("+${s.num(amount)} ${s.coinsLabel}", px, py - 40f * u, Palette.GOLD, 16f, 1.1f, 50f, 1)
    }

    override fun onTimeBonus(ms: Long) {
        timeBonusAge = 0f
        ui.popups.add("+${ms / 1000}s", timerX, timerY + timerR + 20f * u, Palette.BLUE, 20f, 1f, 30f, 0)
    }

    override fun onFrenzyStart(mega: Boolean) {
        app.sfx.play(if (mega) Sfx.MEGA else Sfx.FRENZY_START)
        app.haptics.frenzy()
        app.haptics.suppressTaps = true
        banner(if (mega) s.megaFrenzy else s.frenzy, if (mega) Palette.GOLD else Palette.MAGENTA, 1.3f)
        shake.add(if (mega) 0.5f else 0.35f)
        ui.rings.add(coreX, coreY, coreR / ui.density, width / ui.density, Palette.MAGENTA, 0.8f, 6f)
        for (i in 0 until 6) ui.particles.burst(coreX, coreY, 12, Palette.S_CYAN + i % 6, 200f, 600f, 3f, 8f, 0.8f, Particles.SPARK)
    }

    override fun onFrenzyEnd() {
        app.sfx.play(Sfx.FRENZY_END, 0.8f)
        app.haptics.suppressTaps = false
    }

    override fun onLifeLost(livesLeft: Int) {
        lifeLostAge = 0f
        ui.particles.burst(safe.left + 20f * u + livesLeft * 24f * u, bottomY - 18f * u, 20, Palette.S_RED, 80f, 260f, 3f, 7f, 0.6f)
    }

    override fun onObjectiveComplete() {
        app.sfx.play(Sfx.STAR_2, 0.9f)
        banner(s.objectiveDone, Palette.GREEN, 1.4f, big = false)
        ui.rings.add(progressRect.centerX(), progressRect.centerY(), 10f, 90f, Palette.GREEN, 0.6f, 4f)
    }

    override fun onTimeWarning(secondsLeft: Int) {
        timerPulse = 1f
        if (secondsLeft <= 5) app.sfx.play(Sfx.COUNT_BEEP, 0.6f, 1.2f)
        if (secondsLeft == 10) banner(s.lastSeconds, Palette.ORANGE, 1f, big = false)
    }

    override fun onOrbSpawn(orb: DuelOrb) {
        app.sfx.play(Sfx.ZONE_SPAWN, 0.9f, 0.8f)
        ui.popups.add(paceLabels[orb.item.ordinal], toScreenX(orb.x), arena.top + 30f * u, DuelArt.color(orb.item), 20f, 1f, 30f, 0)
    }

    override fun onOrbCaptured(orb: DuelOrb) {
        val sess = session ?: return
        val x = toScreenX(orb.x)
        val y = orbY(orb, sess)
        val color = DuelArt.color(orb.item)
        app.sfx.play(Sfx.GOLDEN)
        app.haptics.celebrate()
        banner(readyLabels[orb.item.ordinal], color, 1.2f, big = false)
        ui.rings.add(x, y, 10f, 90f, color, 0.5f, 4f)
        ui.particles.burst(x, y, 36, itemSprite(orb.item), 120f, 420f, 3f, 8f, 0.6f, Particles.SPARK)
    }

    override fun onOrbMissed(orb: DuelOrb) {
        app.sfx.play(Sfx.FRENZY_END, 0.4f, 1.3f)
    }

    override fun onItemUsed(item: DuelItem) {
        val color = DuelArt.color(item)
        app.sfx.play(Sfx.CRIT)
        app.haptics.click()
        banner(thrownLabels[item.ordinal], color, 1f, big = false)
        ui.rings.add(itemRect.centerX(), itemRect.centerY(), 10f, 140f, color, 0.5f, 4f)
        ui.particles.burst(itemRect.centerX(), itemRect.centerY(), 30, itemSprite(item), 200f, 600f, 3f, 8f, 0.6f, Particles.SPARK, angle = -1.57f, spread = 1.2f)
    }

    override fun onItemHit(item: DuelItem) {
        val color = DuelArt.color(item)
        banner(hitLabels[item.ordinal], color, 1.3f)
        flash(color, 0.45f)
        shake.add(0.3f)
        app.haptics.error()
        when (item) {
            DuelItem.SLOW -> app.sfx.play(Sfx.FRENZY_END, 1f, 0.7f)
            DuelItem.CLOCK -> {
                app.sfx.play(Sfx.TIME_BONUS, 1f, 0.7f)
                timerPulse = 1f
                ui.popups.add("-${GameBalance.DUEL_CLOCK_PENALTY_MS / 1000}s", timerX, timerY + timerR + 20f * u, Palette.GOLD, 20f, 1f, 30f, 0)
            }
            DuelItem.STOP -> app.sfx.play(Sfx.STOP_WARN)
        }
    }

    private fun itemSprite(item: DuelItem): Int = when (item) {
        DuelItem.SLOW -> Palette.S_BLUE
        DuelItem.CLOCK -> Palette.S_GOLD
        DuelItem.STOP -> Palette.S_RED
    }

    override fun onFinished(result: MatchResult) {
        this.result = result
        phase = Phase.ENDING
        endAge = age
        app.haptics.suppressTaps = false
        app.music.engine.frenzy = false
        if (duel != null) {
            app.sfx.play(Sfx.FRENZY_END)
            when {
                result.failReason != FailReason.ABORTED -> banner(s.timeUp, Palette.CYAN, END_DELAY_S)
                duel.reason == Duel.Reason.RIVAL_GAVE_UP -> banner(s.rivalGaveUp, Palette.GREEN, END_DELAY_S, big = false)
                else -> banner(s.youGaveUp, Palette.RED, END_DELAY_S, big = false)
            }
            return
        }
        if (result.won) {
            app.sfx.play(Sfx.WIN)
            app.haptics.celebrate()
            banner(s.stageClear, Palette.GREEN, END_DELAY_S)
            ui.particles.confetti(width, 0f, 90)
        } else {
            app.sfx.play(Sfx.LOSE)
            banner(if (result.failReason == com.dedonervoso.core.engine.FailReason.NO_LIVES) s.outOfLives else s.timeUp, Palette.RED, END_DELAY_S)
        }
    }

    // ============================================================================================
    // Drawing
    // ============================================================================================

    override fun draw(c: Canvas) {
        val sess = session
        val bg = ui.background
        bg.horizon = 0.8f
        bg.gridAlpha = 0.75f
        bg.draw(c)
        c.save()
        c.translate(shake.offsetX, shake.offsetY)
        if (sess != null) {
            drawArenaFrame(c, sess)
            drawCore(c, sess)
            drawZones(c, sess)
            if (sess.lockActive) drawLock(c, sess)
            if (duel != null) drawOrb(c, sess)
        }
        ui.rings.draw(c)
        ui.particles.draw(c)
        ui.popups.draw(c)
        if (sess != null) drawHud(c, sess)
        c.restore()
        if (sess != null) {
            if (sess.slowActive) drawSlowTint(c, sess)
            drawWarning(c, sess)
            drawHold(c, sess)
            drawFrenzyBorder(c, sess)
        }
        drawFlash(c)
        drawBanners(c)
        if (sess != null) drawCountdown(c, sess)
        if (duel != null && sess?.state == GameState.READY) drawFaceOff(c, duel)
        when (phase) {
            Phase.PAUSED -> drawPause(c)
            Phase.INTRO -> drawIntro(c)
            else -> Unit
        }
    }

    private fun drawArenaFrame(c: Canvas, sess: GameSession) {
        val color = when (sess.state) {
            GameState.STOP -> Palette.RED
            GameState.FRENZY -> Palette.rainbow(ui.time * 0.5f)
            else -> if (stage.isBoss) Palette.RED else Palette.CYAN
        }
        val neon = ui.neon
        val l = arena.left + 2f * u
        val t = arena.top
        val r = arena.right - 2f * u
        val b = arena.bottom
        val len = 22f * u
        val col = Palette.withAlpha(color, 0.7f)
        val w = 2.5f * u
        neon.line(c, l, t, l + len, t, col, w); neon.line(c, l, t, l, t + len, col, w)
        neon.line(c, r, t, r - len, t, col, w); neon.line(c, r, t, r, t + len, col, w)
        neon.line(c, l, b, l + len, b, col, w); neon.line(c, l, b, l, b - len, col, w)
        neon.line(c, r, b, r - len, b, col, w); neon.line(c, r, b, r, b - len, col, w)
    }

    private fun drawCore(c: Canvas, sess: GameSession) {
        val neon = ui.neon
        val pulse = Ease.outQuad(corePulse)
        val frenzy = sess.state == GameState.FRENZY
        val baseColor = when {
            frenzy -> Palette.rainbow(ui.time * 0.6f)
            sess.state == GameState.STOP -> Palette.RED
            else -> Palette.mix(Palette.CYAN, Palette.MAGENTA, sess.comboTier / 6f)
        }
        val r = coreR * (1f + 0.07f * pulse)
        neon.glowBlob(c, coreX, coreY, r * 2.4f, baseColor, 0.28f + 0.2f * pulse + sess.comboTier * 0.03f)
        neon.circle(c, coreX, coreY, r, Palette.withAlpha(Palette.BG_MID, 0.75f))
        neon.circleStroke(c, coreX, coreY, r, Palette.withAlpha(baseColor, 0.35f), 8f * u)
        neon.circleStroke(c, coreX, coreY, r, baseColor, 2f * u)
        // Combo timer (inner) and frenzy meter (outer ring).
        if (sess.combo > 0) neon.ring(c, coreX, coreY, r - 7f * u, sess.comboTimerFraction, Palette.withAlpha(Palette.WHITE, 0.5f), 2f * u)
        if (stage.hasFrenzy) {
            val meter = if (frenzy) sess.frenzyFraction else sess.frenzyMeter
            val ringColor = if (frenzy) Palette.rainbow(ui.time) else if (sess.frenzyMeter >= 1f) Palette.GOLD else Palette.PURPLE
            neon.ring(c, coreX, coreY, r + 12f * u, meter, ringColor, 6f * u, Palette.withAlpha(Palette.WHITE, 0.08f))
        }
        // Combo number and multiplier.
        if (sess.combo > 0) {
            val p = ui.style(ui.displayPaint, 30f, Palette.WHITE, Paint.Align.CENTER)
            p.textSize = min(r * 0.62f, 34f * u) * (1f + 0.1f * pulse)
            c.drawText(comboText.of(sess.combo), coreX, coreY + p.textSize * 0.2f, p)
            val mp = ui.style(ui.displayBoldPaint, 13f, if (sess.comboTier > 0) Palette.MAGENTA else Palette.DIM, Paint.Align.CENTER)
            val mult = sess.comboMultiplier * (if (frenzy) sess.frenzyMultiplier else 1f)
            c.drawText(multText.of((mult * 10 + 0.5f).toInt()), coreX, coreY + r * 0.52f, mp)
            val cp = ui.style(ui.textPaint, 11f, Palette.DIM, Paint.Align.CENTER)
            c.drawText("COMBO", coreX, coreY - r * 0.42f, cp)
        } else {
            ui.icons.draw(c, Icon.BOLT, coreX, coreY, r * 0.8f, Palette.withAlpha(baseColor, 0.8f))
        }
        if (displayTps >= 5.5f) {
            val tp = ui.style(ui.displayBoldPaint, 13f, Palette.withAlpha(Palette.CYAN, min(1f, (displayTps - 5.5f) / 1.5f)), Paint.Align.CENTER)
            c.drawText(tpsText.of((displayTps * 10).toInt()), coreX, coreY + r + 34f * u, tp)
        }
    }

    private fun drawZones(c: Canvas, sess: GameSession) {
        val t = sess.activeTimeMs
        val neon = ui.neon
        val scale = arena.width()
        for ((slot, z) in sess.zones.withIndex()) {
            if (!z.active) continue
            val color = Visuals.zoneColor(z.type)
            val x = toScreenX(z.x(t))
            val y = toScreenY(z.y(t))
            var r = z.radius(t) * scale
            val life = z.lifeFraction(t)
            val spawn = Ease.outBack((zoneSpawnAge[slot] / 0.28f).coerceIn(0f, 1f))
            val hit = zoneHitAge[slot]
            r *= spawn * (1f + (if (hit < 0.15f) 0.12f * (1f - hit / 0.15f) else 0f))
            val ending = life > 0.75f
            val blink = if (ending && ((t / 90) % 2L == 0L)) 0.45f else 1f
            val alpha = blink * (if (life >= 1f) 0.4f else 1f)
            // Motion trail.
            if (z.motion == ZoneMotion.DRIFT || z.motion == ZoneMotion.ORBIT) {
                for (k in 1..3) {
                    val tt = t - k * 60L
                    neon.circle(c, toScreenX(z.x(tt)), toScreenY(z.y(tt)), r * (1f - k * 0.12f), Palette.withAlpha(color, 0.08f * alpha))
                }
            }
            neon.glowBlob(c, x, y, r * 1.8f, color, 0.35f * alpha)
            neon.circle(c, x, y, r, Palette.withAlpha(Palette.mix(Palette.BG_MID, color, 0.22f), 0.78f * alpha))
            neon.circleStroke(c, x, y, r, Palette.withAlpha(color, 0.4f * alpha), 7f * u)
            neon.circleStroke(c, x, y, r, Palette.withAlpha(color, alpha), 2.2f * u)
            // Remaining life arc.
            neon.ring(c, x, y, r + 6f * u, 1f - life, Palette.withAlpha(color, 0.7f * alpha), 2.5f * u)
            // Perfect bullseye.
            if (stage.hasPerfect && z.type != ZoneType.GOLDEN) {
                val pr = r * app.progression.loadout.perfectRadiusFraction
                neon.circleStroke(c, x, y, pr, Palette.withAlpha(Palette.GOLD, 0.55f * alpha), 1.4f * u)
                neon.circle(c, x, y, 2.5f * u, Palette.withAlpha(Palette.GOLD, 0.9f * alpha))
            }
            // Label.
            when (z.type) {
                ZoneType.X2, ZoneType.X3, ZoneType.X5 -> {
                    val p = ui.style(ui.displayPaint, 1f, Palette.withAlpha(Palette.WHITE, alpha), Paint.Align.CENTER)
                    p.textSize = r * 0.5f
                    c.drawText(ZONE_LABELS[z.type.ordinal], x, y - r * 0.3f, p)
                }
                ZoneType.COIN, ZoneType.GOLDEN -> ui.icons.draw(c, Icon.COIN, x, y - r * 0.36f, r * 0.45f, Palette.withAlpha(Palette.GOLD, alpha))
                ZoneType.TIME -> ui.icons.draw(c, Icon.CLOCK, x, y - r * 0.36f, r * 0.45f, Palette.withAlpha(color, alpha))
                ZoneType.COMBO -> ui.icons.draw(c, Icon.COMBO, x, y - r * 0.36f, r * 0.4f, Palette.withAlpha(color, alpha))
                ZoneType.CRITICAL -> ui.icons.draw(c, Icon.TARGET, x, y, r * 1.3f, Palette.withAlpha(color, alpha))
            }
            if (z.locked) ui.icons.draw(c, Icon.LOCK, x + r * 0.7f, y - r * 0.7f, r * 0.42f, Palette.withAlpha(Palette.WHITE, alpha))
            if (z.type == ZoneType.GOLDEN && !reduce) {
                for (k in 0 until 3) {
                    val a = ui.time * 3f + k * 2.1f
                    neon.circle(c, x + kotlin.math.cos(a) * r * 1.2f, y + sin(a) * r * 1.2f, 2.5f * u, Palette.GOLD)
                }
            }
        }
    }

    private fun drawLock(c: Canvas, sess: GameSession) {
        val t = sess.activeTimeMs
        lockPath.reset()
        lockPath.fillType = Path.FillType.EVEN_ODD
        lockPath.addRect(arena, Path.Direction.CW)
        for (z in sess.zones) {
            if (!z.active || !z.locked) continue
            lockPath.addCircle(toScreenX(z.x(t)), toScreenY(z.y(t)), z.radius(t) * arena.width() * 1.12f, Path.Direction.CW)
        }
        overlayPaint.color = Palette.withAlpha(0xFF000000.toInt(), 0.45f)
        c.drawPath(lockPath, overlayPaint)
        val p = ui.style(ui.textPaint, 15f, Palette.WHITE, Paint.Align.CENTER)
        c.drawText(s.onlyInZone, arena.centerX(), arena.top + 22f * u, p)
    }

    private fun drawHud(c: Canvas, sess: GameSession) {
        if (duel != null) drawRivalPanel(c, sess, duel) else drawObjective(c, sess)
        drawTimerScoreAndButton(c, sess)
        if (duel != null) {
            if (sess.slowActive) {
                val lbl = ui.style(ui.textPaint, 11f, Palette.DIM, Paint.Align.LEFT)
                val sp = ui.style(ui.displayBoldPaint, 11f, Palette.BLUE, Paint.Align.LEFT)
                c.drawText(slowLabel, safe.left + 4f * u + lbl.measureText("SCORE") + 8f * u, hudScoreY - 18f * u, sp)
            }
            drawItemButton(c, sess)
            return
        }
        drawBottomBar(c, sess)
    }

    private fun drawObjective(c: Canvas, sess: GameSession) {
        val neon = ui.neon
        // Stage label + objective progress.
        val lp = ui.style(ui.textPaint, 14f, Visuals.typeColor(stage.type), Paint.Align.LEFT)
        ui.fitText(c, stageLabel, progressRect.left, safe.top + 18f * u, lp, progressRect.width())
        val target = sess.objectiveTarget
        val progress = sess.objectiveProgress
        val frac = when (stage.type) {
            com.dedonervoso.core.stage.StageType.SURVIVAL ->
                if (sess.lives > 0 || stage.lives == 0) 1f - progress.toFloat() / (stage.target + 1) else 0f
            else -> if (target <= 0) 1f else progress.toFloat() / target
        }
        val done = sess.objectiveMet
        neon.bar(c, progressRect, frac, if (done) Palette.GREEN else Visuals.typeColor(stage.type), if (done) Palette.CYAN else Palette.PURPLE)
        val pp = ui.style(ui.mediumPaint, 12f, if (done) Palette.GREEN else Palette.DIM, Paint.Align.LEFT)
        if (stage.type != com.dedonervoso.core.stage.StageType.SURVIVAL) {
            c.drawText(progressText.of(progress), progressRect.left, progressRect.bottom + 16f * u, pp)
            val w = pp.measureText(progressText.of(progress))
            c.drawText(targetText, progressRect.left + w + 4f * u, progressRect.bottom + 16f * u, pp)
        }
    }

    private fun drawTimerScoreAndButton(c: Canvas, sess: GameSession) {
        val neon = ui.neon
        // Timer ring.
        val left = sess.timeLeftMs
        val urgent = left <= 10_000 && sess.state.isActive
        val tColor = if (urgent) Palette.RED else Palette.CYAN
        val pulse = Ease.outQuad(timerPulse)
        val tr = timerR * (1f + 0.12f * pulse)
        neon.circle(c, timerX, timerY, tr, Palette.withAlpha(Palette.BG_TOP, 0.7f))
        neon.ring(c, timerX, timerY, tr, left.toFloat() / sess.durationMs, tColor, 4f * u, Palette.withAlpha(Palette.WHITE, 0.1f))
        if (timeBonusAge < 0.6f) neon.circleStroke(c, timerX, timerY, tr + 8f * u * timeBonusAge / 0.6f, Palette.withAlpha(Palette.BLUE, 1f - timeBonusAge / 0.6f), 3f * u)
        val tp = ui.style(ui.displayPaint, 22f, if (urgent) Palette.RED else Palette.WHITE, Paint.Align.CENTER)
        val text = if (urgent) timeDecText.of(left / 100) else timeText.of((left + 999) / 100)
        tp.textSize = (if (urgent) 19f else 24f) * u * (1f + 0.15f * pulse)
        c.drawText(text, timerX, timerY + tp.textSize * 0.36f, tp)

        // Score & taps.
        val lbl = ui.style(ui.textPaint, 11f, Palette.DIM, Paint.Align.LEFT)
        c.drawText("SCORE", safe.left + 4f * u, hudScoreY - 18f * u, lbl)
        val sp = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.LEFT)
        c.drawText(scoreText.of(sess.score), safe.left + 4f * u, hudScoreY + 4f * u, sp)
        lbl.textAlign = Paint.Align.RIGHT
        c.drawText("TAPS", safe.right - 4f * u, hudScoreY - 18f * u, lbl)
        sp.textAlign = Paint.Align.RIGHT
        sp.color = Palette.CYAN
        c.drawText(tapsText.of(sess.taps), safe.right - 4f * u, hudScoreY + 4f * u, sp)

        // Pause button (in a duel: give up).
        neon.circle(c, pauseRect.centerX(), pauseRect.centerY(), pauseRect.width() / 2f, Palette.withAlpha(Palette.PANEL, 0.9f))
        neon.circleStroke(c, pauseRect.centerX(), pauseRect.centerY(), pauseRect.width() / 2f, Palette.withAlpha(Palette.CYAN, 0.7f), 1.5f * u)
        ui.icons.draw(c, if (duel != null) Icon.CLOSE else Icon.PAUSE, pauseRect.centerX(), pauseRect.centerY(), pauseRect.width() * 0.42f, Palette.CYAN)
    }

    private fun drawBottomBar(c: Canvas, sess: GameSession) {
        // Bottom bar: lives, shields.
        var x = safe.left + 16f * u
        val by = bottomY - 18f * u
        if (sess.maxLives > 0) {
            for (i in 0 until sess.maxLives) {
                val alive = i < sess.lives
                val shakeLife = if (!alive && i == sess.lives && lifeLostAge < 0.5f) sin(lifeLostAge * 60f) * 4f * u * (1f - lifeLostAge / 0.5f) else 0f
                ui.icons.draw(c, if (alive) Icon.HEART else Icon.HEART_EMPTY, x + shakeLife, by, 20f * u, if (alive) Palette.RED else Palette.MUTED)
                x += 24f * u
            }
            x += 8f * u
        }
        if (sess.comboShieldsLeft > 0) {
            ui.icons.draw(c, Icon.SHIELD, x, by, 18f * u, Palette.GREEN)
            val np = ui.style(ui.textPaint, 12f, Palette.GREEN, Paint.Align.LEFT)
            c.drawText(SMALL_NUMBERS[sess.comboShieldsLeft.coerceIn(0, SMALL_NUMBERS.lastIndex)], x + 11f * u, by + 5f * u, np)
            x += 34f * u
        }
        if (sess.stopShieldsLeft > 0) {
            ui.icons.draw(c, Icon.HAND, x, by, 18f * u, Palette.CYAN)
            val np = ui.style(ui.textPaint, 12f, Palette.CYAN, Paint.Align.LEFT)
            c.drawText(SMALL_NUMBERS[sess.stopShieldsLeft.coerceIn(0, SMALL_NUMBERS.lastIndex)], x + 11f * u, by + 5f * u, np)
        }
        if (sess.goBoostActive) {
            val gp = ui.style(ui.displayBoldPaint, 14f, Palette.GREEN, Paint.Align.RIGHT)
            c.drawText("GO x2", safe.right - 8f * u, by + 5f * u, gp)
        }
    }

    // ---- live duel --------------------------------------------------------------------------------

    private fun orbY(orb: DuelOrb, sess: GameSession): Float {
        val r = ORB_R * u
        return arena.top + r + orb.fall(sess.matchTimeMs) * (arena.height() - 2f * r)
    }

    /** The falling orb with its capture ring and the pace it asks for (dimmed while an item is held). */
    private fun drawOrb(c: Canvas, sess: GameSession) {
        val orb = sess.activeOrb ?: return
        val r = ORB_R * u
        val x = toScreenX(orb.x)
        val y = orbY(orb, sess)
        val locked = sess.heldItem != null
        val alpha = if (locked) 0.45f else 1f
        val color = DuelArt.color(orb.item)
        val fast = !locked && displayTps >= orb.item.requiredTps
        for (k in 1..3) ui.neon.circle(c, x, y - k * r * 0.55f, r * (1f - k * 0.2f), Palette.withAlpha(color, 0.08f * alpha))
        DuelArt.badge(c, ui, orb.item, x, y, r, alpha)
        ui.neon.ring(c, x, y, r + 8f * u, orb.progress, if (fast) Palette.GREEN else color, 5f * u, Palette.withAlpha(Palette.WHITE, 0.12f * alpha))
        if (locked) ui.icons.draw(c, Icon.LOCK, x + r * 0.8f, y - r * 0.8f, r * 0.5f, Palette.withAlpha(Palette.WHITE, 0.8f))
        val tp = ui.style(ui.displayBoldPaint, 15f, Palette.withAlpha(if (fast) Palette.GREEN else Palette.WHITE, alpha), Paint.Align.CENTER)
        c.drawText(paceLabels[orb.item.ordinal], x, y + r + 26f * u, tp)
    }

    /** In place of the objective: the rival, their score and a bar of the two scores. */
    private fun drawRivalPanel(c: Canvas, sess: GameSession, m: Duel.Match) {
        if (m.rivalNick != rivalLabelNick) {
            rivalLabelNick = m.rivalNick
            rivalLabel = "VS ${m.rivalNick}"
        }
        val color = Visuals.AVATAR_COLORS[m.rivalAvatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)]
        val lp = ui.style(ui.textPaint, 14f, color, Paint.Align.LEFT)
        ui.fitText(c, rivalLabel, progressRect.left, safe.top + 18f * u, lp, progressRect.width())
        // This player's share of both scores (the fill) against the rival's (the track).
        val total = sess.score + m.rivalScore
        val share = if (total <= 0L) 0.5f else sess.score.toFloat() / total
        ui.neon.bar(c, progressRect, share, Palette.CYAN, Palette.GREEN, Palette.withAlpha(Palette.MAGENTA, 0.6f))
        val pp = ui.style(ui.displayBoldPaint, 13f, Palette.MAGENTA, Paint.Align.LEFT)
        val text = rivalScoreText.of(m.rivalScore)
        c.drawText(text, progressRect.left, progressRect.bottom + 16f * u, pp)
        if (!m.rivalOnline) {
            val op = ui.style(ui.textPaint, 11f, Palette.ORANGE, Paint.Align.LEFT)
            c.drawText(s.rivalOffline, progressRect.left + pp.measureText(text) + 8f * u, progressRect.bottom + 16f * u, op)
        }
    }

    private fun drawItemButton(c: Canvas, sess: GameSession) {
        val r = itemRect
        val held = sess.heldItem
        if (held == null) {
            ui.neon.panel(c, r, r.height() / 2f, Palette.withAlpha(Palette.PANEL, 0.55f), Palette.withAlpha(Palette.MUTED, 0.7f), 0.2f)
            val p = ui.style(ui.textPaint, 14f, Palette.MUTED, Paint.Align.CENTER)
            c.drawText(s.noItem, r.centerX(), r.centerY() + p.textSize * 0.35f, p)
            return
        }
        val color = DuelArt.color(held)
        val pulse = if (reduce) 0.5f else 0.5f + 0.5f * sin(ui.time * 6f)
        ui.neon.glowBlob(c, r.centerX(), r.centerY(), r.width() * 0.6f, color, 0.2f + 0.2f * pulse)
        ui.neon.panel(c, r, r.height() / 2f, Palette.withAlpha(Palette.mix(Palette.PANEL, color, 0.3f), 0.95f), color, 0.8f + pulse)
        DuelArt.badge(c, ui, held, r.left + r.height() / 2f + 2f * u, r.centerY(), r.height() * 0.36f)
        val p = ui.style(ui.displayPaint, 16f, Palette.WHITE, Paint.Align.LEFT)
        ui.fitText(c, throwLabels[held.ordinal], r.left + r.height() + 6f * u, r.centerY() + p.textSize * 0.36f, p, r.width() - r.height() - 18f * u)
    }

    /** An opponent's SLOW: a blue tint while tap points are halved. */
    private fun drawSlowTint(c: Canvas, sess: GameSession) {
        val pulse = if (reduce) 0f else 0.04f * sin(ui.time * 5f)
        overlayPaint.color = Palette.withAlpha(Palette.BLUE, 0.1f + 0.12f * sess.slowFraction + pulse)
        c.drawRect(0f, 0f, width, height, overlayPaint)
    }

    /** Before the 3-2-1: both players face off until the agreed start. */
    private fun drawFaceOff(c: Canvas, m: Duel.Match) {
        overlayPaint.color = Palette.withAlpha(Palette.BG_TOP, 0.55f)
        c.drawRect(0f, 0f, width, height, overlayPaint)
        val cx = arena.centerX()
        val cy = arena.centerY()
        val np = ui.style(ui.displayPaint, 24f, Palette.CYAN, Paint.Align.CENTER)
        ui.fitText(c, myNick, cx, cy - 74f * u, np, arena.width() - 40f * u)
        val vp = ui.style(ui.displayPaint, 54f, Palette.WHITE, Paint.Align.CENTER)
        ui.neon.glowText(c, "VS", cx, cy + vp.textSize * 0.36f, vp, Palette.MAGENTA, 20f * u)
        np.textSize = 24f * u
        np.color = Visuals.AVATAR_COLORS[m.rivalAvatar.coerceIn(0, Visuals.AVATAR_COLORS.lastIndex)]
        ui.fitText(c, m.rivalNick, cx, cy + 96f * u, np, arena.width() - 40f * u)
        val gp = ui.style(ui.textPaint, 18f, Palette.withAlpha(Palette.WHITE, 0.6f + 0.4f * sin(ui.time * 5f)), Paint.Align.CENTER)
        c.drawText(s.getReady, cx, arena.bottom - 50f * u, gp)
    }

    private fun drawWarning(c: Canvas, sess: GameSession) {
        if (!sess.warningActive) return
        val f = sess.warningFraction
        val pulse = if (reduce) 0.6f else 0.5f + 0.5f * sin(ui.time * 30f)
        val a = (0.25f + 0.45f * f) * pulse
        val edge = 26f * u
        overlayPaint.color = Palette.withAlpha(Palette.RED, a)
        c.drawRect(0f, 0f, width, edge, overlayPaint)
        c.drawRect(0f, height - edge, width, height, overlayPaint)
        c.drawRect(0f, 0f, edge * 0.6f, height, overlayPaint)
        c.drawRect(width - edge * 0.6f, 0f, width, height, overlayPaint)
        val p = ui.style(ui.displayPaint, 26f, Palette.withAlpha(Palette.WHITE, 0.6f + 0.4f * pulse), Paint.Align.CENTER)
        c.drawText("!", timerX, arena.top + 30f * u, p)
    }

    private fun drawHold(c: Canvas, sess: GameSession) {
        if (sess.state != GameState.STOP) {
            if (tapBannerAge < 0.6f && phase == Phase.PLAY && sess.state.isActive) drawTapBanner(c)
            return
        }
        val reflex = sess.holdKind == InterruptKind.REFLEX
        val color = if (reflex) Palette.ORANGE else Palette.RED
        overlayPaint.color = Palette.withAlpha(color, if (reduce) 0.45f else 0.38f + 0.06f * sin(ui.time * 8f))
        c.drawRect(0f, 0f, width, height, overlayPaint)
        // Glitch bands (disabled with reduce effects: no flicker).
        if (!reduce && !reflex) {
            val seed = (ui.time * 12f).toInt()
            for (k in 0 until 4) {
                val yy = ((seed * 73 + k * 191) % 997) / 997f * height
                val hh = (6 + (seed + k * 7) % 18) * u
                overlayPaint.color = Palette.withAlpha(if (k % 2 == 0) Palette.CYAN else Palette.WHITE, 0.08f)
                c.drawRect(((seed * 31 + k * 13) % 40 - 20) * u, yy, width, yy + hh, overlayPaint)
            }
        }
        val cy = arena.centerY()
        val cx = arena.centerX()
        val size = min(arena.width(), arena.height()) * 0.26f
        val hold = sess.holdFraction
        if (reflex) {
            val ready = sess.reflexReady
            val p = ui.style(ui.displayPaint, 44f, Palette.WHITE, Paint.Align.CENTER)
            ui.neon.glowText(c, if (ready) "READY" else s.wait, cx, cy + p.textSize * 0.36f, p, Palette.ORANGE, 18f * u)
            val hp = ui.style(ui.textPaint, 15f, Palette.withAlpha(Palette.WHITE, 0.8f), Paint.Align.CENTER)
            ui.fitText(c, s.mechanicText(Mechanic.REFLEX), cx, cy + 50f * u, hp, width - 32f * u)
        } else {
            ui.neon.glowBlob(c, cx, cy, size * 2f, Palette.RED, 0.5f)
            ui.neon.octagon(c, cx, cy, size, Palette.withAlpha(Palette.RED, 0.95f), Palette.WHITE, 4f * u)
            ui.icons.draw(c, Icon.HAND, cx, cy - size * 0.28f, size * 0.62f, Palette.WHITE)
            val p = ui.style(ui.displayPaint, 1f, Palette.WHITE, Paint.Align.CENTER)
            p.textSize = size * 0.42f
            c.drawText("STOP", cx, cy + size * 0.52f, p)
            ui.neon.ring(c, cx, cy, size * 1.18f, hold, Palette.withAlpha(Palette.WHITE, 0.8f), 4f * u)
        }
    }

    private fun drawTapBanner(c: Canvas) {
        val t = tapBannerAge / 0.6f
        val p = ui.style(ui.displayPaint, 1f, Palette.withAlpha(Palette.CYAN, 1f - t), Paint.Align.CENTER)
        p.textSize = 64f * u * (1f + 0.5f * Ease.outCubic(t))
        ui.neon.glowText(c, "TAP!", arena.centerX(), arena.centerY() + p.textSize * 0.36f, p, Palette.withAlpha(Palette.CYAN, 1f - t), 20f * u)
    }

    private fun drawFrenzyBorder(c: Canvas, sess: GameSession) {
        if (sess.state != GameState.FRENZY) return
        val color = Palette.rainbow(ui.time * 0.8f)
        tmp.set(3f * u, 3f * u, width - 3f * u, height - 3f * u)
        ui.neon.glowStroke(c, tmp, 24f * u, color, if (reduce) 0.5f else 1.4f, 3f * u)
        // Remaining frenzy bar at the bottom.
        ui.neon.bar(c, frenzyBarRect, sess.frenzyFraction, color, Palette.rainbow(ui.time * 0.8f + 0.3f))
    }

    private fun drawFlash(c: Canvas) {
        if (flashAlpha <= 0f) return
        overlayPaint.color = Palette.withAlpha(flashColor, flashAlpha * 0.5f)
        c.drawRect(0f, 0f, width, height, overlayPaint)
    }

    private fun drawBanners(c: Canvas) {
        if (bannerAge >= bannerDuration) return
        val t = bannerAge / bannerDuration
        val appear = Ease.outBack((bannerAge / 0.25f).coerceIn(0f, 1f))
        val fade = if (t > 0.75f) 1f - (t - 0.75f) / 0.25f else 1f
        val p = ui.style(ui.displayPaint, if (bannerBig) 44f else 22f, Palette.withAlpha(Palette.WHITE, fade), Paint.Align.CENTER)
        p.textSize *= (0.6f + 0.4f * appear)
        ui.fitSize(p, bannerText, width - 40f * u)
        val y = if (bannerBig) arena.top + arena.height() * 0.22f else arena.top + 44f * u
        ui.neon.glowText(c, bannerText, width / 2f, y, p, Palette.withAlpha(bannerColor, fade), 16f * u)
    }

    private fun drawCountdown(c: Canvas, sess: GameSession) {
        if (sess.state != GameState.COUNTDOWN) return
        val v = countdownValue
        if (v <= 0) return
        overlayPaint.color = Palette.withAlpha(Palette.BG_TOP, 0.45f)
        c.drawRect(0f, 0f, width, height, overlayPaint)
        val t = (countdownAge / 0.8f).coerceIn(0f, 1f)
        val p = ui.style(ui.displayPaint, 1f, Palette.WHITE, Paint.Align.CENTER)
        p.textSize = 110f * u * (1.4f - 0.4f * Ease.outBack(t))
        val color = when (v) {
            3 -> Palette.MAGENTA
            2 -> Palette.PURPLE
            else -> Palette.CYAN
        }
        ui.neon.glowText(c, COUNT_LABELS[v.coerceIn(0, 3)], arena.centerX(), arena.centerY() + p.textSize * 0.36f, p, color, 26f * u)
        val op = ui.style(ui.semiPaint, 17f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitText(c, if (duel != null) rivalLabel else s.objectiveText(stage), arena.centerX(), arena.bottom - 40f * u, op, arena.width() - 40f * u)
        if (sess.resuming) {
            val rp = ui.style(ui.textPaint, 16f, Palette.DIM, Paint.Align.CENTER)
            c.drawText(s.resume, arena.centerX(), arena.top + 40f * u, rp)
        } else {
            val lp = ui.style(ui.displayBoldPaint, 18f, Visuals.typeColor(stage.type), Paint.Align.CENTER)
            c.drawText(stageLabel, arena.centerX(), arena.top + 44f * u, lp)
        }
    }

    private fun drawPause(c: Canvas) {
        overlayPaint.color = Palette.withAlpha(Palette.BG_TOP, 0.82f)
        c.drawRect(0f, 0f, width, height, overlayPaint)
        val p = ui.style(ui.displayPaint, 36f, Palette.WHITE, Paint.Align.CENTER)
        ui.neon.glowText(c, s.paused, width / 2f, pauseButtons[0].rect.top - 50f * u, p, Palette.CYAN, 16f * u)
        val op = ui.style(ui.semiPaint, 15f, Palette.DIM, Paint.Align.CENTER)
        ui.fitText(c, s.objectiveText(stage), width / 2f, pauseButtons[0].rect.top - 18f * u, op, width - 48f * u)
        drawButtons(c)
    }

    private fun drawIntro(c: Canvas) {
        val m = introMechanic ?: return
        overlayPaint.color = Palette.withAlpha(Palette.BG_TOP, 0.7f)
        c.drawRect(0f, 0f, width, height, overlayPaint)
        val a = Ease.outBack((age / 0.4f).coerceIn(0f, 1f))
        val w = min(width - 40f * u, 360f * u)
        val h = 330f * u
        tmp.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        c.save()
        c.scale(0.85f + 0.15f * a, 0.85f + 0.15f * a, tmp.centerX(), tmp.centerY())
        val color = mechanicColor(m)
        ui.neon.panel(c, tmp, 22f * u, Palette.PANEL_LIGHT, color, 1.2f)
        val np = ui.style(ui.textPaint, 13f, Palette.DIM, Paint.Align.CENTER)
        np.letterSpacing = 0.3f
        c.drawText(if (s.code == "pt") "NOVO" else "NEW", tmp.centerX(), tmp.top + 34f * u, np)
        np.letterSpacing = 0.03f
        val tp = ui.style(ui.displayPaint, 30f, Palette.WHITE, Paint.Align.CENTER)
        ui.fitSize(tp, s.mechanicTitle(m), w - 40f * u)
        ui.neon.glowText(c, s.mechanicTitle(m), tmp.centerX(), tmp.top + 76f * u, tp, color, 14f * u)
        drawMechanicArt(c, m, tmp.centerX(), tmp.top + 160f * u, 56f * u, color)
        val bp = ui.style(ui.semiPaint, 17f, Palette.TEXT, Paint.Align.CENTER)
        ui.wrapText(c, s.mechanicText(m), tmp.centerX(), tmp.top + 248f * u, bp, w - 48f * u, 22f * u)
        val cp = ui.style(ui.textPaint, 14f, Palette.withAlpha(Palette.CYAN, 0.6f + 0.4f * sin(ui.time * 4f)), Paint.Align.CENTER)
        c.drawText(s.tapToContinue, tmp.centerX(), tmp.bottom - 18f * u, cp)
        c.restore()
    }

    private fun mechanicColor(m: Mechanic): Int = when (m) {
        Mechanic.STOP, Mechanic.FAKE_STOP, Mechanic.BOSS, Mechanic.CRITICAL_ZONES -> Palette.RED
        Mechanic.FRENZY -> Palette.MAGENTA
        Mechanic.PERFECT, Mechanic.COINS -> Palette.GOLD
        Mechanic.REFLEX -> Palette.ORANGE
        Mechanic.HOT_ZONES, Mechanic.MOVING_ZONES, Mechanic.SPECIAL_ZONES, Mechanic.LOCK_ZONES -> Palette.GREEN
        else -> Palette.CYAN
    }

    private fun drawMechanicArt(c: Canvas, m: Mechanic, cx: Float, cy: Float, r: Float, color: Int) {
        val neon = ui.neon
        val beat = (ui.time % 1f)
        when (m) {
            Mechanic.STOP, Mechanic.FAKE_STOP -> {
                neon.octagon(c, cx, cy, r, Palette.RED, Palette.WHITE, 3f * u)
                ui.icons.draw(c, Icon.HAND, cx, cy - r * 0.2f, r * 0.7f, Palette.WHITE)
                if (m == Mechanic.FAKE_STOP) {
                    val p = ui.style(ui.displayPaint, 26f, Palette.GOLD, Paint.Align.CENTER)
                    c.drawText("?", cx + r * 0.95f, cy - r * 0.6f, p)
                }
            }
            Mechanic.HOT_ZONES, Mechanic.MOVING_ZONES, Mechanic.LOCK_ZONES, Mechanic.SPECIAL_ZONES, Mechanic.PERFECT, Mechanic.CRITICAL_ZONES -> {
                val zc = if (m == Mechanic.CRITICAL_ZONES) Palette.RED else if (m == Mechanic.PERFECT) Palette.GOLD else Palette.MAGENTA
                val ox = if (m == Mechanic.MOVING_ZONES) sin(ui.time * 2f) * r * 0.6f else 0f
                neon.glowBlob(c, cx + ox, cy, r * 1.8f, zc, 0.35f)
                neon.circleStroke(c, cx + ox, cy, r, zc, 3f * u)
                neon.circleStroke(c, cx + ox, cy, r * 0.3f, Palette.GOLD, 1.5f * u)
                val p = ui.style(ui.displayPaint, 22f, Palette.WHITE, Paint.Align.CENTER)
                when (m) {
                    Mechanic.SPECIAL_ZONES -> ui.icons.draw(c, Icon.COIN, cx, cy, r * 0.8f, Palette.GOLD)
                    Mechanic.LOCK_ZONES -> ui.icons.draw(c, Icon.LOCK, cx, cy, r * 0.8f, Palette.WHITE)
                    Mechanic.CRITICAL_ZONES -> ui.icons.draw(c, Icon.TARGET, cx, cy, r * 1.2f, Palette.RED)
                    Mechanic.PERFECT -> neon.circle(c, cx, cy, r * 0.12f, Palette.GOLD)
                    else -> c.drawText("x3", cx + ox, cy + p.textSize * 0.36f, p)
                }
                neon.circleStroke(c, cx + ox, cy, r * (1f + beat * 0.6f), Palette.withAlpha(zc, 1f - beat), 2f * u)
            }
            Mechanic.FRENZY -> ui.icons.draw(c, Icon.FLAME, cx, cy, r * 1.6f, Palette.rainbow(ui.time * 0.5f))
            Mechanic.COINS -> ui.icons.draw(c, Icon.COIN, cx, cy, r * 1.5f, Palette.GOLD)
            Mechanic.BOSS -> ui.icons.draw(c, Icon.SKULL, cx, cy, r * 1.5f, Palette.RED, Palette.BG_TOP)
            Mechanic.REFLEX -> {
                val p = ui.style(ui.displayPaint, 30f, Palette.ORANGE, Paint.Align.CENTER)
                ui.neon.glowText(c, if (beat < 0.5f) "WAIT…" else "TAP!", cx, cy + p.textSize * 0.36f, p, Palette.ORANGE, 12f * u)
            }
            Mechanic.COMBO -> {
                val p = ui.style(ui.displayPaint, 40f, Palette.MAGENTA, Paint.Align.CENTER)
                ui.neon.glowText(c, "x3", cx, cy + p.textSize * 0.36f, p, Palette.MAGENTA, 14f * u)
            }
            Mechanic.TAP -> {
                neon.circle(c, cx, cy, r * 0.25f, Palette.CYAN)
                neon.circleStroke(c, cx, cy, r * (0.3f + beat), Palette.withAlpha(Palette.CYAN, 1f - beat), 3f * u)
            }
        }
    }

    companion object {
        fun forStage(app: GameApp, number: Int) = PlayScreen(app, app.progression.stage(number))

        fun daily(app: GameApp): PlayScreen {
            val (template, config) = app.progression.daily()
            return PlayScreen(app, config, daily = true, dailyTitle = app.strings.dailyTitle(template))
        }

        /** The live duel [match], once its start time is set. */
        fun duel(app: GameApp, match: Duel.Match) = PlayScreen(app, StageCatalog.duel(), duel = match)

        private const val END_DELAY_S = 1.6f
        /** Radius of a duel's falling orb (layout units). */
        private const val ORB_R = 30f
        private const val POPUP_PERFECT = 1
        private const val POPUP_POINTS = 2
        private val ZONE_LABELS = arrayOf("x2", "x3", "x5", "", "", "", "", "")
        private val COUNT_LABELS = arrayOf("", "1", "2", "3")
        private val SMALL_NUMBERS = Array(10) { it.toString() }
    }
}
