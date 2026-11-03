package com.mywisper.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.time.Instant

/** Rewrite selected text with a saved prompt, and match a spoken instruction to one (desktop transform.js + groq.matchPrompt). */
class Transform(context: Context, private val groq: GroqClient = GroqClient()) {
    private val app = context.applicationContext

    data class Match(val id: String?, val extra: String)
    data class Output(val text: String, val ms: Long)

    /** "make EOD and keep it short" → { id: "eod", extra: "keep it short" }. No match → id null, extra = the instruction. */
    suspend fun matchPrompt(instruction: String): Match {
        val out = groq.chatWithUsage(
            "qwen/qwen3.8-27b", PromptLibrary.matchSystem(app),
            "Saved prompts:\n${PromptLibrary.forMatching(app)}\n\nInstruction: $instruction",
            temperature = 0.0, maxTokens = 300, jsonMode = true,
        )
        val o = runCatching { JSONObject(out.text) }.getOrDefault(JSONObject())
        val id = o.optString("id").takeIf { it.isNotEmpty() && it != "null" && PromptLibrary.get(app, it) != null }
        val extra = o.optString("extra").trim()
        return Match(id, if (id != null) extra else extra.ifEmpty { instruction })
    }

    /** Runs a prompt (or a one-off instruction) on the text and saves it to the shared history. */
    suspend fun run(text: String, promptId: String?, extra: String = "", instruction: String? = null, appName: String? = null): Output {
        val settings = Settings(app)
        val entry = promptId?.let { PromptLibrary.get(app, it) }
        val t0 = System.currentTimeMillis()
        val model = settings.transformModel
        val out = groq.chatWithUsage(
            model, PromptLibrary.render(app, entry, extra), "<text>\n$text\n</text>",
            temperature = entry?.temperature ?: 0.3, maxTokens = 4096,
        )
        val ms = System.currentTimeMillis() - t0
        Log.i(TAG, "rewrite ${promptId ?: "custom"} with $model: $ms ms")
        val now = System.currentTimeMillis()
        val price = Models.price(model)
        SyncRepo.addHistory(mapOf(
            "id" to "$now-" + List(5) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString(""),
            "time" to Instant.ofEpochMilli(now).toString(), "updatedAt" to now, "device" to "android",
            "kind" to "transform", "action" to (promptId ?: "custom"),
            "label" to (entry?.name ?: "Custom: ${extra.take(40)}") + if (entry != null && extra.isNotBlank()) " + \"$extra\"" else "",
            "instruction" to instruction, "extra" to extra.ifBlank { null }, "app" to (appName ?: "MyWisper"),
            "status" to if (out.text.isNotBlank()) "ok" else "error",
            "rawText" to text, "cleanText" to out.text, "llmModel" to model, "llmMs" to ms, "totalMs" to ms,
            "promptTokens" to out.promptTokens, "completionTokens" to out.completionTokens, "words" to 0, "wpm" to 0, "audioSec" to 0,
            "costUsd" to out.promptTokens / 1e6 * price.first + out.completionTokens / 1e6 * price.second,
        ))
        return Output(out.text, ms)
    }

    companion object { private const val TAG = "MyWisper" }
}
