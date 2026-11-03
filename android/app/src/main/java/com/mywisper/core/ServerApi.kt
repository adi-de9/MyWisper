package com.mywisper.core

import com.google.firebase.auth.FirebaseAuth
import com.mywisper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The MyWisper server (functions/ in the repo; same as desktop src/main/api.js). It holds the Groq key and
 * each user's daily free limit, and hands out the user's own sync key. Requests carry the Firebase ID token.
 */
object ServerApi {
    val BASE: String = BuildConfig.API_BASE

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Firebase ID token of the signed-in user (cached and refreshed by Firebase; force = get a fresh one). */
    suspend fun token(force: Boolean = false): String {
        val user = FirebaseAuth.getInstance().currentUser ?: throw GroqException("Sign in to MyWisper first", code = "signin")
        return try {
            user.getIdToken(force).await().token ?: throw GroqException("Sign in to MyWisper again", code = "signin")
        } catch (e: GroqException) {
            throw e
        } catch (e: Exception) {
            throw GroqException("No internet", e)
        }
    }

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        var force = false
        while (true) {
            val req = Request.Builder().url("$BASE$path").header("Authorization", "Bearer ${token(force)}").build()
            try {
                http.newCall(req).execute().use { res ->
                    val body = res.body?.string().orEmpty()
                    if (res.isSuccessful) return@withContext JSONObject(body)
                    if (res.code == 401 && !force) { force = true; return@use } // token just expired: once more
                    throw GroqException(errorMessage(res.code, body), code = errorCode(body))
                }
            } catch (e: IOException) {
                throw GroqException("No internet", e)
            }
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }

    /** This user's 32-byte sync key (Settings keeps it, encrypted, after the first time). */
    suspend fun dataKey(): ByteArray {
        val key = Base64.getDecoder().decode(get("/dataKey").getString("key"))
        if (key.size != 32) throw GroqException("Server sent an invalid key")
        return key
    }

    data class Usage(val audioSec: Int, val limitSec: Int, val resetAt: Long) {
        val reached get() = audioSec >= limitSec
    }

    /** Today's use of the daily free limit. */
    suspend fun usage(): Usage {
        val o = get("/usage")
        return Usage(o.optInt("audioSec"), o.optInt("limitSec", 900), o.optLong("resetAt"))
    }

    fun errorCode(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** Short message for the keyboard's status bar. */
    fun errorMessage(status: Int, body: String): String = when (errorCode(body)) {
        "daily_limit" -> "Daily free limit reached – resets at midnight UTC"
        "too_fast" -> "Too many requests – wait a minute"
        "paused" -> "MyWisper is paused – try again later"
        "too_large" -> "Recording too long"
        else -> when (status) {
            401, 403 -> "Sign in to MyWisper again"
            413 -> "Recording too long"
            429 -> "Busy – try again soon"
            in 500..599 -> "Server is having trouble – try again"
            else -> "Error $status"
        }
    }
}
