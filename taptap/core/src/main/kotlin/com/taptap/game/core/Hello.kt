package com.taptap.game.core

object Hello {
    fun greet(name: String): String = "Hello $name".also { check(it.isNotEmpty()) }
}
