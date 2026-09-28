package com.taptap.game.ui.fx

import kotlin.math.sin

/** Trauma-based camera shake (spec §55): only for big moments, never on normal taps. */
class Shake(private val dp: Float) {
    private var trauma = 0f
    private var t = 0f
    var enabled = true
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    fun add(amount: Float) {
        if (enabled) trauma = minOf(1f, trauma + amount)
    }

    fun update(dt: Float) {
        t += dt
        trauma = maxOf(0f, trauma - dt * 1.6f)
        val s = trauma * trauma * MAX_DP * dp
        offsetX = s * (sin(t * 47f) + 0.5f * sin(t * 83f)) / 1.5f
        offsetY = s * (sin(t * 53f + 1.3f) + 0.5f * sin(t * 71f)) / 1.5f
    }

    companion object {
        private const val MAX_DP = 9f
    }
}
