package com.mywisper.core

/** Estimated Groq prices for the usage stats in the shared history (same as desktop config.js). */
object Models {
    /** USD per hour of audio. */
    fun sttPerHour(model: String) = when (model) { "whisper-large-v3-turbo" -> 0.04; else -> 0.111 }

    /** USD per million (input, output) tokens. */
    fun price(model: String) = when (model) {
        "openai/gpt-oss-20b" -> 0.075 to 0.30
        "openai/gpt-oss-120b" -> 0.15 to 0.60
        else -> 0.29 to 0.59
    }
}
