package com.mywisper.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextRulesTest {
    private val hallucinations = listOf("thank you", "you", "bye")
    private val always = listOf("thank you for watching", "please subscribe")

    @Test fun normalizesModelOutput() {
        assertEquals("kal 5 baje - meeting", TextRules.normalizeOutput("<think>hmm</think><dictation>kal 5 baje ‑ meeting</dictation>\n"))
    }

    @Test fun dropsSilencePhrasesOnlyWhenStrict() {
        assertEquals("", TextRules.filterTranscript(" Thank you. ", strict = true, hallucinations = hallucinations))
        assertEquals("Thank you.", TextRules.filterTranscript("Thank you.", strict = false, hallucinations = hallucinations))
        assertEquals("", TextRules.filterTranscript(" . ", strict = false, hallucinations = hallucinations))
        assertEquals("Kal milte hain", TextRules.filterTranscript("Kal milte hain", strict = true, hallucinations = hallucinations))
        // Subtitle phrases go even when plenty of "voice" was heard (noise), whatever the punctuation.
        assertEquals("", TextRules.filterTranscript("Thank you for watching!", strict = false, hallucinations = hallucinations, always = always))
        assertEquals("Thank you for the update.", TextRules.filterTranscript("Thank you for the update.", strict = true, hallucinations = hallucinations, always = always))
    }

    @Test fun addsLeadingSpaceOnlyWhenNeeded() {
        assertEquals(" hello", TextRules.withLeadingSpace("word", "hello"))
        assertEquals("hello", TextRules.withLeadingSpace("word ", "hello"))
        assertEquals("hello", TextRules.withLeadingSpace("", "hello"))
        assertEquals("hello", TextRules.withLeadingSpace(null, "hello"))
        assertEquals("hello", TextRules.withLeadingSpace("(", "hello"))
        assertEquals(", ok", TextRules.withLeadingSpace("word", ", ok"))
        assertEquals(" Next", TextRules.withLeadingSpace("done.", "Next"))
    }

    @Test fun deletesTheWordBeforeTheCursor() {
        assertEquals(5, TextRules.wordDeleteLength("hello"))
        assertEquals(6, TextRules.wordDeleteLength("say hello "))
        assertEquals(1, TextRules.wordDeleteLength("hi!"))
        assertEquals(0, TextRules.wordDeleteLength(""))
    }
}
