package com.mywisper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PromptsTest {
    @Test fun readsTheExportedDesktopPrompts() {
        // Generated from the desktop prompts by scripts/export-mobile-prompts.js (runs before every build).
        val p = Prompts.parse(File("src/main/assets/prompts.json").readText())
        assertTrue(p.cleanupFor("chat").contains("CHAT app"))
        assertTrue(p.cleanupFor("general").contains("Roman script"))
        assertEquals(p.cleanupFor("general"), p.cleanupFor("unknown-category"))
        assertTrue(p.hallucinations.contains("thank you"))
    }
}
