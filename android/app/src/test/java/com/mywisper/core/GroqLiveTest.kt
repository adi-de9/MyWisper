package com.mywisper.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Calls the real Groq API directly (skipped unless GROQ_API_KEY and MYWISPER_TEST_WAV are set).
 * Same path as the keyboard: Whisper → hallucination filter → cleanup with the exported desktop prompt.
 * (The app itself goes through the MyWisper server; here the Groq key stands in for the sign-in token.)
 */
class GroqLiveTest {
    private val key = System.getenv("GROQ_API_KEY").orEmpty()
    private val wav = System.getenv("MYWISPER_TEST_WAV").orEmpty()
    private fun direct() = GroqClient(baseUrl = "https://api.groq.com/openai/v1", token = { key })

    @Test fun transcribesAndCleansUp() = runBlocking {
        assumeTrue("set GROQ_API_KEY and MYWISPER_TEST_WAV to run", key.isNotBlank() && File(wav).isFile)
        val prompts = Prompts.parse(File("src/main/assets/prompts.json").readText())
        val groq = direct()

        val raw = groq.transcribe(File(wav).readBytes(), "whisper-large-v3", prompts.whisperPrompt, "auto")
        val text = TextRules.filterTranscript(raw, strict = false, hallucinations = prompts.hallucinations)
        println("whisper: $text")
        assertTrue(text.isNotBlank())

        val clean = groq.chat("qwen/qwen3.8-27b", prompts.cleanupFor("chat"), "<dictation>\n$text\n</dictation>", 0.2)
        println("cleanup: $clean")
        assertTrue(clean.isNotBlank())
        assertFalse("no Devanagari", clean.any { it in 'ऀ'..'ॿ' })
        assertFalse("no tags", clean.contains("<"))
    }

    @Test fun rejectsABadToken() = runBlocking {
        assumeTrue(key.isNotBlank())
        val bad = GroqClient(baseUrl = "https://api.groq.com/openai/v1", token = { "gsk_invalid" })
        val e = runCatching { bad.chat("qwen/qwen3.8-27b", "x", "hi", 0.0, 5) }.exceptionOrNull()
        assertTrue("got $e", e is GroqException && e.message == "Sign in to MyWisper again")
    }
}
