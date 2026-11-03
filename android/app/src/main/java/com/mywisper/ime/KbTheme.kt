package com.mywisper.ime

import android.content.Context
import android.content.res.Configuration
import com.mywisper.core.Settings

/** The keyboard's colours. Dark = the desktop app's palette; light = its light-mode palette. */
data class KbTheme(
    val bg: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val line: Int,
    val fg: Int,
    val fg2: Int,
    val fg3: Int,
    val accent: Int,     // Enter key + mic button
    val accentInk: Int,  // text/icon on the accent
    val popup: Int,
    val dark: Boolean,
    val borders: Boolean,
) {
    companion object {
        private val ACCENTS = mapOf(
            "blue" to 0xFF3291FF.toInt(),
            "green" to 0xFF3FCF8E.toInt(),
            "purple" to 0xFF8E4EC6.toInt(),
            "orange" to 0xFFF5A524.toInt(),
        )

        fun from(context: Context, s: Settings): KbTheme {
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val dark = when (s.kbTheme) { "dark" -> true; "light" -> false; else -> systemDark }
            val base = if (dark) KbTheme(
                bg = 0xFF0A0A0A.toInt(), key = 0xFF1F1F1F.toInt(), keySpecial = 0xFF141414.toInt(),
                keyPressed = 0xFF3A3A3A.toInt(), line = 0xFF1F1F1F.toInt(), fg = 0xFFEDEDED.toInt(),
                fg2 = 0xFFA1A1A1.toInt(), fg3 = 0xFF707070.toInt(), accent = 0xFFEDEDED.toInt(),
                accentInk = 0xFF0A0A0A.toInt(), popup = 0xFF2A2A2A.toInt(), dark = true, borders = s.keyBorders,
            ) else KbTheme(
                bg = 0xFFF2F2F2.toInt(), key = 0xFFFFFFFF.toInt(), keySpecial = 0xFFE4E4E4.toInt(),
                keyPressed = 0xFFD0D0D0.toInt(), line = 0xFFDDDDDD.toInt(), fg = 0xFF0A0A0A.toInt(),
                fg2 = 0xFF555555.toInt(), fg3 = 0xFF8F8F8F.toInt(), accent = 0xFF0A0A0A.toInt(),
                accentInk = 0xFFFFFFFF.toInt(), popup = 0xFFFFFFFF.toInt(), dark = false, borders = s.keyBorders,
            )
            val accent = ACCENTS[s.kbAccent] ?: return base
            return base.copy(accent = accent, accentInk = 0xFFFFFFFF.toInt())
        }
    }
}
