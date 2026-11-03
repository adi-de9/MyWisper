package com.mywisper.core

import android.content.Context
import org.json.JSONObject

/**
 * The dictation prompts, generated from the desktop app's src/main/prompts.js
 * (scripts/export-mobile-prompts.js writes assets/prompts.json before every build).
 */
class Prompts(
    val whisperPrompt: String,
    val hallucinations: List<String>,
    private val cleanup: Map<String, String>,
    val hallucinationsAlways: List<String> = emptyList(),
) {

    /** Cleanup system prompt for a kind of app ("chat", "email", …); falls back to "general". */
    fun cleanupFor(category: String): String = cleanup[category] ?: cleanup.getValue("general")

    companion object {
        @Volatile private var cached: Prompts? = null

        fun get(context: Context): Prompts = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("prompts.json").bufferedReader().use { it.readText() }).also { cached = it }
        }

        fun parse(json: String): Prompts {
            val o = JSONObject(json)
            val list = o.getJSONArray("hallucinations")
            val c = o.getJSONObject("cleanup")
            return Prompts(
                whisperPrompt = o.getString("whisperPrompt"),
                hallucinations = List(list.length()) { list.getString(it) },
                cleanup = c.keys().asSequence().associateWith { c.getString(it) },
                hallucinationsAlways = o.optJSONArray("hallucinationsAlways")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            )
        }
    }
}
