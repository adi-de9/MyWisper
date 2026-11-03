package com.mywisper.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** An error with a short message that fits in the keyboard's status bar. code = server reason, e.g. "daily_limit". */
class GroqException(message: String, cause: Throwable? = null, val code: String? = null) : Exception(message, cause)

/**
 * Groq calls: speech-to-text (Whisper) and chat (cleanup). Mirrors desktop src/main/groq.js.
 * They go through the MyWisper server (ServerApi), which holds the Groq key and counts the daily free limit;
 * the request is signed with the user's Firebase ID token. Tests pass Groq's own URL and a key as the token.
 */
class GroqClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = ServerApi.BASE + "/openai/v1",
    private val token: suspend (force: Boolean) -> String = { ServerApi.token(it) },
) {
    /** Speech → raw text. */
    suspend fun transcribe(
        audio: ByteArray, model: String, prompt: String, language: String,
        fileName: String = "speech.wav", mimeType: String = "audio/wav",
    ): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audio.toRequestBody(mimeType.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("prompt", prompt)
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotEmpty() && language != "auto") addFormDataPart("language", language) }
            .build()
        val json = send(Request.Builder().url("$baseUrl/audio/transcriptions").post(body))
        return JSONObject(json).optString("text", "")
    }

    /** A chat reply plus the tokens it used (for the usage stats). */
    data class Chat(val text: String, val promptTokens: Int, val completionTokens: Int)

    /** One chat call with a system prompt; returns the cleaned-up reply text. */
    suspend fun chat(model: String, system: String, user: String, temperature: Double, maxTokens: Int = 2048): String =
        chatWithUsage(model, system, user, temperature, maxTokens).text

    suspend fun chatWithUsage(
        model: String, system: String, user: String, temperature: Double, maxTokens: Int = 2048,
        jsonMode: Boolean = false,
    ): Chat {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_completion_tokens", maxTokens)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        // Reasoning models must be told to think briefly (same as desktop modelOptions()).
        if (jsonMode) payload.put("response_format", JSONObject().put("type", "json_object"))
        if (model.startsWith("openai/gpt-oss")) payload.put("reasoning_effort", "low").put("include_reasoning", false)
        if (model.startsWith("qwen/")) payload.put("reasoning_effort", "none").put("reasoning_format", "hidden")
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val json = send(Request.Builder().url("$baseUrl/chat/completions").post(body))
        val o = JSONObject(json)
        val content = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
        val usage = o.optJSONObject("usage")
        return Chat(TextRules.normalizeOutput(content), usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0)
    }

    /**
     * Opens the connection ahead of time (DNS + TLS, ~0.3–1 s on mobile data) while the user is still
     * speaking, so the real upload starts straight away; also wakes the server. No sign-in, no Groq call.
     */
    fun warmUp() {
        runCatching {
            http.newCall(Request.Builder().url(baseUrl.substringBefore("/openai") + "/ping").get().build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {}
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) { response.close() }
                })
        }
    }

    private suspend fun send(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        var force = false
        var lastError = GroqException("No internet")
        for (attempt in 1..2) { // one retry for server hiccups, dropped connections and an expired sign-in token
            val request = builder.header("Authorization", "Bearer ${token(force)}").build()
            try {
                http.newCall(request).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    if (res.isSuccessful) return@withContext text
                    lastError = GroqException(ServerApi.errorMessage(res.code, text), code = ServerApi.errorCode(text))
                    if (res.code == 401 && !force) force = true
                    else if (res.code < 500) throw lastError
                }
            } catch (e: GroqException) {
                throw e
            } catch (e: UnknownHostException) {
                throw GroqException("No internet", e)
            } catch (e: InterruptedIOException) {
                throw GroqException("Took too long – try again", e)
            } catch (e: IOException) {
                lastError = GroqException("No internet", e)
            }
        }
        throw lastError
    }

}
