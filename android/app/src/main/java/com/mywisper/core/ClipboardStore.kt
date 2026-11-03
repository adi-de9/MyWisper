package com.mywisper.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The keyboard's clipboard history (like Gboard's): recently copied text, kept on this phone only
 * (never synced). Pinned items stay; others disappear after an hour. Newest first.
 */
class ClipboardStore(context: Context) {
    data class Item(val text: String, val time: Long, val pinned: Boolean)

    private val prefs = context.applicationContext.getSharedPreferences("mywisper_clipboard", Context.MODE_PRIVATE)

    fun items(): List<Item> {
        val now = System.currentTimeMillis()
        return load().filter { it.pinned || now - it.time < KEEP_MS }
    }

    /** Adds copied text (moves it to the top if it's already there). */
    fun add(text: String) {
        if (text.isBlank() || text.length > MAX_CHARS) return
        val list = items().toMutableList()
        val old = list.firstOrNull { it.text == text }
        list.removeAll { it.text == text }
        list.add(0, Item(text, System.currentTimeMillis(), old?.pinned == true))
        val unpinned = list.filter { !it.pinned }
        save(list.filter { it.pinned || it in unpinned.take(MAX_UNPINNED) })
    }

    fun togglePin(text: String) = save(items().map { if (it.text == text) it.copy(pinned = !it.pinned) else it })
    fun delete(text: String) = save(items().filter { it.text != text })
    fun clear() = save(items().filter { it.pinned })

    private fun load(): List<Item> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Item(it.getString("t"), it.getLong("at"), it.optBoolean("pin")) } }
    }.getOrDefault(emptyList())

    private fun save(list: List<Item>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.text).put("at", it.time).put("pin", it.pinned)) }
        prefs.edit().putString("items", a.toString()).apply()
    }

    private companion object {
        const val KEEP_MS = 60 * 60 * 1000L
        const val MAX_UNPINNED = 20
        const val MAX_CHARS = 5000
    }
}
