package com.taptap.game

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.taptap.game.core.Hello

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val names = listOf("TAP", "TAP").map { it.lowercase() }.sortedBy { it.length }
        setContentView(TextView(this).apply { text = Hello.greet(names.joinToString(" ")) })
    }
}
