package com.mywisper.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File

/** One finished recording. */
class Recording(val pcm: ByteArray, val voiceMs: Int, val peak: Float, val m4a: ByteArray? = null) {
    val durationMs: Int get() = pcm.size / (Recorder.SAMPLE_RATE * 2 / 1000)
    fun wav(): ByteArray = Wav.fromPcm16(pcm, Recorder.SAMPLE_RATE)
}

/** One 30 ms frame, as it is recorded (live typing and the live preview listen to these). */
class Frame(val pcm: ByteArray, val voice: Boolean, val quiet: Boolean)

/**
 * Records the microphone as 16 kHz mono 16-bit PCM (what Whisper uses internally) into memory.
 * onLevel gets a 0..1 loudness for the meter; onFrame (optional) gets every frame. Both are called
 * from the recording thread.
 */
class Recorder(
    private val onFrame: ((Frame) -> Unit)? = null,
    /** Folder for compressing while recording (so the upload can start right away); null = don't. */
    private val encodeDir: File? = null,
    private val onLevel: (Float) -> Unit,
) {
    private var aac: AacStream? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()
    private val meter = VoiceMeter(FRAME_MS)
    var startedAt = 0L
        private set

    val isRecording get() = running

    /** Starts recording; false if the microphone can't be opened (no permission, or in use by a call). */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun start(): Boolean {
        if (running) return true
        synchronized(pcm) { pcm.reset() }
        meter.reset()
        aac = encodeDir?.let { dir -> runCatching { AacStream(SAMPLE_RATE, dir) }.getOrNull() }
        fakeAudio?.let { file ->
            // Debug builds only: play a WAV file as if it were the microphone (tests without speaking).
            fakeAudio = null
            val samples = runCatching { Wav.readPcm16(file.readBytes()) }.getOrNull() ?: return false
            running = true
            startedAt = SystemClock.elapsedRealtime()
            thread = Thread({ fakeLoop(samples) }, "MyWisperFakeMic").apply { start() }
            return true
        }
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME_SAMPLES * 2 * 8))
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        try { r.startRecording() } catch (e: Exception) { r.release(); return false }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); return false }
        record = r
        running = true
        startedAt = SystemClock.elapsedRealtime()
        thread = Thread({ readLoop(r) }, "MyWisperRecorder").apply { start() }
        return true
    }

    private fun readLoop(r: AudioRecord) {
        val samples = ShortArray(FRAME_SAMPLES)
        while (running) {
            val n = r.read(samples, 0, FRAME_SAMPLES)
            if (n <= 0) { if (n < 0) break else continue }
            handle(samples, n)
        }
    }

    private fun fakeLoop(all: ShortArray) {
        var at = 0
        val frame = ShortArray(FRAME_SAMPLES)
        val silence = ShortArray(FRAME_SAMPLES)
        while (running) {
            if (at < all.size) {
                val n = minOf(FRAME_SAMPLES, all.size - at)
                System.arraycopy(all, at, frame, 0, n)
                if (n < FRAME_SAMPLES) frame.fill(0, n)
                at += n
                handle(frame, FRAME_SAMPLES)
            } else handle(silence, FRAME_SAMPLES) // file finished: silence until stopped
            Thread.sleep(FRAME_MS.toLong()) // real-time pace
        }
    }

    private fun handle(samples: ShortArray, n: Int) {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = samples[i].toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        synchronized(pcm) { pcm.write(bytes, 0, bytes.size) }
        aac?.feed(bytes)
        onLevel(meter.frame(samples, n))
        onFrame?.invoke(Frame(bytes, meter.lastWasVoice, meter.lastWasQuiet))
    }

    /** Stops and returns what was recorded. */
    fun stop(): Recording {
        running = false
        record?.let { r -> try { r.stop() } catch (_: Exception) {} }
        thread?.join(500)
        record?.release()
        record = null
        thread = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        val m4a = aac?.finish()
        aac = null
        return Recording(data, meter.voiceMs, meter.peak, m4a)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 30
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000

        /** Debug builds: the next recording plays this WAV instead of using the mic (set by a test broadcast). */
        @Volatile var fakeAudio: File? = null
    }
}
