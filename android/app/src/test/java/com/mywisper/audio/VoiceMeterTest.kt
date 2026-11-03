package com.mywisper.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class VoiceMeterTest {
    private val frame = 480 // 30 ms at 16 kHz

    private fun feed(meter: VoiceMeter, seconds: Double, sample: (Int) -> Double) {
        var n = 0
        repeat((seconds * 1000 / 30).toInt()) {
            val s = ShortArray(frame) { (sample(n++) * 32767).toInt().toShort() }
            meter.frame(s, frame)
        }
    }

    @Test fun silenceAndRoomNoiseAreNotVoice() {
        val m = VoiceMeter()
        feed(m, 2.0) { 0.0 }
        val rnd = Random(1)
        feed(m, 2.0) { (rnd.nextDouble() - 0.5) * 0.004 } // about -60 dBFS hiss
        assertEquals(0, m.voiceMs)
    }

    @Test fun speechLevelSoundCounts() {
        val m = VoiceMeter()
        val rnd = Random(2)
        feed(m, 0.5) { (rnd.nextDouble() - 0.5) * 0.004 }
        feed(m, 1.5) { 0.3 * sin(2 * PI * 220 * it / 16_000.0) } // loud tone, like talking
        feed(m, 0.5) { (rnd.nextDouble() - 0.5) * 0.004 }
        assertTrue("voiceMs=${m.voiceMs}", m.voiceMs in 1200..1600)
        assertTrue(m.peak > 0.25f)
    }
}
