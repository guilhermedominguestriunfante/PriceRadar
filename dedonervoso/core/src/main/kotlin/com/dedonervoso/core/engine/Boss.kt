package com.dedonervoso.core.engine

import com.dedonervoso.core.util.Rng
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Attacks a boss can make. Each one is telegraphed for [GameBalance.BOSS_ATTACK_WARNING_MS]
 * and lasts [durationMs] of active time (0 = instant). The ROAR is not here: a boss's roar is the
 * stage's regular STOP, presented by the UI as the boss roaring.
 */
enum class BossAttack(val durationMs: Long) {
    /** Invulnerable: taps on the boss are blocked (no points), taps elsewhere still count. */
    SHIELD(2_000L),

    /** Dashes across the arena, much faster than usual. */
    CHARGE(1_400L),

    /** The screen goes dark for a while (the boss's eyes still glow). Presentation only. */
    BLACKOUT(2_500L),

    /** Steals [GameBalance.BOSS_CLOCK_MS] from the match clock. */
    CLOCK(0L),

    /** Jumps to a new spot [GameBalance.BOSS_TELEPORTS] times. */
    TELEPORT(1_200L),
}

/**
 * The campaign bosses, one every 10 stages in this order; from stage 60 they come back stronger
 * (see [Boss.tier]). Each has the attacks it favours; the STOPs of the stage are its roars.
 */
enum class BossKind(val attacks: List<BossAttack>) {
    FURIOSO(listOf(BossAttack.CHARGE)),
    PUNHO(listOf(BossAttack.SHIELD, BossAttack.CHARGE)),
    CRONOMETRO(listOf(BossAttack.CLOCK, BossAttack.SHIELD)),
    GLITCH(listOf(BossAttack.TELEPORT, BossAttack.BLACKOUT)),
    REI(listOf(BossAttack.CHARGE, BossAttack.SHIELD, BossAttack.CLOCK, BossAttack.TELEPORT, BossAttack.BLACKOUT)),
    ;

    companion object {
        /** Boss of campaign stage [stage] (a multiple of 10), and how many times the cycle has come around. */
        fun of(stage: Int): BossKind = values()[((stage / 10) - 1).coerceAtLeast(0) % values().size]
        fun tier(stage: Int): Int = ((stage / 10) - 1).coerceAtLeast(0) / values().size
    }
}

/** What a boss did, reported by [Boss.due] one step at a time. */
enum class BossEvent { WARNING, ATTACK_START, ATTACK_END, TELEPORT }

/**
 * The boss of a boss stage (spec: "barra de vida"). It wanders the arena and flees from the
 * finger, and attacks on a seeded schedule that speeds up as it gets hurt (its [rage]).
 * Its health is the stage's score target minus the player's score: points are damage, and a
 * STOP fault (which costs points) heals it.
 *
 * Positions are arena units like zones; every time here is the session's *active* time, so the
 * boss freezes during STOP holds. Movement is linear between events, so [step] is exact for any
 * step length: the session splits its steps at [nextEventAt].
 */
