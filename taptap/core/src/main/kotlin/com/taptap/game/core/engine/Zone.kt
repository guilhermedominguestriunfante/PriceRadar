package com.taptap.game.core.engine

import com.taptap.game.core.util.clamp
import com.taptap.game.core.util.lerp
import com.taptap.game.core.util.reflect
import kotlin.math.cos
import kotlin.math.sin

/**
 * A hot zone. Instances are pooled by the session (no allocation while playing). Position and
 * radius are pure functions of the session's *active time*, so a tap is always judged against
 * where the zone really was at the tap's timestamp, and zones freeze during STOP.
 * Coordinates are arena units: x in [0, 1] (arena width), y in [0, arena height / width].
 */
class Zone internal constructor(internal val slot: Int) {
    var id: Int = 0; internal set
    var active: Boolean = false; internal set
    var type: ZoneType = ZoneType.X2; internal set
    var motion: ZoneMotion = ZoneMotion.STATIC; internal set
    /** Mandatory zone: while it exists, taps outside every zone are misses. */
    var locked: Boolean = false; internal set
    var shrinks: Boolean = false; internal set
    var spawnAt: Long = 0L; internal set
    var expireAt: Long = 0L; internal set
    var hits: Int = 0; internal set
    var baseRadius: Float = 0f; internal set

    internal var x0 = 0f
    internal var y0 = 0f
    internal var vx = 0f
    internal var vy = 0f
    internal var minX = 0f
    internal var maxX = 1f
    internal var minY = 0f
    internal var maxY = 1f
    internal var orbitR = 0f
    internal var omega = 0f
    internal var phase = 0f
    internal val teleX = FloatArray(TELEPORT_SLOTS)
    internal val teleY = FloatArray(TELEPORT_SLOTS)

    val lifeMs: Long get() = expireAt - spawnAt

    fun ageSeconds(t: Long): Float = (t - spawnAt).coerceAtLeast(0L) / 1000f

    fun lifeFraction(t: Long): Float =
        if (expireAt <= spawnAt) 1f else clamp((t - spawnAt).toFloat() / (expireAt - spawnAt), 0f, 1f)

    fun remainingMs(t: Long): Long = (expireAt - t).coerceAtLeast(0L)

    fun x(t: Long): Float = when (motion) {
        ZoneMotion.STATIC -> x0
        ZoneMotion.DRIFT -> reflect(x0 + vx * ageSeconds(t), minX, maxX)
        ZoneMotion.ORBIT -> x0 + orbitR * cos(phase + omega * ageSeconds(t))
        ZoneMotion.TELEPORT -> teleX[teleportIndex(t)]
    }

    fun y(t: Long): Float = when (motion) {
        ZoneMotion.STATIC -> y0
        ZoneMotion.DRIFT -> reflect(y0 + vy * ageSeconds(t), minY, maxY)
        ZoneMotion.ORBIT -> y0 + orbitR * sin(phase + omega * ageSeconds(t))
        ZoneMotion.TELEPORT -> teleY[teleportIndex(t)]
    }

    fun radius(t: Long): Float =
        if (shrinks) lerp(baseRadius, baseRadius * SHRINK_TO, lifeFraction(t)) else baseRadius

    /** For TELEPORT zones: fraction of the current position's dwell time elapsed (0..1). */
    fun teleportPhase(t: Long): Float {
        if (motion != ZoneMotion.TELEPORT) return 0f
        val age = (t - spawnAt).coerceAtLeast(0L)
        return (age % GameBalance.TELEPORT_PERIOD_MS).toFloat() / GameBalance.TELEPORT_PERIOD_MS
    }

    private fun teleportIndex(t: Long): Int {
        val age = (t - spawnAt).coerceAtLeast(0L)
        return (age / GameBalance.TELEPORT_PERIOD_MS).toInt().coerceIn(0, TELEPORT_SLOTS - 1)
    }

    companion object {
        const val TELEPORT_SLOTS = 8
        const val SHRINK_TO = 0.55f
    }
}
