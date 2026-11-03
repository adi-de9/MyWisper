package com.mywisper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same vector as desktop src/main/crypto.js, so PC and phone can read each other's data. */
class CryptoTest {
    private val secret = ByteArray(32) { 1 }
    private val key = Crypto.userKey("test-uid", secret)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun derivesTheSameKeyAsDesktop() {
        assertEquals("34c3c3e8a6f17016288e11750da7cc3f289eb057cd0c021c284cba8c7067be39", hex(key))
    }

    @Test fun encryptsExactlyLikeDesktop() {
        val v = Crypto.encrypt(key, "Kal meeting 5 baje hai – ✓", ByteArray(12) { 2 })
        assertEquals("enc1:AgICAgICAgICAgIC7JYHqNAkdh+UJ0OP6gWdYQA5yPmD8TBH6unbG9aok07dSess5q2d86XREFmHig==", v)
    }

    @Test fun decryptsDesktopData() {
        assertEquals("Kal meeting 5 baje hai – ✓",
            Crypto.decrypt(key, "enc1:AgICAgICAgICAgIC7JYHqNAkdh+UJ0OP6gWdYQA5yPmD8TBH6unbG9aok07dSess5q2d86XREFmHig=="))
        assertEquals("plain old text", Crypto.decrypt(key, "plain old text"))
    }

    @Test fun wrongKeyFails() {
        val v = Crypto.encrypt(key, "secret")
        val other = Crypto.userKey("other-uid", secret)
        assertTrue(runCatching { Crypto.decrypt(other, v) }.isFailure)
    }

    @Test fun historyKeepsNumbersReadable() {
        val sealed = Crypto.sealHistory(key, mapOf("cleanText" to "hi", "words" to 1, "time" to "t"))
        assertTrue((sealed["cleanText"] as String).startsWith("enc1:"))
        assertEquals(1, sealed["words"])
        assertEquals("hi", Crypto.openHistory(key, sealed)["cleanText"])
    }
}
