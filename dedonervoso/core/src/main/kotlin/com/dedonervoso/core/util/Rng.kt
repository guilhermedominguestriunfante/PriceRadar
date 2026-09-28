package com.dedonervoso.core.util

/**
 * Small, fast, seedable PRNG (SplitMix64). Deterministic on every platform and allocation free,
 * so gameplay generated from a seed (stages, daily challenges, event plans) is reproducible.
 */
class Rng(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** Uniform int in `[0, bound)`. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Uniform int in `[from, until)`. */
    fun nextInt(from: Int, until: Int): Int = if (until <= from) from else from + nextInt(until - from)

    /** Uniform int in `[min, max]` (inclusive). */
    fun between(min: Int, max: Int): Int = if (max <= min) min else min + nextInt(max - min + 1)

    fun betweenLong(min: Long, max: Long): Long =
        if (max <= min) min else min + ((nextLong() ushr 1) % (max - min + 1))

    /** Uniform float in `[0, 1)`. */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()

    fun range(min: Float, max: Float): Float = min + (max - min) * nextFloat()

    fun chance(probability: Float): Boolean = nextFloat() < probability

    /** Index chosen proportionally to [weights]; -1 when all weights are zero. */
    fun weighted(weights: IntArray): Int {
        var total = 0
        for (w in weights) if (w > 0) total += w
        if (total == 0) return -1
        var r = nextInt(total)
        for (i in weights.indices) {
            val w = weights[i]
            if (w <= 0) continue
            if (r < w) return i
            r -= w
        }
        return weights.lastIndex
    }

    companion object {
        private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15uL.toLong()
        private val MIX_1 = 0xBF58476D1CE4E5B9uL.toLong()
        private val MIX_2 = 0x94D049BB133111EBuL.toLong()

        /** Derives an independent stream seed from a base seed and a salt. */
        fun mix(seed: Long, salt: Long): Long {
            var z = seed + salt * GOLDEN_GAMMA
            z = (z xor (z ushr 30)) * MIX_1
            z = (z xor (z ushr 27)) * MIX_2
            return z xor (z ushr 31)
        }
    }
}
