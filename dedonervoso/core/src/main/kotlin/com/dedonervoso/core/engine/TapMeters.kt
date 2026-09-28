package com.dedonervoso.core.engine

/**
 * Taps-per-second over a sliding window (spec §25), using event timestamps. Allocation free.
 */
class TpsMeter(capacity: Int = 96) {
    private val times = LongArray(capacity)
    private var head = 0
    private var size = 0

    /** Best rate reached during the match (only counted with enough samples). */
    var maxTps = 0f
        private set

    fun reset() {
        head = 0
        size = 0
        maxTps = 0f
    }

    fun record(t: Long) {
        times[head] = t
        head = (head + 1) % times.size
        if (size < times.size) size++
        val n = countInWindow(t)
        if (n >= GameBalance.TPS_MIN_SAMPLES) {
            val rate = minOf(GameBalance.MAX_HUMAN_TPS, n * 1000f / GameBalance.TPS_WINDOW_MS)
            if (rate > maxTps) maxTps = rate
        }
    }

    fun countInWindow(now: Long, windowMs: Long = GameBalance.TPS_WINDOW_MS): Int {
        var count = 0
        var idx = head
        for (i in 0 until size) {
            idx = if (idx == 0) times.size - 1 else idx - 1
            val t = times[idx]
            if (now - t < windowMs && t <= now) count++ else if (now - t >= windowMs) break
        }
        return count
    }

    fun rate(now: Long): Float = countInWindow(now) * 1000f / GameBalance.TPS_WINDOW_MS
}

/**
 * Token bucket limiting sustained tap rate to what a human can physically do (spec §37):
 * bursts of [GameBalance.TAP_BUCKET_CAPACITY] taps, then one token per [GameBalance.TAP_REFILL_MS].
 */
class TapLimiter {
    private var tokens = GameBalance.TAP_BUCKET_CAPACITY.toFloat()
    private var lastT = Long.MIN_VALUE

    fun reset() {
        tokens = GameBalance.TAP_BUCKET_CAPACITY.toFloat()
        lastT = Long.MIN_VALUE
    }

    fun tryConsume(t: Long): Boolean {
        if (lastT != Long.MIN_VALUE && t > lastT) {
            tokens = minOf(
                GameBalance.TAP_BUCKET_CAPACITY.toFloat(),
                tokens + (t - lastT).toFloat() / GameBalance.TAP_REFILL_MS,
            )
        }
        if (t > lastT) lastT = t
        if (tokens < 1f) return false
        tokens -= 1f
        return true
    }
}