class Boss internal constructor(
    val kind: BossKind,
    /** 0 on the first cycle (stages 10–50), 1 from stage 60, and so on: faster and angrier. */
    val tier: Int,
    private val arenaHeight: Float,
    private val rng: Rng,
) {
    val radius: Float = GameBalance.BOSS_RADIUS
    var x: Float = 0.5f
        private set
    var y: Float = arenaHeight * 0.38f
        private set
    private var targetX = x
    private var targetY = y
    private var retargetAt = 0L
    private var fleeReadyAt = 0L

    /** 0 calm, 1 at half health or less, 2 at a fifth or less. Never goes back down. */
    var rage: Int = 0
        private set

    /** The attack telegraphed right now (before it starts), if any. */
    var warning: BossAttack? = null
        private set

    /** The attack running right now, if any. */
    var attack: BossAttack? = null
        private set

    /** The most recent attack (still set after it ends). */
    var lastAttack: BossAttack? = null
        private set

    private var nextWarnAt = GameBalance.BOSS_FIRST_ATTACK_MS
    private var startAt = Long.MAX_VALUE
    private var endAt = Long.MAX_VALUE
    private var teleportsLeft = 0
    private var nextTeleportAt = Long.MAX_VALUE

    var hits: Int = 0
        private set
    var attacksMade: Int = 0
        private set

    val shielded: Boolean get() = attack == BossAttack.SHIELD
    val blackout: Boolean get() = attack == BossAttack.BLACKOUT
    val charging: Boolean get() = attack == BossAttack.CHARGE

    private val minX get() = radius + MARGIN
    private val maxX get() = 1f - radius - MARGIN
    private val minY get() = radius + MARGIN
    private val maxY get() = max(minY, arenaHeight - radius - MARGIN)

    /** Arena widths per second right now. */
    val speed: Float
        get() {
            val base = GameBalance.BOSS_SPEED * RAGE_SPEED[rage] * min(1.5f, 1f + 0.1f * tier)
            return if (charging) base * GameBalance.BOSS_CHARGE_SPEED else base
        }

    internal fun start(now: Long) {
        pickTarget(now, minDistance = 0f)
    }

    /** Earliest active time at which something scheduled happens. */
    internal fun nextEventAt(): Long {
        var next = retargetAt
        next = min(next, nextTeleportAt)
        next = when {
            attack != null -> min(next, endAt)
            warning != null -> min(next, startAt)
            else -> min(next, nextWarnAt)
        }
        return next
    }

    /** Moves towards the current target for [dtMs] of active time. */
    internal fun step(dtMs: Long) {
        if (dtMs <= 0) return
        val dx = targetX - x
        val dy = targetY - y
        val d = sqrt(dx * dx + dy * dy)
        if (d <= 1e-5f) return
        val move = speed * dtMs / 1000f
        if (move >= d) {
            x = targetX
            y = targetY
        } else {
            x += dx / d * move
            y += dy / d * move
        }
    }

    /**
     * The next thing due at [now], applying it, or null when nothing is. A telegraph only starts
     * when [canAttack] (not during a STOP or its warning); otherwise it waits a little.
     */
    internal fun due(now: Long, canAttack: Boolean): BossEvent? {
        if (nextTeleportAt <= now) {
            teleport(now)
            return BossEvent.TELEPORT
        }
        val running = attack
        if (running != null) {
            if (endAt <= now) {
                attack = null
                endAt = Long.MAX_VALUE
                nextTeleportAt = Long.MAX_VALUE
                teleportsLeft = 0
                scheduleNext(now)
                if (running == BossAttack.CHARGE) pickTarget(now, minDistance = 0.2f)
                return BossEvent.ATTACK_END
            }
        } else if (warning != null) {
            if (startAt <= now) {
                val a = warning!!
                warning = null
                attack = a
                lastAttack = a
                attacksMade++
                startAt = Long.MAX_VALUE
                endAt = now + a.durationMs
                when (a) {
                    BossAttack.CHARGE -> chargeTarget(now)
                    BossAttack.TELEPORT -> {
                        teleportsLeft = GameBalance.BOSS_TELEPORTS
                        nextTeleportAt = now
                    }
                    else -> Unit
                }
                return BossEvent.ATTACK_START
            }
        } else if (nextWarnAt <= now) {
            if (!canAttack) {
                nextWarnAt = now + ATTACK_RETRY_MS
                return null
            }
            warning = kind.attacks[rng.nextInt(kind.attacks.size)]
            startAt = now + GameBalance.BOSS_ATTACK_WARNING_MS
            nextWarnAt = Long.MAX_VALUE
            return BossEvent.WARNING
        }
        if (retargetAt <= now) pickTarget(now, minDistance = 0.18f)
        return null
    }

    /** A tap landed on the boss: it flinches and, now and then, runs away from the finger. */
    internal fun onHit(now: Long) {
        hits++
        if (now >= fleeReadyAt && !charging) {
            fleeReadyAt = now + GameBalance.BOSS_FLEE_COOLDOWN_MS
            pickTarget(now, minDistance = 0.32f)
        }
    }

    /** Raises the rage to match the remaining health fraction; true when it went up. */
    internal fun updateRage(healthFraction: Float): Boolean {
        val r = when {
            healthFraction <= GameBalance.BOSS_RAGE_2 -> 2
            healthFraction <= GameBalance.BOSS_RAGE_1 -> 1
            else -> 0
        }
        if (r <= rage) return false
        rage = r
        return true
    }

    private fun scheduleNext(now: Long) {
        val gap = rng.betweenLong(GameBalance.BOSS_ATTACK_GAP_MIN_MS, GameBalance.BOSS_ATTACK_GAP_MAX_MS)
        val scale = RAGE_GAP[rage] * max(0.6f, 1f - 0.06f * tier)
        nextWarnAt = now + (gap * scale).toLong()
    }

    private fun pickTarget(now: Long, minDistance: Float) {
        var tx = x
        var ty = y
        for (attempt in 0 until 8) {
            tx = rng.range(minX, maxX)
            ty = rng.range(minY, maxY)
            val dx = tx - x
            val dy = ty - y
            if (dx * dx + dy * dy >= minDistance * minDistance) break
        }
        targetX = tx
        targetY = ty
        retargetAt = now + rng.betweenLong(GameBalance.BOSS_RETARGET_MIN_MS, GameBalance.BOSS_RETARGET_MAX_MS)
    }

    /** A charge crosses to the far side of the arena. */
    private fun chargeTarget(now: Long) {
        targetX = if (x < 0.5f) maxX else minX
        targetY = rng.range(minY, maxY)
        retargetAt = now + BossAttack.CHARGE.durationMs
    }

    private fun teleport(now: Long) {
        x = rng.range(minX, maxX)
        y = rng.range(minY, maxY)
        targetX = x
        targetY = y
        teleportsLeft--
        nextTeleportAt = if (teleportsLeft > 0) now + BossAttack.TELEPORT.durationMs / GameBalance.BOSS_TELEPORTS else Long.MAX_VALUE
        retargetAt = now + GameBalance.BOSS_RETARGET_MIN_MS
    }

    private companion object {
        const val MARGIN = 0.02f
        const val ATTACK_RETRY_MS = 1_200L
        val RAGE_SPEED = floatArrayOf(1f, 1.35f, 1.7f)
        val RAGE_GAP = floatArrayOf(1f, 0.75f, 0.55f)
    }
}
