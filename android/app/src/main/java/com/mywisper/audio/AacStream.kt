package com.mywisper.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Compresses to AAC (.m4a) WHILE recording, so when the user taps ✓ the file is already done and the
 * upload starts immediately (AacEncoder.encode after the fact takes ~1 s per 15 s of speech).
 * feed() is called from the recording thread; finish() after recording stopped. Any failure → null,
 * and the caller falls back to AacEncoder / WAV.
 */
class AacStream(sampleRate: Int, tmpDir: File) {
    private val out = File.createTempFile("live", ".m4a", tmpDir)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private val bytesPerSec = sampleRate * 2L
    private var track = -1
    private var written = 0L
    private var failed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun feed(pcm: ByteArray) {
        if (failed) return
        try {
            var off = 0
            while (off < pcm.size) {
                val i = codec.dequeueInputBuffer(5_000)
                if (i < 0) { drain(0); continue }
                val buf = codec.getInputBuffer(i)!!
                buf.clear()
                val n = minOf(buf.remaining(), pcm.size - off)
                buf.put(pcm, off, n)
                codec.queueInputBuffer(i, 0, n, written * 1_000_000L / bytesPerSec, 0)
                written += n
                off += n
            }
            drain(0)
        } catch (e: Exception) {
            failed = true
        }
    }

    private fun drain(timeoutUs: Long): Boolean {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
                else -> return false // nothing ready
            }
        }
    }

    /** Ends the stream and returns the .m4a bytes (or null if anything went wrong). */
    fun finish(): ByteArray? = try {
        if (failed) null else {
            val i = codec.dequeueInputBuffer(50_000)
            if (i >= 0) codec.queueInputBuffer(i, 0, 0, written * 1_000_000L / bytesPerSec, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val deadline = System.currentTimeMillis() + 1500
            while (!drain(10_000) && System.currentTimeMillis() < deadline) { /* wait for the last frames */ }
            muxer.stop()
            out.readBytes()
        }
    } catch (e: Exception) {
        null
    } finally {
        release()
    }

    /** Throws the stream away (cancel). */
    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
        out.delete()
    }
}
