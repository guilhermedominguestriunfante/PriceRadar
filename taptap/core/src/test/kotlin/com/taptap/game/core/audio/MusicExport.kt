package com.taptap.game.core.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes music excerpts to WAV for manual listening/inspection (-Dcalibrate=true). */
class MusicExport {
    @org.junit.jupiter.api.Test
    fun export() {
        if (System.getProperty("calibrate") != "true") return
        val dir = File(System.getProperty("java.io.tmpdir"), "taptap-music").apply { mkdirs() }
        fun write(name: String, setup: (MusicEngine) -> Unit, seconds: Int) {
            val e = MusicEngine(44_100)
            setup(e)
            val frames = 44_100 * seconds
            val pcm = ShortArray(frames * 2)
            e.render(pcm, frames)
            val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
            bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(44_100).putInt(44_100 * 4).putShort(4).putShort(16)
            bb.put("data".toByteArray()).putInt(pcm.size * 2)
            for (s in pcm) bb.putShort(s)
            FileOutputStream(File(dir, "$name.wav")).use { it.write(bb.array()) }
        }
        write("menu", { it.mode = MusicMode.MENU }, 12)
        write("game_i1", { it.mode = MusicMode.GAME; it.intensity = 1 }, 12)
        write("game_i4", { it.mode = MusicMode.GAME; it.intensity = 4 }, 12)
        write("frenzy", { it.mode = MusicMode.GAME; it.intensity = 4; it.frenzy = true }, 12)
        write("boss", { it.mode = MusicMode.BOSS; it.intensity = 3 }, 12)
    }
}
