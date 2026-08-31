package com.xiaomi.tts.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 音频格式转换器：将 Android 支持的任意音频（m4a/aac/ogg/amr/flac/视频音轨等）
 * 解码并封装为 WAV（API 克隆接口仅支持 mp3/wav）。
 * 全程使用系统 MediaCodec，无第三方依赖，离线完成。
 */
object AudioConverter {

    /**
     * 解码 Uri 指向的音频为 WAV 字节数组（保持原采样率/声道，PCM16）
     */
    fun decodeToWav(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            // 定位音频轨道
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw IOException("文件中没有可解码的音频轨道")
            }
            extractor.selectTrack(trackIndex)

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()

                var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                val pcm = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var sawInputEOS = false
                var sawOutputEOS = false
                var decodeMs = System.currentTimeMillis()

                while (!sawOutputEOS) {
                    if (!sawInputEOS) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                        in 0..Int.MAX_VALUE -> {
                            if (info.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)!!
                                val chunk = ByteArray(info.size)
                                outBuf.get(chunk)
                                outBuf.clear()
                                pcm.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEOS = true
                            }
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        else -> { /* INFO_TRY_AGAIN_LATER */ }
                    }

                    // 防御：解码超过 30 秒视为异常（样本不需要那么长）
                    if (System.currentTimeMillis() - decodeMs > 30_000) {
                        throw IOException("解码超时，文件可能过大或损坏")
                    }
                }

                if (pcm.size() == 0) {
                    throw IOException("未能解码出音频数据")
                }
                return pcmToWav(pcm.toByteArray(), sampleRate, channels)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * 从 WAV 提取 PCM 数据（定位 data chunk，兼容非 44 字节头）
     */
    fun wavToPcm(wav: ByteArray): ByteArray {
        if (wav.size < 12) return wav
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = (wav[pos + 4].toInt() and 0xFF) or
                    ((wav[pos + 5].toInt() and 0xFF) shl 8) or
                    ((wav[pos + 6].toInt() and 0xFF) shl 16) or
                    ((wav[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") {
                val end = minOf(pos + 8 + size, wav.size)
                return wav.copyOfRange(pos + 8, end)
            }
            pos += 8 + size + (size and 1)
        }
        // 兜底：剥标准 44 字节头
        return wav.copyOfRange(44.coerceAtMost(wav.size), wav.size)
    }

    /**
     * PCM16 封装 WAV 头（小端）
     */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArrayOutputStream(44 + pcm.size)

        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))

        str("RIFF"); le32(36 + pcm.size); str("WAVE")
        str("fmt "); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(blockAlign); le16(bitsPerSample)
        str("data"); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
