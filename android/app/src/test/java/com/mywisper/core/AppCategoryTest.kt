package com.mywisper.core

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCategoryTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test fun mapsKnownApps() {
        assertEquals("chat", AppCategory.of("com.whatsapp", text))
        assertEquals("chat", AppCategory.of("com.whatsapp.w4b", text))
        assertEquals("email", AppCategory.of("com.google.android.gm", text))
        assertEquals("ai", AppCategory.of("com.openai.chatgpt", text))
        assertEquals("docs", AppCategory.of("com.google.android.apps.docs.editors.docs", text))
        assertEquals("general", AppCategory.of("com.android.chrome", text))
        assertEquals("general", AppCategory.of(null, 0))
        assertEquals("general", AppCategory.of("com.whatsappfake", text)) // prefix must end at a dot
    }

    @Test fun usesFieldHintsForUnknownApps() {
        assertEquals("chat", AppCategory.of("x.y", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertEquals("email", AppCategory.of("x.y", text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT))
    }

    @Test fun detectsPasswordFields() {
        assertTrue(AppCategory.isPassword(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(AppCategory.isPassword(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(AppCategory.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(AppCategory.isPassword(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }
}
