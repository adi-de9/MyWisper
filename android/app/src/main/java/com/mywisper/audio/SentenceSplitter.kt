package com.mywisper.audio

import java.io.ByteArrayOutputStream

/**
 * Live typing: cuts the recording into sentences at natural pauses (same rules as desktop pill.js LIVE).
 * A sentence starts at the first voice frame (with ~1 s of audio before it, so soft starts aren't lost)
 * and ends after 700 ms of real quiet or 25 s. Bits with too little speech (coughs, clicks) are dropped.
 * feed() is called for every frame; onSentence gets each finished sentence as a Recording.
 */
class SentenceSplitter(private val onSentence: (Recording) -> Unit) {
    private val preroll = ArrayDeque<ByteArray>()
    private val current = ByteArrayOutputStream()
    private var speaking = false
    private var speechMs = 0
    private var quietMs = 0
    private var lengthMs = 0

    @Synchronized
    fun feed(f: Frame) {
        if (!speaking) {
            if (!f.voice) {
                preroll.addLast(f.pcm)
                if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                return
            }
            speaking = true
            current.reset()
            preroll.forEach { current.write(it) }
            lengthMs = preroll.size * FRAME_MS
            preroll.clear()
            speechMs = 0; quietMs = 0
        }
        current.write(f.pcm)
        lengthMs += FRAME_MS
        if (f.voice) speechMs += FRAME_MS
        quietMs = if (f.quiet) quietMs + FRAME_MS else 0
        if (quietMs >= PAUSE_MS || lengthMs >= MAX_SENTENCE_MS) emit()
    }

    /** Sends whatever is being said right now (when the user stops). */
    @Synchronized
    fun flush() {
        if (speaking) emit()
        preroll.clear()
    }

    private fun emit() {
        speaking = false
        if (speechMs < MIN_SPEECH_MS) return
        var bytes = current.toByteArray()
        // Keep only a little quiet after the last word.
        val trim = ((quietMs - KEEP_TAIL_MS).coerceAtLeast(0) / FRAME_MS) * FRAME_BYTES
        if (trim in 1 until bytes.size) bytes = bytes.copyOf(bytes.size - trim)
        onSentence(Recording(bytes, speechMs, 0f))
    }

    companion object {
        const val FRAME_MS = Recorder.FRAME_MS
        private const val FRAME_BYTES = Recorder.FRAME_SAMPLES * 2
        const val PAUSE_MS = 700
        const val PREROLL_FRAMES = 33       // ~1 s
        const val MIN_SPEECH_MS = 250
        const val MAX_SENTENCE_MS = 25_000
        const val KEEP_TAIL_MS = 250
    }
}
