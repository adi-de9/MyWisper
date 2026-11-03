package com.mywisper.ime

import android.content.Context
import android.graphics.Typeface

/** Geist (the desktop app's font), converted to TTF in assets/fonts. Falls back to the system font. */
object Fonts {
    private val cache = mutableMapOf<Int, Typeface>()

    fun sans(context: Context, weight: Int = 450): Typeface = synchronized(cache) {
        cache.getOrPut(weight) {
            runCatching {
                Typeface.Builder(context.assets, "fonts/Geist-Variable.ttf")
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }.getOrNull() ?: Typeface.DEFAULT
        }
    }
}
