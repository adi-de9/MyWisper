package com.mywisper.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings, shared by the setup app and the keyboard (same process, same SharedPreferences).
 * The sync key is encrypted with a key that never leaves the Android Keystore.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mywisper", Context.MODE_PRIVATE)

    init {
        // Older versions kept the user's own Groq key and the floating mic's settings; both are gone now.
        if (prefs.contains("apiKeyEnc") || prefs.contains("micStyle")) {
            prefs.edit().remove("apiKeyEnc").remove("micStyle").remove("hiddenApps").remove("bubbleX")
                .remove("bubbleLift").remove("livePreview").apply()
        }
    }

    /** The signed-in user's sync key from the server (ServerApi.dataKey), kept encrypted by the Keystore. */
    fun dataKey(uid: String): ByteArray? {
        val saved = prefs.getString(KEY_DATA, null) ?: return null
        val (owner, key) = runCatching { KeyCrypto.decrypt(saved) }.getOrNull()?.split(":", limit = 2) ?: return null
        return if (owner == uid) runCatching { java.util.Base64.getDecoder().decode(key) }.getOrNull() else null
    }

    fun saveDataKey(uid: String, key: ByteArray?) {
        if (key == null) prefs.edit().remove(KEY_DATA).apply()
        else prefs.edit().putString(KEY_DATA, KeyCrypto.encrypt("$uid:" + java.util.Base64.getEncoder().encodeToString(key))).apply()
    }

    /** "auto" | "hi" | "en" (Whisper language hint), same as desktop. */
    var language: String
        get() = prefs.getString("language", "auto") ?: "auto"
        set(value) = prefs.edit().putString("language", value).apply()

    var vibrate: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(value) = prefs.edit().putBoolean("vibrate", value).apply()

    var sound: Boolean
        get() = prefs.getBoolean("sound", false)
        set(value) = prefs.edit().putBoolean("sound", value).apply()

    var doubleSpacePeriod: Boolean
        get() = prefs.getBoolean("doubleSpacePeriod", true)
        set(value) = prefs.edit().putBoolean("doubleSpacePeriod", value).apply()

    /** Whether the mic permission was requested once (after a second "deny", only system settings can grant it). */
    var askedMicBefore: Boolean
        get() = prefs.getBoolean("askedMic", false)
        set(value) = prefs.edit().putBoolean("askedMic", value).apply()

    /** First-run setup finished (or skipped); afterwards the app opens on Home. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    // ---------- MyWisper keyboard look & extras ----------
    /** A permanent 1–0 row above the letters. */
    var numberRow: Boolean
        get() = prefs.getBoolean("numberRow", false)
        set(value) = prefs.edit().putBoolean("numberRow", value).apply()

    /** "system" | "dark" | "light" */
    var kbTheme: String
        get() = prefs.getString("kbTheme", "system") ?: "system"
        set(value) = prefs.edit().putString("kbTheme", value).apply()

    /** Colour of the Enter key and the mic: "mono" | "blue" | "green" | "purple" | "orange" */
    var kbAccent: String
        get() = prefs.getString("kbAccent", "mono") ?: "mono"
        set(value) = prefs.edit().putString("kbAccent", value).apply()

    var keyBorders: Boolean
        get() = prefs.getBoolean("keyBorders", true)
        set(value) = prefs.edit().putBoolean("keyBorders", value).apply()

    /** "small" | "normal" | "tall" */
    var kbHeight: String
        get() = prefs.getString("kbHeight", "normal") ?: "normal"
        set(value) = prefs.edit().putString("kbHeight", value).apply()

    /** Remember copied text in the keyboard's clipboard panel (kept on this phone only). */
    var clipboardHistory: Boolean
        get() = prefs.getBoolean("clipboardHistory", true)
        set(value) = prefs.edit().putBoolean("clipboardHistory", value).apply()

    // Models: fixed by the app (MyWisper pays for them; same as the PC app's defaults, and the server only allows these).
    val whisperModel = "whisper-large-v3"
    val llmModel = "qwen/qwen3.8-27b"
    val transformModel = "openai/gpt-oss-120b"

    /** Type each sentence as soon as you pause (off = everything at the end). */
    var liveTyping: Boolean
        get() = prefs.getBoolean("liveTyping", false)
        set(value) = prefs.edit().putBoolean("liveTyping", value).apply()

    private companion object {
        const val KEY_DATA = "dataKeyEnc"
    }
}

/** AES-GCM with a key kept in the Android Keystore. Output: base64(iv + ciphertext). */
private object KeyCrypto {
    private const val ALIAS = "mywisper_api_key" // name kept from when it protected the Groq key
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String {
        val data = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        }
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}
