package com.mywisper.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mywisper.audio.Frame
import com.mywisper.audio.Recording
import com.mywisper.audio.SentenceSplitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Live typing: every sentence is sent to Groq as soon as the user pauses, and the results are typed
 * strictly in the order they were spoken (desktop main.js live typing). Used by the floating mic and
 * the MyWisper keyboard. feed() takes recorded frames (any thread); everything else runs on the main thread.
 *
 * insert(text) types one sentence and returns false if it couldn't (then it ends up on the clipboard).
 */
class LiveQueue(
    private val scope: CoroutineScope,
    private val dictation: Dictation,
    private val category: String,
    private val appName: String?,
    private val insert: (String) -> Boolean,
    private val onQueued: (Int) -> Unit,
    private val onDone: (typed: Int, notTyped: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val results = Channel<Deferred<Dictation.Result>>(Channel.UNLIMITED)
    private val inFlight = Semaphore(MAX_IN_FLIGHT) // Groq rate limits: at most 2 sentences at once
    private val splitter = SentenceSplitter { rec -> main.post { enqueue(rec) } }
    private var waiting = 0
    private val typer: Job

    init {
        typer = scope.launch {
            var typed = 0
            val notTyped = StringBuilder()
            for (d in results) {
                val r = runCatching { d.await() }.getOrNull()
                onQueued(--waiting)
                when (r) {
                    is Dictation.Result.Text -> if (insert(r.text)) typed++ else notTyped.append(if (notTyped.isEmpty()) "" else " ").append(r.text)
                    is Dictation.Result.Failed -> Log.w(TAG, "live sentence failed: ${r.message}")
                    else -> {}
                }
            }
            onDone(typed, notTyped.toString())
        }
    }

    val isActive get() = typer.isActive

    fun feed(frame: Frame) = splitter.feed(frame)

    private fun enqueue(rec: Recording) {
        onQueued(++waiting)
        results.trySend(scope.async { inFlight.withPermit { processWithRetry(rec) } })
    }

    private suspend fun processWithRetry(rec: Recording): Dictation.Result {
        repeat(RETRIES) { attempt ->
            val r = dictation.process(rec, category, appName, live = true) { }
            // Groq rate limit: wait a bit and try again, so no sentence is lost.
            if (r !is Dictation.Result.Failed || "limit" !in r.message) return r
            delay(2000L * (attempt + 1))
        }
        return dictation.process(rec, category, appName, live = true) { }
    }

    /** ✓: send the sentence being spoken now, then type everything that's left. */
    fun finish() {
        splitter.flush()
        main.post { results.close() } // after the flushed sentence was queued
    }

    /** ✕: drop everything not typed yet. */
    fun cancel() {
        results.close()
        typer.cancel()
    }

    private companion object {
        const val TAG = "MyWisper"
        const val MAX_IN_FLIGHT = 2
        const val RETRIES = 3
    }
}
