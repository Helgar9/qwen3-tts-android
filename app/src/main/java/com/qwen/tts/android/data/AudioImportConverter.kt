package com.qwen.tts.android.data

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Converts Android-decodable audio into the exact reference format expected by
 * qwen3-tts.cpp: 24 kHz, mono, PCM16 WAV.
 *
 * On a Galaxy S23 this covers the common user formats handled by the platform
 * codecs, including WAV, FLAC, MP3, AAC/M4A, Ogg/Vorbis, Opus and WebM audio.
 */
object AudioImportConverter {
    const val TARGET_SAMPLE_RATE = 24_000

    data class Result(
        val sourceMime: String,
        val sourceSampleRate: Int,
        val sourceChannels: Int,
        val durationMillis: Long,
    )

    fun convertToReferenceWav(source: File, destination: File): Result {
        require(source.isFile && source.length() > 0L) { "Selected audio file is empty or unavailable." }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track was found in the selected file.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("The selected audio track has no MIME type.")
            val sourceRate = intValue(inputFormat, MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            val sourceChannels = intValue(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)

            val decoded = FloatCollector()
            var decodedRate = sourceRate
            var decodedChannels = sourceChannels
            var decodedEncoding = intValue(
                inputFormat,
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            if (mime == "audio/raw") {
                decodeRawTrack(
                    extractor = extractor,
                    format = inputFormat,
                    collector = decoded,
                    channels = decodedChannels,
                    encoding = decodedEncoding,
                )
            } else {
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(inputFormat, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                                ?: error("Audio decoder returned no input buffer.")
                            inputBuffer.clear()
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val sampleTime = extractor.sampleTime.coerceAtLeast(0L)
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            decodedRate = intValue(outputFormat, MediaFormat.KEY_SAMPLE_RATE, decodedRate)
                            decodedChannels = intValue(outputFormat, MediaFormat.KEY_CHANNEL_COUNT, decodedChannels)
                                .coerceAtLeast(1)
                            decodedEncoding = intValue(
                                outputFormat,
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER,
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit

                        else -> if (outputIndex >= 0) {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                    ?: error("Audio decoder returned no output buffer.")
                                val data = outputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                appendPcmFrames(
                                    data.slice().order(ByteOrder.LITTLE_ENDIAN),
                                    decodedChannels,
                                    decodedEncoding,
                                    decoded,
                                )
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val mono = decoded.toArray()
            require(mono.isNotEmpty()) { "The selected audio decoded to zero samples." }
            val resampled = resampleLinear(mono, decodedRate, TARGET_SAMPLE_RATE)
            writePcm16Wav(destination, resampled, TARGET_SAMPLE_RATE)

            return Result(
                sourceMime = mime,
                sourceSampleRate = decodedRate,
                sourceChannels = decodedChannels,
                durationMillis = (resampled.size.toDouble() * 1000.0 / TARGET_SAMPLE_RATE).roundToInt().toLong(),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun decodeRawTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        collector: FloatCollector,
        channels: Int,
        encoding: Int,
    ) {
        val maxInput = intValue(format, MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            .coerceIn(64 * 1024, 4 * 1024 * 1024)
        val buffer = ByteBuffer.allocateDirect(maxInput).order(ByteOrder.LITTLE_ENDIAN)
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            appendPcmFrames(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, encoding, collector)
            if (!extractor.advance()) break
        }
    }

    private fun appendPcmFrames(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        collector: FloatCollector,
    ) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_DEFAULT,
            AudioFormat.ENCODING_PCM_16BIT -> 2
            else -> error("Unsupported decoded PCM encoding: $encoding")
        }
        val frameBytes = bytesPerSample * channels
        while (buffer.remaining() >= frameBytes) {
            var sum = 0.0f
            repeat(channels) {
                sum += readPcmSample(buffer, encoding)
            }
            collector.add((sum / channels).coerceIn(-1.0f, 1.0f))
        }
    }

    private fun readPcmSample(buffer: ByteBuffer, encoding: Int): Float = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 0xff) - 128) / 128.0f
        AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1.0f, 1.0f)
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val b0 = buffer.get().toInt() and 0xff
            val b1 = buffer.get().toInt() and 0xff
            val b2 = buffer.get().toInt() and 0xff
            var value = b0 or (b1 shl 8) or (b2 shl 16)
            if (value and 0x800000 != 0) value = value or -0x1000000
            value / 8_388_608.0f
        }
        AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648.0f
        AudioFormat.ENCODING_DEFAULT,
        AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32_768.0f
        else -> error("Unsupported decoded PCM encoding: $encoding")
    }

    private fun resampleLinear(input: FloatArray, inputRate: Int, outputRate: Int): FloatArray {
        require(inputRate > 0) { "Invalid decoded sample rate: $inputRate" }
        if (inputRate == outputRate) return input
        val outputSize = ((input.size.toLong() * outputRate) / inputRate).coerceAtLeast(1L).toInt()
        val output = FloatArray(outputSize)
        val step = inputRate.toDouble() / outputRate.toDouble()
        for (i in output.indices) {
            val sourcePos = i * step
            val left = floor(sourcePos).toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePos - left).toFloat()
            output[i] = input[left] + (input[right] - input[left]) * fraction
        }
        return output
    }

    private fun writePcm16Wav(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
            val dataBytes = samples.size * 2
            out.writeBytes("RIFF")
            writeLeInt(out, 36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLeInt(out, 16)
            writeLeShort(out, 1)
            writeLeShort(out, 1)
            writeLeInt(out, sampleRate)
            writeLeInt(out, sampleRate * 2)
            writeLeShort(out, 2)
            writeLeShort(out, 16)
            out.writeBytes("data")
            writeLeInt(out, dataBytes)
            samples.forEach { sample ->
                val pcm = (sample.coerceIn(-1.0f, 1.0f) * 32767.0f).roundToInt()
                writeLeShort(out, pcm)
            }
        }
    }

    private fun writeLeShort(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value ushr 8) and 0xff)
    }

    private fun writeLeInt(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value ushr 8) and 0xff)
        out.writeByte((value ushr 16) and 0xff)
        out.writeByte((value ushr 24) and 0xff)
    }

    private fun intValue(format: MediaFormat, key: String, fallback: Int): Int =
        if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(fallback) else fallback

    private class FloatCollector(initialCapacity: Int = 16_384) {
        private var values = FloatArray(initialCapacity)
        private var count = 0

        fun add(value: Float) {
            if (count == values.size) values = values.copyOf(values.size * 2)
            values[count++] = value
        }

        fun toArray(): FloatArray = values.copyOf(count)
    }
}
