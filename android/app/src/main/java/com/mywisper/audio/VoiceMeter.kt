package com.mywisper.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Measures loudness per audio frame and adds up how long someone was actually talking.
 * A simple energy detector (the desktop uses the Silero model): a frame is "voice" when it is
 * clearly louder than the background noise, which is tracked as it changes.
 */
class VoiceMeter(private val frameMs: Int = 30) {
    var voiceMs = 0
        private set
    var peak = 0f
        private set
    /** The last frame was speech / clearly quiet (live typing splits sentences on these). */
    var lastWasVoice = false
        private set
    var lastWasQuiet = true
        private set
    private var noiseDb = START_NOISE_DB

    fun reset() {
        voiceMs = 0; peak = 0f; noiseDb = START_NOISE_DB
    }

    /** Feeds one frame; returns a 0..1 level for the on-screen meter. */
    fun frame(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        var framePeak = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            sum += (v * v).toDouble()
            framePeak = max(framePeak, abs(v))
        }
        val rms = sqrt(sum / count) / 32768.0
        val db = 20 * log10(max(rms, 1e-6))
        peak = max(peak, framePeak / 32768f)

        lastWasVoice = db > max(noiseDb + VOICE_ABOVE_NOISE_DB, MIN_VOICE_DB)
        // Clearly quiet = well below speech level (used to find the pause at the end of a sentence).
        lastWasQuiet = db < max(noiseDb + QUIET_ABOVE_NOISE_DB, MIN_VOICE_DB - 3)
        if (lastWasVoice) voiceMs += frameMs
        // Noise floor: drops quickly to quieter frames, creeps up slowly (so speech doesn't become "noise").
        noiseDb = if (db < noiseDb) noiseDb * 0.6 + db * 0.4 else noiseDb + (db - noiseDb) * 0.01

        return ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val START_NOISE_DB = -55.0
        private const val VOICE_ABOVE_NOISE_DB = 12.0
        private const val QUIET_ABOVE_NOISE_DB = 6.0
        private const val MIN_VOICE_DB = -45.0 // quieter than this is never speech
        private const val METER_MIN_DB = -60.0
        private const val METER_MAX_DB = -12.0
    }
}
