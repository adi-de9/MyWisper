package com.mywisper.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One Rewrite prompt (EOD report, Polish, the user's own…). */
data class PromptEntry(
    val id: String, val name: String, val description: String, val prompt: String,
    val temperature: Double, val pinned: Boolean, val builtIn: Boolean,
)

/**
 * The prompt library on the phone: the built-in prompts (exported from the desktop app into
 * assets/prompts.json) with the user's edits and own prompts from sync on top. Same merge as
 * desktop src/main/library.js list(). Prompts are edited on the PC.
 */
object PromptLibrary {
    private var defaults: List<JSONObject>? = null
    private var raw: JSONObject? = null

    private fun asset(context: Context): JSONObject = raw ?: JSONObject(
        context.assets.open("prompts.json").bufferedReader().use { it.readText() },
    ).also { raw = it }

    private fun defaults(context: Context): List<JSONObject> = defaults ?: asset(context).getJSONArray("library").let { a ->
        List(a.length()) { a.getJSONObject(it) }
    }.also { defaults = it }

    fun list(context: Context): List<PromptEntry> {
        val edits = SyncRepo.promptEdits(context).associateBy { it.optString("id") }
        val builtIns = defaults(context).filter { edits[it.getString("id")]?.optBoolean("deleted") != true }.map { d ->
            val e = edits[d.getString("id")]
            fun pick(k: String) = if (e != null && e.has(k) && !e.isNull(k)) e.get(k) else d.opt(k)
            PromptEntry(
                id = d.getString("id"), name = pick("name").toString(), description = pick("description")?.toString().orEmpty(),
                prompt = pick("prompt").toString(), temperature = (pick("temperature") as? Number)?.toDouble() ?: 0.3,
                pinned = (pick("pinned") as? Boolean) ?: false, builtIn = true,
            )
        }
        val defaultIds = defaults(context).map { it.getString("id") }.toSet()
        val own = edits.values.filter { it.optString("id") !in defaultIds && !it.optBoolean("deleted") && it.has("prompt") }.map { e ->
            PromptEntry(
                id = e.getString("id"), name = e.optString("name", "Untitled prompt"), description = e.optString("description"),
                prompt = e.getString("prompt"), temperature = e.optDouble("temperature", 0.3), pinned = e.optBoolean("pinned"), builtIn = false,
            )
        }
        return builtIns + own
    }

    fun get(context: Context, id: String) = list(context).firstOrNull { it.id == id }

    /** e.g. "29 Sep 2026" (desktop todayLabel()). */
    fun todayLabel(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

    /** The final system prompt: {date} filled in, the spoken extra wish, and the output-only wrapper (desktop render()). */
    fun render(context: Context, entry: PromptEntry?, extra: String = ""): String {
        val a = asset(context)
        val parts = mutableListOf(entry?.prompt?.replace("{date}", todayLabel()) ?: a.getString("renderNoEntry"))
        if (extra.isNotBlank()) parts += listOf("", a.getString("renderExtra") + extra)
        parts += listOf("", a.getString("renderWrapper"))
        return parts.joinToString("\n")
    }

    fun matchSystem(context: Context): String = asset(context).getString("matchSystem")

    /** [{id, name, description}] for the matcher. */
    fun forMatching(context: Context): String = JSONArray(list(context).map {
        JSONObject().put("id", it.id).put("name", it.name).put("description", it.description)
    }).toString()
}
