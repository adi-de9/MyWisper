package com.mywisper.core

/** Small text rules shared by dictation and the keyboard (pure Kotlin, unit-tested). */
object TextRules {
    private val THINK = Regex("<think>[\\s\\S]*?</think>")
    private val TAGS = Regex("</?(dictation|text)>")
    private val LOOKALIKE_HYPHENS = Regex("[‐‑‒]")
    private val LOOKALIKE_SPACES = Regex("[    ]")
    private val LETTER_OR_DIGIT = Regex("[\\p{L}\\p{N}]")

    /** Cleans model output the same way as desktop groq.js chat(). */
    fun normalizeOutput(text: String): String = text
        .replace(THINK, "")
        .replace(TAGS, "")
        // Models sometimes emit look-alike characters that show up as boxes in some apps.
        .replace(LOOKALIKE_HYPHENS, "-")
        .replace(LOOKALIKE_SPACES, " ")
        .trim()

    /**
     * Whisper output → text to clean, or "" when it's probably noise.
     * strict = little voice was detected, so a typical silence phrase ("Thank you.") is probably invented.
     */
    fun filterTranscript(raw: String, strict: Boolean, hallucinations: List<String>, always: List<String> = emptyList()): String {
        val text = raw.trim()
        val plain = normalize(text)
        // "Thanks for watching!"-type phrases are never real dictation; "Thank you." only when little voice was heard.
        if (plain in always || (strict && plain in hallucinations)) return ""
        if (!LETTER_OR_DIGIT.containsMatchIn(text)) return "" // only punctuation, e.g. " ."
        return text
    }

    /** Lower case, no punctuation, single spaces (same as desktop groq.js normalize). */
    fun normalize(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    /** Adds a space before inserted text when it would otherwise stick to the previous word. */
    fun withLeadingSpace(before: CharSequence?, text: String): String {
        if (before.isNullOrEmpty() || text.isEmpty()) return text
        val last = before[before.length - 1]
        if (last.isWhitespace() || last in "([{\"'/@#-") return text
        if (text[0] in ".,!?;:)]}") return text
        return " $text"
    }

    /** Dictionary replacements ("my wisper" → "MyWisper"), whole words, any case (desktop groq.applyReplacements). */
    fun applyReplacements(text: String, replacements: Map<String, String>): String {
        var out = text
        for ((from, to) in replacements) {
            if (from.isBlank()) continue
            out = Regex("\\b${Regex.escape(from.trim())}\\b", RegexOption.IGNORE_CASE).replace(out, Regex.escapeReplacement(to))
        }
        return out
    }

    /** Number of characters to delete so that the word before the cursor (and spaces after it) is removed. */
    fun wordDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0 && !before[i - 1].isLetterOrDigit()) return before.length - (i - 1) // a lone symbol
        while (i > 0 && before[i - 1].isLetterOrDigit()) i--
        return before.length - i
    }
}
