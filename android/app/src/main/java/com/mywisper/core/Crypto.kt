package com.mywisper.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the text MyWisper syncs to Firestore (same format as desktop src/main/crypto.js):
 * key = HKDF-SHA256(server secret, salt = uid, info = "mywisper-data-v1"), AES-256-GCM,
 * value = "enc1:" + base64(iv | ciphertext | tag).
 */
object Crypto {
    const val PREFIX = "enc1:"
    private const val INFO = "mywisper-data-v1"
    private val random = SecureRandom()

    /**
     * The key for one user. The MyWisper server does this (ServerApi.dataKey) – the secret never ships in the
     * app; kept here so tests can check both sides derive the same key.
     */
    fun userKey(uid: String, secret: ByteArray): ByteArray {
        // HKDF (RFC 5869) with SHA-256: extract, then one expand block (32 bytes).
        val prk = hmac(uid.toByteArray(Charsets.UTF_8), secret)
        return hmac(prk, INFO.toByteArray(Charsets.UTF_8) + byteArrayOf(1)).copyOf(32)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    fun encrypt(key: ByteArray, text: String, iv: ByteArray = ByteArray(12).also { random.nextBytes(it) }): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return PREFIX + Base64.getEncoder().encodeToString(iv + c.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** Values without the prefix (old unencrypted docs) come back unchanged. Throws on a wrong key. */
    fun decrypt(key: ByteArray, value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val data = Base64.getDecoder().decode(value.substring(PREFIX.length))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }

    /** History fields that hold the user's words (the rest stays readable for stats), as on desktop. */
    val HISTORY_TEXT = listOf("rawText", "cleanText", "original", "result", "instruction", "extra", "label", "app", "title", "error")

    fun sealHistory(key: ByteArray, e: Map<String, Any?>): Map<String, Any?> =
        if (e["deleted"] == true) e else e.mapValues { (k, v) -> if (k in HISTORY_TEXT && v is String) encrypt(key, v) else v }

    fun openHistory(key: ByteArray, e: Map<String, Any?>): Map<String, Any?> =
        e.mapValues { (k, v) -> if (k in HISTORY_TEXT && v is String) decrypt(key, v) else v }

    /** Prompts / dictionary: everything but id, updatedAt, deleted is in one encrypted JSON "data" field. */
    fun openBlob(key: ByteArray, doc: Map<String, Any?>): JSONObject {
        val data = doc["data"] as? String ?: return JSONObject(doc.filterValues { it != null })
        val o = JSONObject(decrypt(key, data))
        for (k in listOf("id", "updatedAt", "deleted")) doc[k]?.let { o.put(k, it) }
        return o
    }
}
