package com.mywisper.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Compresses a recording to AAC in an .m4a file (~4 KB per second instead of 32 KB for WAV), so the upload
 * to Groq is about 8× smaller – that upload is what makes dictation slow on mobile data.
 * Uses the phone's built-in encoder (MediaCodec), no extra libraries.
 */
object AacEncoder {
    private const val BITRATE = 32_000

    /** PCM 16-bit mono → .m4a bytes, or null if this phone's encoder fails (then WAV is sent instead). */
    fun encode(pcm: ByteArray, sampleRate: Int, tmpDir: File): ByteArray? {
        val out = File.createTempFile("speech", ".m4a", tmpDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65_536)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var outputDone = false
            val bytesPerSec = sampleRate * 2L
            while (!outputDone) {
                // Feed every free input buffer we can get right now.
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(if (offset == 0) 10_000 else 0)
                    if (i < 0) break
                    val buf = codec.getInputBuffer(i)!!
                    buf.clear()
                    val n = minOf(buf.remaining(), pcm.size - offset)
                    buf.put(pcm, offset, n)
                    val timeUs = offset * 1_000_000L / bytesPerSec
                    offset += n
                    inputDone = offset >= pcm.size
                    codec.queueInputBuffer(i, 0, n, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                }
                // Take everything that's encoded; only wait (briefly) when there is nothing to do.
                val o = codec.dequeueOutputBuffer(info, 2_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
            muxer.stop()
            out.readBytes()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
            out.delete()
        }
    }
}
