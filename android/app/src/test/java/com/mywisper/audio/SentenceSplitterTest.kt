package com.mywisper.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SentenceSplitterTest {
    private val rnd = Random(3)

    /** Runs audio through VoiceMeter + SentenceSplitter, like Recorder does. */
    private fun split(vararg parts: Pair<Double, Boolean>, flush: Boolean = false): List<Recording> {
        val out = mutableListOf<Recording>()
        val splitter = SentenceSplitter { out += it }
        val meter = VoiceMeter(Recorder.FRAME_MS)
        var n = 0
        for ((seconds, speech) in parts) {
            repeat((seconds * 1000 / Recorder.FRAME_MS).toInt()) {
                val s = ShortArray(Recorder.FRAME_SAMPLES) {
                    val v = if (speech) 0.3 * sin(2 * PI * 220 * n / 16_000.0) else (rnd.nextDouble() - 0.5) * 0.004
                    n++
                    (v * 32767).toInt().toShort()
                }
                meter.frame(s, s.size)
                val bytes = ByteArray(s.size * 2) { i -> if (i % 2 == 0) (s[i / 2].toInt() and 0xff).toByte() else (s[i / 2].toInt() shr 8).toByte() }
                splitter.feed(Frame(bytes, meter.lastWasVoice, meter.lastWasQuiet))
            }
        }
        if (flush) splitter.flush()
        return out
    }

    @Test fun cutsAtPauses() {
        val s = split(0.5 to false, 1.5 to true, 1.0 to false, 1.2 to true, 1.0 to false, 0.9 to true, 1.0 to false)
        assertEquals(3, s.size)
        assertTrue("first ${s[0].voiceMs}", s[0].voiceMs in 1300..1600)
    }

    @Test fun shortPauseKeepsOneSentence() {
        assertEquals(1, split(0.3 to false, 1.0 to true, 0.4 to false, 1.0 to true, 1.0 to false).size)
    }

    @Test fun flushSendsTheLastSentence() {
        assertEquals(0, split(0.3 to false, 1.0 to true).size)
        assertEquals(1, split(0.3 to false, 1.0 to true, flush = true).size)
    }

    @Test fun ignoresCoughsAndSilence() {
        assertEquals(0, split(2.0 to false, 0.15 to true, 2.0 to false, flush = true).size)
    }
}
