package com.taptap.game.core.engine

import com.taptap.game.core.stage.StageConfig
import com.taptap.game.core.stage.StageType
import com.taptap.game.core.util.Rng
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One match of TAP TAP: the central game controller (spec §39).
 *
 * Driven by a monotonic clock in milliseconds (Android: `SystemClock.uptimeMillis()`, the same
 * base as `MotionEvent.getEventTime()`):
 *  - [update] is called every frame,
 *  - [tap] is called from the input handler with the touch's own event time.
 *
 * Every scheduled transition (interrupts, zones, frenzy, combo timeout, match end) is processed
 * in chronological order up to the time being simulated, so a tap is judged against the exact
 * state at the moment the finger touched the screen — never against a later frame. Input is
 * registered before any feedback is produced (spec §6).
 *
 * Two clocks are kept: [matchTimeMs] (play time, frozen while paused/counting down) and
 * [activeTimeMs] (additionally frozen during STOP holds) which drives zones, the combo timer
 * and the frenzy meter decay — so zones freeze in place while the player must not tap.
 *
 * Not thread safe: call from a single thread (the UI thread).
 */
class GameSession(
    val stage: StageConfig,
    val loadout: Loadout,
    /** Arena height divided by arena width (tap/zone coordinates are in arena widths). */
    val arenaHeight: Float,
    seed: Long,
    private val listener: GameListener = GameListener.NONE,
) {
    private val rng = Rng(Rng.mix(seed, stage.seed))

    // ---- state ---------------------------------------------------------------------------------
    var state: GameState = GameState.READY
        private set

    /** True while counting down after a pause (vs. the opening countdown). */
    var resuming: Boolean = false
        private set
    private var stateBeforePause: GameState = GameState.PLAYING
    private var countdownRemainingMs = 0L
    private var lastCountdownValue = 0

    private var lastRealMs = 0L
    var matchTimeMs: Long = 0L
        private set
    var activeTimeMs: Long = 0L
        private set
    var durationMs: Long = stage.durationMs
        private set
    val timeLeftMs: Long get() = max(0L, durationMs - matchTimeMs)

    // ---- scoring ------------------------------------------------------------------------------
    var score: Long = 0L
        private set
    var taps: Long = 0L
        private set
    var touches: Int = 0
        private set
    var combo: Int = 0
        private set
    var maxCombo: Int = 0
        private set
    var comboTier: Int = 0
        private set
    val comboMultiplier: Float get() = GameBalance.COMBO_MULTIPLIERS[comboTier]
    private var lastComboActiveMs = 0L
    private val comboThresholds = IntArray(GameBalance.COMBO_THRESHOLDS.size) { i ->
        if (i == 0) 0 else max(i, (GameBalance.COMBO_THRESHOLDS[i] * loadout.comboThresholdScale).roundToInt())
    }

    /** Remaining fraction of the combo timer (1 = just tapped, 0 = about to break). */
    val comboTimerFraction: Float
        get() = if (combo == 0 || state == GameState.FRENZY) 1f else
            (1f - (activeTimeMs - lastComboActiveMs).toFloat() / stage.comboTimeoutMs).coerceIn(0f, 1f)

    fun comboThreshold(tier: Int): Int = comboThresholds[tier.coerceIn(0, comboThresholds.lastIndex)]

    var comboShieldsLeft: Int = loadout.comboShields
        private set
    var stopShieldsLeft: Int = loadout.stopShields
        private set
    val maxLives: Int = stage.lives
    var lives: Int = stage.lives
        private set

    // ---- statistics -----------------------------------------------------------------------------
    var perfects: Int = 0
        private set
    var zoneHits: Int = 0
        private set
    var stopErrors: Int = 0
        private set
    var stopsSurvived: Int = 0
        private set
    var interruptsSeen: Int = 0
        private set
    var frenzies: Int = 0
        private set
    var megaFrenzies: Int = 0
        private set
    var coins: Int = 0
        private set
    var goldenHits: Int = 0
        private set
    var reflexBestMs: Long = -1L
        private set
    var timeBonusMs: Long = 0L
        private set
    var droppedTaps: Int = 0
        private set
    var badTimestamps: Int = 0
        private set
    val tps = TpsMeter()
    private val limiter = TapLimiter()
    private var lastTapEventMs = Long.MIN_VALUE

    var result: MatchResult? = null
        private set
    private var objectiveAnnounced = false

    // ---- frenzy -------------------------------------------------------------------------------
    /** Frenzy meter 0..1 (fills with taps, zones, perfects and combo tiers). */
    var frenzyMeter: Float = 0f
        private set
    private var frenzyQueued = false
    var megaFrenzy: Boolean = false
        private set
    private var frenzyStartAt = 0L
    private var frenzyEndAt = 0L
    private var lastMeterGainActiveMs = 0L
    private var megaTouches = 0

    /** Remaining fraction of the running frenzy (1 → 0). */
    val frenzyFraction: Float
        get() = if (state != GameState.FRENZY || frenzyEndAt <= frenzyStartAt) 0f else
            ((frenzyEndAt - matchTimeMs).toFloat() / (frenzyEndAt - frenzyStartAt)).coerceIn(0f, 1f)

    val frenzyMultiplier: Float
        get() = if (megaFrenzy) GameBalance.MEGA_FRENZY_MULTIPLIER + GameBalance.FRENZY_BOOST_MULT * loadout.frenzyBoostLevel
        else loadout.frenzyMultiplier

    // ---- interrupts -----------------------------------------------------------------------------
    private val interrupts: MutableList<Interrupt> =
        stage.stop?.let { InterruptPlanner.plan(it, stage.durationMs, rng) } ?: ArrayList()
    private var nextInterrupt = 0

    /** The interrupt currently warning or active, if any. */
    var currentInterrupt: Interrupt? = null
        private set

    /** STOP/FAKE warning telegraph is showing. */
    val warningActive: Boolean
        get() = currentInterrupt?.let { it.warned && !it.started } == true

    val warningFraction: Float
        get() {
            val i = currentInterrupt ?: return 0f
            if (!i.warned || i.started || i.startAt <= i.warnAt) return 0f
            return ((matchTimeMs - i.warnAt).toFloat() / (i.startAt - i.warnAt)).coerceIn(0f, 1f)
        }

    /** Kind of interrupt holding the player (state == STOP). */
    val holdKind: InterruptKind? get() = if (state == GameState.STOP) currentInterrupt?.kind else null

    /** During a REFLEX hold: true in the READY part, false in the WAIT part. */
    val reflexReady: Boolean
        get() = currentInterrupt?.let { it.kind == InterruptKind.REFLEX && matchTimeMs < it.startAt + GameBalance.REFLEX_READY_MS } == true

    /** Remaining fraction of the STOP hold (1 → 0); 0 when not in STOP. */
    val holdFraction: Float
        get() {
            val i = currentInterrupt ?: return 0f
            if (state != GameState.STOP || i.endAt <= i.startAt) return 0f
            return ((i.endAt - matchTimeMs).toFloat() / (i.endAt - i.startAt)).coerceIn(0f, 1f)
        }

    val plannedInterrupts: Int get() = interrupts.size

    private var reflexAwaiting = false
    private var reflexGoAt = 0L
    private var reflexDeadline = 0L

    var goBoostUntil: Long = 0L
        private set
    val goBoostActive: Boolean get() = matchTimeMs < goBoostUntil

    // ---- zones ------------------------------------------------------------------------------------
    val zones: Array<Zone> = Array(MAX_ZONES) { Zone(it) }
    private var zoneIdCounter = 0
    private var nextZoneSpawnActive = FIRST_ZONE_DELAY_MS
    private var lastTimeZoneActive = -TIME_ZONE_COOLDOWN_MS
    private var goldenSpawned = false
    private val weightScratch = IntArray(ZONE_TYPES.size)

    /** A mandatory (locked) zone is on screen: taps outside zones are misses. */
    val lockActive: Boolean
        get() {
            for (z in zones) if (z.active && z.locked) return true
            return false
        }

    private var nextTimeWarning = 0

    // ============================================================================================
    // Control
    // ============================================================================================

    /** Starts the opening 3-2-1 countdown. */
    fun start(nowMs: Long) {
        check(state == GameState.READY) { "Session already started" }
        lastRealMs = nowMs
        beginCountdown(resume = false)
    }

    /** Advances the simulation to [nowMs]. Call once per frame. */
    fun update(nowMs: Long) {
        advanceTo(nowMs)
    }

    fun pause(nowMs: Long) {
        if (state == GameState.PAUSED || state == GameState.FINISHED || state == GameState.READY) return
        advanceTo(nowMs)
        if (state == GameState.FINISHED) return
        stateBeforePause = when (state) {
            GameState.FRENZY -> GameState.FRENZY
            GameState.COUNTDOWN -> if (resuming) stateBeforePause else GameState.PLAYING
            else -> GameState.PLAYING
        }
        val interrupt = currentInterrupt
        if (interrupt != null) {
            if (interrupt.started) {
                // Pausing during a hold ends it: resuming straight into a STOP would punish the
                // first tap after "TAP!" (spec §36). Counts as survived if clean.
                completeInterrupt(interrupt, fromPause = true)
            } else {
                // Warning in progress: re-arm it so the full telegraph plays again after resume.
                interrupt.warned = false
                nextInterrupt--
                interruptsSeen--
                currentInterrupt = null
                shiftInterruptsFrom(nextInterrupt, matchTimeMs + REARM_DELAY_MS - interrupt.warnAt)
            }
        }
        state = GameState.PAUSED
        listener.onPaused()
    }

    /** Leaves pause with a 3-2-1 countdown (spec §36). */
    fun resume(nowMs: Long) {
        if (state != GameState.PAUSED) return
        lastRealMs = nowMs
        beginCountdown(resume = true)
    }

    /** Ends the match immediately (player quit). */
    fun abort(nowMs: Long) {
        if (state == GameState.FINISHED) return
        if (state != GameState.PAUSED && state != GameState.READY) advanceTo(nowMs)
        if (state != GameState.FINISHED) finish(FailReason.ABORTED)
    }

    // ============================================================================================
    // Input
    // ============================================================================================

    /**
     * Registers a touch-down at [eventTimeMs] (the MotionEvent's time) and position ([x], [y]) in
     * arena units. [activePointers] is the number of fingers down including this one and
     * [receivedAtMs] the clock time when the event was handled (used to reject timestamps from
     * the future). Returns true when the tap counted (scored), false when ignored or penalised.
     */
    fun tap(eventTimeMs: Long, x: Float, y: Float, activePointers: Int = 1, receivedAtMs: Long = eventTimeMs): Boolean {
        if (eventTimeMs > receivedAtMs + GameBalance.MAX_FUTURE_EVENT_MS ||
            (lastTapEventMs != Long.MIN_VALUE && eventTimeMs < lastTapEventMs - MAX_BACKWARDS_MS)
        ) {
            badTimestamps++
            listener.onTapIgnored(x, y, IgnoreReason.BAD_TIMESTAMP)
            return false
        }
        if (eventTimeMs > lastRealMs) advanceTo(eventTimeMs)
        if (!state.isActive) {
            listener.onTapIgnored(x, y, IgnoreReason.NOT_PLAYING)
            return false
        }
        if (activePointers > GameBalance.MAX_ACTIVE_POINTERS) {
            listener.onTapIgnored(x, y, IgnoreReason.TOO_MANY_POINTERS)
            return false
        }
        if (!limiter.tryConsume(eventTimeMs)) {
            droppedTaps++
            listener.onTapIgnored(x, y, IgnoreReason.RATE_LIMIT)
            return false
        }
        if (eventTimeMs > lastTapEventMs) lastTapEventMs = eventTimeMs
        // Late-delivered event: judge it at the time it happened.
        val tapMatchTime = matchTimeMs - (lastRealMs - eventTimeMs).coerceAtLeast(0L)

        if (state == GameState.STOP) {
            val interrupt = currentInterrupt ?: return false
            val sinceStart = tapMatchTime - interrupt.startAt
            return when {
                sinceStart < 0 -> scoreTap(x, y, eventTimeMs)
                sinceStart < reactionGraceMs() -> {
                    listener.onTapIgnored(x, y, IgnoreReason.REACTION_GRACE)
                    false
                }
                else -> {
                    fault(interrupt, x, y)
                    false
                }
            }
        }

        if (reflexAwaiting) {
            val reaction = (tapMatchTime - reflexGoAt).coerceAtLeast(0L)
            reflexAwaiting = false
            val grade = ReflexGrade.of(reaction)
            val bonus = (grade.bonusTaps * loadout.tapValue * comboMultiplier).roundToInt()
            score += bonus
            if (reflexBestMs < 0 || reaction < reflexBestMs) reflexBestMs = reaction
            listener.onReflexResult(reaction, bonus, grade)
        }
        return scoreTap(x, y, eventTimeMs)
    }

    private fun reactionGraceMs(): Long = stage.stop?.graceMs ?: GameBalance.MIN_REACTION_GRACE_MS

    private fun scoreTap(x: Float, y: Float, eventTimeMs: Long): Boolean {
        val t = activeTimeMs
        // --- hot zone hit test at the tap's time
        var hit: Zone? = null
        var hitDistance = 0f
        var best = Float.MAX_VALUE
        for (z in zones) {
            if (!z.active) continue
            val dx = x - z.x(t)
            val dy = y - z.y(t)
            val d = sqrt(dx * dx + dy * dy)
            val r = z.radius(t)
            val reach = r * GameBalance.ZONE_HIT_TOLERANCE
            if (d <= reach && d / reach < best) {
                best = d / reach
                hit = z
                hitDistance = d
            }
        }
        if (hit == null && lockActive) {
            listener.onMiss(x, y)
            breakCombo(BreakReason.ZONE_MISS)
            return false
        }
        val perfect = hit != null && stage.hasPerfect &&
            hitDistance <= hit.radius(t) * loadout.perfectRadiusFraction * GameBalance.ZONE_HIT_TOLERANCE

        touches++
        tps.record(eventTimeMs)

        // --- combo
        combo += 1 + (if (hit?.type == ZoneType.COMBO) GameBalance.COMBO_ZONE_EXTRA else 0) +
            (if (perfect) GameBalance.PERFECT_COMBO_EXTRA else 0)
        if (combo > maxCombo) maxCombo = combo
        lastComboActiveMs = activeTimeMs
        var tierUps = 0
        while (comboTier < comboThresholds.lastIndex && combo >= comboThresholds[comboTier + 1]) {
            comboTier++
            tierUps++
        }

        // --- points
        val tapValue = loadout.tapValue
        var p = tapValue * comboMultiplier
        if (hit != null && (hit.type.isMultiplier || hit.type == ZoneType.CRITICAL)) {
            p *= hit.type.multiplier * loadout.zoneMultiplierBonus
        }
        if (state == GameState.FRENZY) p *= frenzyMultiplier
        if (goBoostActive) p *= GameBalance.FAKE_STOP_MULTIPLIER
        var points = max(1, p.roundToInt())
        if (perfect) {
            points += (tapValue * GameBalance.PERFECT_BONUS_TAPS * comboMultiplier * loadout.perfectBonusScale).roundToInt()
        }
        score += points
        taps += tapValue

        // --- zone effects
        val kind = when {
            perfect -> TapKind.PERFECT
            hit != null -> TapKind.ZONE
            else -> TapKind.NORMAL
        }
        listener.onTap(x, y, points, kind, hit, comboTier)
        if (tierUps > 0) {
            listener.onComboTier(comboTier, comboMultiplier)
            gainMeter(GameBalance.FRENZY_FILL_COMBO_TIER * tierUps)
        }
        if (hit != null) applyZoneHit(hit, perfect, x, y)
        if (perfect) {
            perfects++
            if (perfects % GameBalance.PERFECTS_PER_COIN == 0) addCoins(1, x, y)
        }
        if (state == GameState.FRENZY && megaFrenzy) {
            megaTouches++
            if (megaTouches % GameBalance.MEGA_FRENZY_TAPS_PER_COIN == 0) addCoins(1, x, y)
        }

        // --- frenzy meter
        var gain = GameBalance.FRENZY_FILL_TAP
        if (hit != null) gain += GameBalance.FRENZY_FILL_ZONE
        if (perfect) gain += GameBalance.FRENZY_FILL_PERFECT
        gainMeter(gain)

        checkObjective()
        return true
    }

    private fun applyZoneHit(zone: Zone, perfect: Boolean, x: Float, y: Float) {
        zoneHits++
        zone.hits++
        listener.onZoneHit(zone, perfect)
        when (zone.type) {
            ZoneType.COIN -> addCoins(1, x, y)
            ZoneType.TIME -> {
                val add = min(GameBalance.TIME_BONUS_MS, GameBalance.TIME_BONUS_CAP_MS - timeBonusMs)
                if (add > 0) {
                    timeBonusMs += add
                    durationMs += add
                    recomputeTimeWarnings()
                    listener.onTimeBonus(add)
                }
            }
            ZoneType.GOLDEN -> {
                goldenHits++
                addCoins(rng.between(GameBalance.GOLDEN_COINS_MIN, GameBalance.GOLDEN_COINS_MAX), x, y)
            }
            else -> Unit
        }
        if (zone.hits >= zone.type.maxHits) {
            zone.active = false
            listener.onZonePop(zone)
        }
    }

    private fun addCoins(amount: Int, x: Float, y: Float) {
        if (amount <= 0) return
        coins += amount
        listener.onCoins(amount, x, y)
    }

    private fun fault(interrupt: Interrupt, x: Float, y: Float) {
        if (interrupt.shielded) {
            listener.onTapIgnored(x, y, IgnoreReason.REACTION_GRACE)
            return
        }
        if (interrupt.faulted) {
            val penalty = min(score, GameBalance.STOP_REPEAT_PENALTY_POINTS.toLong()).toInt()
            score -= penalty
            listener.onStopFault(x, y, penalty, lifeLost = false, repeat = true)
            return
        }
        if (stopShieldsLeft > 0) {
            stopShieldsLeft--
            interrupt.shielded = true
            listener.onShieldUsed(ShieldType.STOP)
            return
        }
        interrupt.faulted = true
        stopErrors++
        val tier = stage.penaltyTier.ordinal
        val penalty = min(
            score,
            max(GameBalance.STOP_PENALTY_MIN_POINTS[tier].toLong(), (score * GameBalance.STOP_PENALTY_FRACTION[tier]).toLong()),
        ).toInt()
        score -= penalty
        if (stage.penaltyTier != PenaltyTier.LIGHT) {
            taps = max(0L, taps - GameBalance.STOP_TAP_LOSS.toLong() * loadout.tapValue)
            breakCombo(if (interrupt.kind == InterruptKind.REFLEX) BreakReason.FALSE_START else BreakReason.STOP_FAULT)
        }
        var lifeLost = false
        if (maxLives > 0 && lives > 0) {
            lives--
            lifeLost = true
        }
        listener.onStopFault(x, y, penalty, lifeLost, repeat = false)
        if (lifeLost) {
            listener.onLifeLost(lives)
            if (lives == 0) finish(FailReason.NO_LIVES)
        }
    }

    private fun breakCombo(reason: BreakReason) {
        if (combo == 0 || state == GameState.FRENZY) return
        if (comboShieldsLeft > 0 && combo >= SHIELD_MIN_COMBO) {
            comboShieldsLeft--
            lastComboActiveMs = activeTimeMs
            listener.onShieldUsed(ShieldType.COMBO)
            return
        }
        val lost = combo
        combo = 0
        comboTier = 0
        listener.onComboBreak(lost, reason)
    }

    private fun gainMeter(amount: Float) {
        val frenzy = stage.frenzy ?: return
        if (state == GameState.FRENZY) return
        lastMeterGainActiveMs = activeTimeMs
        val before = frenzyMeter
        frenzyMeter = min(1f, frenzyMeter + amount * loadout.frenzyFillScale * frenzy.fillScale)
        if (frenzyMeter >= 1f) {
            if (canStartFrenzy()) startFrenzy() else if (!frenzyQueued) {
                frenzyQueued = true
                if (before < 1f) listener.onFrenzyReady()
            }
        }
    }

    private fun canStartFrenzy(): Boolean {
        if (state != GameState.PLAYING || currentInterrupt != null || timeLeftMs <= FRENZY_MIN_TIME_LEFT_MS) return false
        // An interrupt is postponed by a frenzy at most once; afterwards frenzy waits for it,
        // so frequent frenzies can never erase the STOP challenge.
        if (nextInterrupt < interrupts.size) {
            val next = interrupts[nextInterrupt]
            val frenzyLength = if (megaFrenzyPossible) GameBalance.MEGA_FRENZY_DURATION_MS else loadout.frenzyDurationMs
            if (next.postponed && next.warnAt < matchTimeMs + frenzyLength + GameBalance.INTERRUPT_AFTER_FRENZY_MS) return false
        }
        return true
    }

    private val megaFrenzyPossible: Boolean get() = (stage.frenzy?.megaChance ?: 0f) > 0f

    private fun startFrenzy() {
        val frenzy = stage.frenzy ?: return
        frenzyQueued = false
        megaFrenzy = rng.chance(frenzy.megaChance)
        megaTouches = 0
        frenzies++
        if (megaFrenzy) megaFrenzies++
        state = GameState.FRENZY
        frenzyStartAt = matchTimeMs
        frenzyEndAt = matchTimeMs + if (megaFrenzy) GameBalance.MEGA_FRENZY_DURATION_MS else loadout.frenzyDurationMs
        frenzyMeter = 0f
        // Frenzy never overlaps a STOP: push pending interrupts past its end (spec §65 Frenzy + STOP).
        if (nextInterrupt < interrupts.size) {
            val first = interrupts[nextInterrupt]
            val earliestWarn = frenzyEndAt + GameBalance.INTERRUPT_AFTER_FRENZY_MS
            if (first.warnAt < earliestWarn) {
                first.postponed = true
                shiftInterruptsFrom(nextInterrupt, earliestWarn - first.warnAt)
            }
        }
        listener.onFrenzyStart(megaFrenzy)
        checkObjective()
    }

    private fun endFrenzy() {
        state = GameState.PLAYING
        megaFrenzy = false
        lastComboActiveMs = activeTimeMs
        lastMeterGainActiveMs = activeTimeMs
        listener.onFrenzyEnd()
    }

    /** Shifts interrupt [from] and later ones by [delta], dropping any that no longer fit. */
    private fun shiftInterruptsFrom(from: Int, delta: Long) {
        if (from < 0 || delta <= 0) return
        val gap = (stage.stop?.minGapMs ?: 0L) + (stage.stop?.warningMs ?: 0L)
        var minWarn = 0L
        for (i in from until interrupts.size) {
            val it = interrupts[i]
            val needed = if (i == from) delta else minWarn - it.warnAt
            if (needed > 0) it.shift(needed)
            minWarn = it.endAt + gap
        }
        val limit = durationMs - GameBalance.INTERRUPT_END_MARGIN_MS
        while (interrupts.size > from && interrupts.last().startAt > limit) interrupts.removeAt(interrupts.lastIndex)
    }

    // ============================================================================================
    // Simulation
    // ============================================================================================

    private fun beginCountdown(resume: Boolean) {
        resuming = resume
        state = GameState.COUNTDOWN
        countdownRemainingMs = GameBalance.COUNTDOWN_MS
        lastCountdownValue = 3
        listener.onCountdown(3, resume)
    }

    private fun advanceTo(nowMs: Long) {
        if (nowMs <= lastRealMs) return
        var dt = nowMs - lastRealMs
        lastRealMs = nowMs
        var guard = 0
        while (dt > 0 && guard++ < MAX_STEPS_PER_ADVANCE) {
            when (state) {
                GameState.COUNTDOWN -> {
                    val step = min(dt, countdownRemainingMs)
                    countdownRemainingMs -= step
                    dt -= step
                    val value = ((countdownRemainingMs + GameBalance.COUNTDOWN_STEP_MS - 1) / GameBalance.COUNTDOWN_STEP_MS).toInt()
                    while (lastCountdownValue > 1 && value < lastCountdownValue) {
                        lastCountdownValue--
                        listener.onCountdown(lastCountdownValue, resuming)
                    }
                    if (countdownRemainingMs <= 0) finishCountdown()
                }
                GameState.PLAYING, GameState.STOP, GameState.FRENZY -> {
                    val next = nextEventMatchTime()
                    val step = min(dt, max(0L, next - matchTimeMs))
                    if (step > 0) advanceClocks(step)
                    dt -= step
                    if (matchTimeMs >= next) processDueEvents()
                }
                else -> return
            }
        }
    }

    private fun finishCountdown() {
        val target = if (resuming) stateBeforePause else GameState.PLAYING
        state = if (target == GameState.FRENZY && frenzyEndAt > matchTimeMs) GameState.FRENZY else GameState.PLAYING
        lastComboActiveMs = activeTimeMs
        resuming = false
        listener.onCountdown(0, false)
        if (frenzyQueued && canStartFrenzy()) startFrenzy()
    }

    private val activeClockRunning: Boolean get() = state != GameState.STOP

    private fun advanceClocks(step: Long) {
        matchTimeMs += step
        if (activeClockRunning) {
            activeTimeMs += step
            if (state != GameState.FRENZY && frenzyMeter > 0f && !frenzyQueued &&
                activeTimeMs - lastMeterGainActiveMs > GameBalance.FRENZY_DECAY_DELAY_MS
            ) {
                frenzyMeter = max(0f, frenzyMeter - GameBalance.FRENZY_DECAY_PER_SECOND * step / 1000f)
            }
        }
    }

    /** Absolute match time of the next scheduled transition. */
    private fun nextEventMatchTime(): Long {
        var next = durationMs
        val interrupt = currentInterrupt
        if (interrupt != null) {
            next = min(next, if (!interrupt.started) interrupt.startAt else interrupt.endAt)
        } else if (nextInterrupt < interrupts.size) {
            next = min(next, interrupts[nextInterrupt].warnAt)
        }
        if (state == GameState.FRENZY) next = min(next, frenzyEndAt)
        if (reflexAwaiting) next = min(next, reflexDeadline)
        if (goBoostUntil > matchTimeMs) next = min(next, goBoostUntil)
        if (nextTimeWarning < GameBalance.TIME_WARNINGS_S.size) {
            next = min(next, durationMs - GameBalance.TIME_WARNINGS_S[nextTimeWarning] * 1000L)
        }
        if (activeClockRunning) {
            var activeNext = Long.MAX_VALUE
            if (stage.zones != null) activeNext = min(activeNext, nextZoneSpawnActive)
            for (z in zones) if (z.active) activeNext = min(activeNext, z.expireAt + GameBalance.ZONE_EXPIRE_GRACE_MS)
            if (combo > 0 && state != GameState.FRENZY) activeNext = min(activeNext, lastComboActiveMs + stage.comboTimeoutMs)
            if (activeNext != Long.MAX_VALUE) next = min(next, matchTimeMs + max(0L, activeNext - activeTimeMs))
        }
        return max(next, matchTimeMs)
    }

    private fun processDueEvents() {
        val now = matchTimeMs
        if (activeClockRunning) {
            for (z in zones) {
                if (z.active && activeTimeMs >= z.expireAt + GameBalance.ZONE_EXPIRE_GRACE_MS) {
                    z.active = false
                    listener.onZoneExpire(z)
                }
            }
            if (combo > 0 && state != GameState.FRENZY && activeTimeMs - lastComboActiveMs >= stage.comboTimeoutMs) {
                breakCombo(BreakReason.TIMEOUT)
                if (combo > 0) lastComboActiveMs = activeTimeMs
            }
        }
        if (state == GameState.FRENZY && now >= frenzyEndAt) endFrenzy()
        if (goBoostUntil in 1..now) goBoostUntil = 0L
        if (reflexAwaiting && now >= reflexDeadline) {
            reflexAwaiting = false
            listener.onReflexResult(-1L, 0, ReflexGrade.MISSED)
        }
        processInterrupts(now)
        if (activeClockRunning && stage.zones != null && activeTimeMs >= nextZoneSpawnActive) spawnZone()
        while (nextTimeWarning < GameBalance.TIME_WARNINGS_S.size &&
            now >= durationMs - GameBalance.TIME_WARNINGS_S[nextTimeWarning] * 1000L
        ) {
            listener.onTimeWarning(GameBalance.TIME_WARNINGS_S[nextTimeWarning])
            nextTimeWarning++
        }
        if (state.isActive && now >= durationMs) finish(FailReason.NONE)
    }

    private fun processInterrupts(now: Long) {
        var interrupt = currentInterrupt
        if (interrupt == null && nextInterrupt < interrupts.size && now >= interrupts[nextInterrupt].warnAt) {
            val candidate = interrupts[nextInterrupt]
            if (state == GameState.FRENZY) {
                shiftInterruptsFrom(nextInterrupt, frenzyEndAt + GameBalance.INTERRUPT_AFTER_FRENZY_MS - candidate.warnAt)
                return
            }
            nextInterrupt++
            if (candidate.startAt > durationMs - GameBalance.INTERRUPT_END_MARGIN_MS) return
            interrupt = candidate
            currentInterrupt = candidate
            candidate.warned = true
            interruptsSeen++
            if (candidate.kind != InterruptKind.REFLEX) listener.onInterruptWarning(candidate.kind)
        }
        if (interrupt == null) return
        if (!interrupt.started && now >= interrupt.startAt) {
            interrupt.started = true
            when (interrupt.kind) {
                InterruptKind.FAKE_STOP -> {
                    goBoostUntil = now + GameBalance.FAKE_STOP_BONUS_MS
                    listener.onFakeStopReveal()
                    finishInterrupt(interrupt)
                    return
                }
                InterruptKind.STOP, InterruptKind.REFLEX -> {
                    state = GameState.STOP
                    listener.onInterruptStart(interrupt.kind)
                }
            }
        }
        if (interrupt.started && !interrupt.ended && now >= interrupt.endAt) completeInterrupt(interrupt, fromPause = false)
    }

    private fun completeInterrupt(interrupt: Interrupt, fromPause: Boolean) {
        if (state == GameState.STOP) state = GameState.PLAYING
        val clean = !interrupt.faulted
        if (clean) stopsSurvived++
        lastComboActiveMs = activeTimeMs
        if (interrupt.kind == InterruptKind.REFLEX && !fromPause) {
            reflexAwaiting = true
            reflexGoAt = matchTimeMs
            reflexDeadline = matchTimeMs + GameBalance.REFLEX_GO_WINDOW_MS
        }
        listener.onInterruptEnd(interrupt.kind, clean)
        // When pausing, a queued frenzy waits for the resume countdown to finish.
        finishInterrupt(interrupt, allowFrenzy = !fromPause)
    }

    private fun finishInterrupt(interrupt: Interrupt, allowFrenzy: Boolean = true) {
        interrupt.ended = true
        currentInterrupt = null
        if (allowFrenzy && frenzyQueued && canStartFrenzy()) startFrenzy()
        checkObjective()
    }

    private fun recomputeTimeWarnings() {
        nextTimeWarning = 0
        while (nextTimeWarning < GameBalance.TIME_WARNINGS_S.size &&
            matchTimeMs >= durationMs - GameBalance.TIME_WARNINGS_S[nextTimeWarning] * 1000L
        ) nextTimeWarning++
    }

    // ---- zones ------------------------------------------------------------------------------------

    private fun spawnZone() {
        val config = stage.zones ?: return
        val activeCount = zones.count { it.active }
        if (activeCount >= config.maxConcurrent) {
            nextZoneSpawnActive = activeTimeMs + ZONE_RETRY_MS
            return
        }
        nextZoneSpawnActive = activeTimeMs + rng.betweenLong(config.spawnMinMs, config.spawnMaxMs)
        val slot = zones.firstOrNull { !it.active } ?: return

        val weights = weightScratch
        System.arraycopy(config.weights, 0, weights, 0, weights.size)
        if (activeTimeMs - lastTimeZoneActive < TIME_ZONE_COOLDOWN_MS || timeBonusMs >= GameBalance.TIME_BONUS_CAP_MS) {
            weights[ZoneType.TIME.ordinal] = 0
        }
        weights[ZoneType.GOLDEN.ordinal] = 0
        var type = ZONE_TYPES[rng.weighted(weights).coerceAtLeast(0)]
        if (!goldenSpawned && rng.chance(config.goldenChance)) {
            type = ZoneType.GOLDEN
            goldenSpawned = true
        }
        if (type == ZoneType.TIME) lastTimeZoneActive = activeTimeMs

        val radius = when (type) {
            ZoneType.CRITICAL -> GameBalance.ZONE_CRITICAL_RADIUS
            ZoneType.GOLDEN -> GameBalance.ZONE_GOLDEN_RADIUS
            ZoneType.X5 -> GameBalance.ZONE_BASE_RADIUS * 0.85f
            else -> GameBalance.ZONE_BASE_RADIUS
        } * config.radiusScale * loadout.zoneRadiusScale

        val margin = ZONE_MARGIN + radius
        val minX = margin
        val maxX = 1f - margin
        val minY = margin
        val maxY = max(minY, arenaHeight - margin)
        var px = 0.5f
        var py = arenaHeight / 2f
        for (attempt in 0 until PLACEMENT_ATTEMPTS) {
            px = rng.range(minX, maxX)
            py = rng.range(minY, maxY)
            if (zones.none { it.active && dist(it.x(activeTimeMs), it.y(activeTimeMs), px, py) < it.radius(activeTimeMs) + radius + ZONE_SPACING }) break
        }

        slot.id = ++zoneIdCounter
        slot.type = type
        slot.active = true
        slot.hits = 0
        slot.baseRadius = radius
        slot.spawnAt = activeTimeMs
        slot.expireAt = activeTimeMs + when (type) {
            ZoneType.GOLDEN -> GameBalance.GOLDEN_LIFETIME_MS
            ZoneType.CRITICAL -> max(config.lifeMinMs * 2 / 3, 1_200L)
            else -> rng.betweenLong(config.lifeMinMs, config.lifeMaxMs)
        }
        slot.x0 = px
        slot.y0 = py
        slot.minX = minX
        slot.maxX = maxX
        slot.minY = minY
        slot.maxY = maxY
        slot.locked = type.isMultiplier && rng.chance(config.lockChance)
        slot.shrinks = type != ZoneType.GOLDEN && rng.chance(config.shrinkChance)
        val roll = rng.nextFloat()
        val movingBias = if (type == ZoneType.GOLDEN || type == ZoneType.CRITICAL) 0.5f else 0f
        slot.motion = when {
            roll < config.driftChance + movingBias -> ZoneMotion.DRIFT
            roll < config.driftChance + movingBias + config.orbitChance -> ZoneMotion.ORBIT
            roll < config.driftChance + movingBias + config.orbitChance + config.teleportChance -> ZoneMotion.TELEPORT
            else -> ZoneMotion.STATIC
        }
        if (config.driftChance + config.orbitChance + config.teleportChance == 0f && movingBias == 0f) slot.motion = ZoneMotion.STATIC
        when (slot.motion) {
            ZoneMotion.DRIFT -> {
                val angle = rng.range(0f, (2 * Math.PI).toFloat())
                val speed = config.speed * rng.range(0.8f, 1.2f) * (if (type == ZoneType.GOLDEN) 1.4f else 1f)
                slot.vx = kotlin.math.cos(angle) * speed
                slot.vy = kotlin.math.sin(angle) * speed
            }
            ZoneMotion.ORBIT -> {
                val r = min(0.16f, min(min(px - minX, maxX - px), min(py - minY, maxY - py)))
                slot.orbitR = max(0.04f, r)
                slot.x0 = px.coerceIn(minX + slot.orbitR, max(minX + slot.orbitR, maxX - slot.orbitR))
                slot.y0 = py.coerceIn(minY + slot.orbitR, max(minY + slot.orbitR, maxY - slot.orbitR))
                slot.omega = config.speed * rng.range(0.8f, 1.2f) / slot.orbitR * (if (rng.chance(0.5f)) 1f else -1f)
                slot.phase = rng.range(0f, (2 * Math.PI).toFloat())
            }
            ZoneMotion.TELEPORT -> {
                for (i in 0 until Zone.TELEPORT_SLOTS) {
                    slot.teleX[i] = if (i == 0) px else rng.range(minX, maxX)
                    slot.teleY[i] = if (i == 0) py else rng.range(minY, maxY)
                }
            }
            ZoneMotion.STATIC -> Unit
        }
        listener.onZoneSpawn(slot)
    }

    // ---- objective & end --------------------------------------------------------------------

    /** Current progress towards the stage objective (SURVIVAL: errors made). */
    val objectiveProgress: Long
        get() = when (stage.type) {
            StageType.SPEED -> taps
            StageType.SCORE, StageType.BOSS -> score
            StageType.COMBO -> maxCombo.toLong()
            StageType.PRECISION -> zoneHits.toLong()
            StageType.SURVIVAL -> stopErrors.toLong()
            StageType.PERFECT -> perfects.toLong()
            StageType.FRENZY -> frenzies.toLong()
        }

    val objectiveTarget: Long
        get() = when (stage.type) {
            StageType.BOSS -> stage.scoreTarget.toLong()
            else -> stage.target.toLong()
        }

    /** Whether the objective is currently satisfied (SURVIVAL/BOSS also need to reach the end). */
    val objectiveMet: Boolean
        get() = when (stage.type) {
            StageType.SURVIVAL -> stopErrors <= stage.target && (maxLives == 0 || lives > 0)
            StageType.BOSS -> score >= stage.scoreTarget && (maxLives == 0 || lives > 0)
            else -> objectiveProgress >= objectiveTarget
        }

    private fun checkObjective() {
        if (objectiveAnnounced || stage.type == StageType.SURVIVAL) return
        if (objectiveMet) {
            objectiveAnnounced = true
            listener.onObjectiveComplete()
        }
    }

    private fun finish(reason: FailReason) {
        if (state == GameState.FINISHED) return
        val interrupt = currentInterrupt
        if (interrupt != null && interrupt.started && !interrupt.ended) {
            // Match ended during a STOP (spec §65): a clean hold still counts as survived.
            interrupt.ended = true
            if (!interrupt.faulted) stopsSurvived++
            currentInterrupt = null
        }
        state = GameState.FINISHED
        val won = reason == FailReason.NONE && objectiveMet
        val failReason = when {
            won -> FailReason.NONE
            reason == FailReason.NONE -> FailReason.OBJECTIVE
            else -> reason
        }
        val stars = if (!won) 0 else 1 + (if (score >= stage.star2Score) 1 else 0) + (if (score >= stage.star3Score) 1 else 0)
        val played = matchTimeMs
        val r = MatchResult(
            stageNumber = stage.number,
            stageType = stage.type,
            isBoss = stage.isBoss,
            won = won,
            failReason = failReason,
            stars = stars,
            score = score,
            taps = taps,
            touches = touches,
            tapValue = loadout.tapValue,
            maxCombo = maxCombo,
            maxTps = tps.maxTps,
            avgTps = if (played > 0) touches * 1000f / played else 0f,
            perfects = perfects,
            zoneHits = zoneHits,
            interrupts = interruptsSeen,
            stopsSurvived = stopsSurvived,
            stopErrors = stopErrors,
            frenzies = frenzies,
            megaFrenzies = megaFrenzies,
            coinsCollected = coins,
            goldenHits = goldenHits,
            reflexBestMs = reflexBestMs,
            timeBonusMs = timeBonusMs,
            playedMs = played,
            livesLeft = lives,
            objectiveProgress = objectiveProgress,
            objectiveTarget = objectiveTarget,
            droppedTaps = droppedTaps,
            badTimestamps = badTimestamps,
        )
        result = r
        listener.onFinished(r)
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        const val MAX_ZONES = 6
        private val ZONE_TYPES = ZoneType.values()
        private const val FIRST_ZONE_DELAY_MS = 1_500L
        private const val ZONE_RETRY_MS = 400L
        private const val TIME_ZONE_COOLDOWN_MS = 15_000L
        private const val ZONE_MARGIN = 0.03f
        private const val ZONE_SPACING = 0.04f
        private const val PLACEMENT_ATTEMPTS = 10
        private const val SHIELD_MIN_COMBO = 10
        private const val REARM_DELAY_MS = 1_000L
        private const val FRENZY_MIN_TIME_LEFT_MS = 1_000L
        private const val MAX_BACKWARDS_MS = 1_000L
        private const val MAX_STEPS_PER_ADVANCE = 10_000
    }
}
