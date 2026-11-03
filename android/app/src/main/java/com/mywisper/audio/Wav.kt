package com.mywisper.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wraps raw 16-bit little-endian PCM in a WAV header (Groq Whisper accepts .wav). */
object Wav {
    fun fromPcm16(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val byteRate = sampleRate * channels * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1) /* PCM */; putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return header.array() + pcm
    }

    /** WAV file → 16-bit samples (finds the "data" chunk, so 44- or 46-byte headers both work). Mono 16 kHz expected. */
    fun readPcm16(wav: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, wav.size - pos - 8) / 2
                return ShortArray(n) { b.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }
}
