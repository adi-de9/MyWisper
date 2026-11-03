package com.mywisper.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.mywisper.audio.AacEncoder
import com.mywisper.audio.Recorder
import com.mywisper.audio.Recording
import java.time.Instant

/** Recording → Whisper → cleanup. The phone version of desktop main.js processAudio(). */
class Dictation(context: Context, private val groq: GroqClient = GroqClient()) {
    private val app = context.applicationContext

    /** Call when recording starts: connects to Groq while the user speaks. */
    fun prepare() = groq.warmUp()

    sealed interface Result {
        /** Text to insert. note = extra status, e.g. "cleanup failed". */
        data class Text(val text: String, val ms: Long, val note: String? = null) : Result
        /** Nothing to insert (silence, too short). */
        data class Empty(val reason: String) : Result
        data class Failed(val message: String) : Result
    }

    /** appName = where the text goes (shown in the shared history). */
    /** live = one sentence of live typing (stored with live: true, like desktop). */
    suspend fun process(rec: Recording, category: String, appName: String? = null, live: Boolean = false, onStage: (String) -> Unit): Result {
        val t0 = SystemClock.elapsedRealtime()
        if (rec.durationMs < MIN_RECORDING_MS) return Result.Empty("Too short – tap the mic again to stop")
        // No real voice → don't send it: Whisper invents text for silence (same gate as the desktop VAD).
        if (rec.voiceMs < MIN_VOICE_MS) return Result.Empty("No speech heard")
        if (!Account.isSignedIn) return Result.Failed("Sign in to MyWisper first")

        groq.warmUp() // usually already done when recording started; cheap if the connection is open
        val settings = Settings(app)
        val prompts = Prompts.get(app)
        val dict = SyncRepo.dictionary(app) // synced from the PC (desktop Dictionary tab)

        var raw = ""
        var sttMs = 0L
        var llmMs = 0L
        var chat: GroqClient.Chat? = null
        return try {
            onStage("Transcribing")
            val words = dict.words.take(40).joinToString(", ")
            val whisperPrompt = (if (words.isEmpty()) prompts.whisperPrompt else "${prompts.whisperPrompt} $words.").take(800)
            val s0 = SystemClock.elapsedRealtime()
            // Compressed audio uploads ~8× faster than WAV (desktop sends Opus for the same reason).
            val m4a = rec.m4a ?: AacEncoder.encode(rec.pcm, Recorder.SAMPLE_RATE, app.cacheDir) // usually done while recording
            val encMs = SystemClock.elapsedRealtime() - s0
            raw = if (m4a != null) groq.transcribe(m4a, settings.whisperModel, whisperPrompt, settings.language, "speech.m4a", "audio/mp4")
            else groq.transcribe(rec.wav(), settings.whisperModel, whisperPrompt, settings.language)
            sttMs = SystemClock.elapsedRealtime() - s0
            Log.i(TAG, "upload ${if (m4a != null) "m4a ${m4a.size / 1024} KB" else "wav ${rec.pcm.size / 1024} KB"}, encode $encMs ms, whisper total $sttMs ms")
            val text = TextRules.filterTranscript(raw, strict = rec.voiceMs < CLEAR_VOICE_MS, hallucinations = prompts.hallucinations, always = prompts.hallucinationsAlways)
            Log.i(TAG, "whisper: ${rec.durationMs} ms audio, voice ${rec.voiceMs} ms → ${text.length} chars")
            if (text.isEmpty()) return Result.Empty("No speech heard")

            onStage("Cleaning up")
            var system = prompts.cleanupFor(category)
            if (dict.words.isNotEmpty()) system += "\n- Spell these names/terms exactly like this: ${dict.words.joinToString(", ")}."
            val l0 = SystemClock.elapsedRealtime()
            val result = try {
                chat = groq.chatWithUsage(settings.llmModel, system, "<dictation>\n$text\n</dictation>", temperature = 0.2)
                llmMs = SystemClock.elapsedRealtime() - l0
                Result.Text(TextRules.applyReplacements(chat!!.text.ifBlank { text }, dict.replacements), SystemClock.elapsedRealtime() - t0)
            } catch (e: GroqException) {
                Log.w(TAG, "cleanup failed: ${e.message}")
                Result.Text(TextRules.applyReplacements(text, dict.replacements), SystemClock.elapsedRealtime() - t0,
                    note = "Cleanup failed – inserted raw text")
            }
            Log.i(TAG, "cleanup ($category): ${result.text.length} chars, llm $llmMs ms, total ${result.ms} ms")
            saveHistory(rec, category, appName, settings, raw, result.text, sttMs, llmMs, chat, result.ms, if (result.note == null) "ok" else "raw", live)
            result
        } catch (e: GroqException) {
            Log.w(TAG, "dictation failed: ${e.message}")
            Result.Failed(e.message ?: "Something went wrong")
        }
    }

    // Same fields as a desktop history entry, so the PC dashboard shows phone dictations (and counts them in stats).
    private fun saveHistory(
        rec: Recording, category: String, appName: String?, settings: Settings, raw: String, clean: String,
        sttMs: Long, llmMs: Long, chat: GroqClient.Chat?, totalMs: Long, status: String, live: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val audioSec = rec.durationMs / 1000.0
        val words = clean.split(Regex("\\s+")).count { it.isNotBlank() }
        val pt = chat?.promptTokens ?: 0
        val ct = chat?.completionTokens ?: 0
        val (pin, pout) = Models.price(settings.llmModel)
        val cost = (maxOf(audioSec, 10.0) / 3600) * Models.sttPerHour(settings.whisperModel) + pt / 1e6 * pin + ct / 1e6 * pout
        SyncRepo.addHistory(mapOf(
            "id" to "$now-" + List(5) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
            "time" to Instant.ofEpochMilli(now).toString(),
            "updatedAt" to now,
            "device" to "android",
            "kind" to "dictation",
            "app" to appName,
            "category" to category,
            "audioSec" to Math.round(audioSec * 100) / 100.0,
            "sttModel" to settings.whisperModel,
            "llmModel" to settings.llmModel,
            "rawText" to raw,
            "cleanText" to clean,
            "sttMs" to sttMs,
            "llmMs" to llmMs,
            "pasteMs" to 0,
            "totalMs" to totalMs,
            "promptTokens" to pt,
            "completionTokens" to ct,
            "words" to words,
            "wpm" to if (words > 0 && audioSec > 0) Math.round(words / (audioSec / 60)) else 0,
            "costUsd" to cost,
            "status" to status,
            "live" to if (live) true else null,
        ))
    }

    companion object {
        const val TAG = "MyWisper"
        private const val MIN_RECORDING_MS = 300
        private const val MIN_VOICE_MS = 250
        /** Below this much voice, typical "silence phrases" from Whisper are dropped (desktop CLEAR_VOICE_MS). */
        private const val CLEAR_VOICE_MS = 1000
    }
}
