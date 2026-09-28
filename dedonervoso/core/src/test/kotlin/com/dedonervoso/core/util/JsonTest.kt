package com.dedonervoso.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test
    fun roundTripsNestedValues() {
        val value = linkedMapOf(
            "s" to "a \"quoted\" \\ line\nnew ç ã 🎮", "n" to 42L, "d" to 1.5, "b" to true, "z" to null,
            "l" to listOf(1L, 2L, linkedMapOf("x" to "y")),
        )
        val text = Json.write(value)
        assertEquals(value, Json.parse(text))
    }

    @Test
    fun rejectsMalformed() {
        assertFailsWith<JsonException> { Json.parse("{\"a\":") }
        assertFailsWith<JsonException> { Json.parse("{\"a\":1}x") }
        assertFailsWith<JsonException> { Json.parse("[1,2") }
    }

    @Test
    fun rngIsDeterministicAndBounded() {
        val a = Rng(99)
        val b = Rng(99)
        repeat(1000) {
            val x = a.nextInt(7)
            assertEquals(x, b.nextInt(7))
            check(x in 0..6)
            val f = a.nextFloat()
            b.nextFloat()
            check(f >= 0f && f < 1f)
        }
    }

    @Test
    fun reflectBouncesBetweenBounds() {
        assertEquals(0.3f, reflect(0.3f, 0f, 1f), 1e-6f)
        assertEquals(0.8f, reflect(1.2f, 0f, 1f), 1e-6f)
        assertEquals(0.2f, reflect(2.2f, 0f, 1f), 1e-6f)
        assertEquals(0.2f, reflect(-0.2f, 0f, 1f), 1e-6f)
    }
}
