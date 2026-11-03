package com.mywisper.core

import android.text.InputType

/**
 * Which kind of app the text is going into, so cleanup can format it right
 * (the Android version of desktop src/main/context.js). Categories match prompts.json.
 */
object AppCategory {
    private val RULES = listOf(
        "chat" to listOf("com.whatsapp", "org.telegram", "org.thunderdog.challegram", "com.slack", "com.discord",
            "com.microsoft.teams", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android", "com.google.android.apps.dynamite"),
        "email" to listOf("com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "ch.protonmail.android", "com.samsung.android.email.provider"),
        "ai" to listOf("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard", "ai.perplexity.app",
            "com.deepseek.chat", "ai.x.grok", "com.microsoft.copilot"),
        "docs" to listOf("notion.id", "com.google.android.apps.docs", "com.microsoft.office.word", "md.obsidian",
            "com.google.android.keep", "com.samsung.android.app.notes"),
        "code" to listOf("com.github.android"),
    )

    fun of(packageName: String?, inputType: Int): String {
        val pkg = packageName.orEmpty()
        for ((category, prefixes) in RULES) {
            if (prefixes.any { pkg == it || pkg.startsWith("$it.") }) return category
        }
        if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> return "chat"
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> return "email"
            }
        }
        return "general"
    }

    /** The app's name for the history ("WhatsApp"), or the package name if unknown. */
    fun label(context: android.content.Context, packageName: String?): String? = packageName?.let { pkg ->
        runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
    }

    /** Fields where the mic must stay off (passwords). */
    fun isPassword(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
