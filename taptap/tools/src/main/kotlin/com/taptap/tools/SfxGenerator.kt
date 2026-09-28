package com.taptap.tools

import com.taptap.game.core.audio.Sfx
import com.taptap.game.core.audio.SfxBank
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders every [Sfx] to a 16-bit mono PCM WAV file in the directory given as first argument.
 * Invoked by the app build (task :app:generateSfx) so the APK always ships current sounds.
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: error("usage: SfxGenerator <outputDir>"))
    out.mkdirs()
    var total = 0L
    for (sfx in Sfx.values()) {
        val samples = SfxBank.render(sfx)
        val file = File(out, sfx.file + ".wav")
        Wav.write(file, samples, SfxBank.SAMPLE_RATE)
        total += file.length()
    }
    println("Generated ${Sfx.values().size} sound effects (${total / 1024} KiB) in $out")
}

object Wav {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes)
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32_767f).toInt().toShort())
        FileOutputStream(file).use {
            it.write(header.array())
            it.write(data.array())
        }
    }
}
