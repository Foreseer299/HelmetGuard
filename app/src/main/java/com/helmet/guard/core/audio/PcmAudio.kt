package com.helmet.guard.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Minimal RIFF/WAVE PCM16 reader used for Android TTS output. */
data class Pcm16Audio(val sampleRateHz: Int, val channels: Int, val samples: ShortArray)

object WavPcmReader {
    fun read(bytes: ByteArray): Pcm16Audio {
        require(bytes.size >= 44 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE") { "Not a RIFF/WAVE file" }
        var offset = 12
        var format: Format? = null
        var data: ByteArray? = null
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = leInt(bytes, offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size) { "Invalid WAVE chunk" }
            val start = offset + 8
            when (id) {
                "fmt " -> {
                    require(size >= 16)
                    val audioFormat = leShort(bytes, start)
                    val channels = leShort(bytes, start + 2)
                    val sampleRate = leInt(bytes, start + 4)
                    val bits = leShort(bytes, start + 14)
                    require(audioFormat == 1) { "Only integer PCM is supported" }
                    require(channels in 1..2) { "Only mono/stereo are supported" }
                    require(bits == 16) { "Only PCM16 is supported" }
                    format = Format(sampleRate, channels)
                }
                "data" -> data = bytes.copyOfRange(start, start + size)
            }
            offset = start + size + (size and 1)
        }
        val f = requireNotNull(format) { "Missing fmt chunk" }
        val pcm = requireNotNull(data) { "Missing data chunk" }
        require(pcm.size % 2 == 0)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return Pcm16Audio(f.sampleRate, f.channels, ShortArray(pcm.size / 2) { buffer.short })
    }

    private data class Format(val sampleRate: Int, val channels: Int)
    private fun ascii(bytes: ByteArray, offset: Int, length: Int) = bytes.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)
    private fun leShort(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun leInt(bytes: ByteArray, offset: Int) = leShort(bytes, offset) or (leShort(bytes, offset + 2) shl 16)
}

object PcmResampler {
    fun toMonoAt(audio: Pcm16Audio, targetRateHz: Int = 16_000): ShortArray {
        require(audio.sampleRateHz > 0 && targetRateHz > 0 && audio.channels in 1..2)
        val mono = if (audio.channels == 1) audio.samples else ShortArray(audio.samples.size / 2) { index ->
            ((audio.samples[index * 2].toInt() + audio.samples[index * 2 + 1].toInt()) / 2).toShort()
        }
        return monoToRate(mono, audio.sampleRateHz, targetRateHz)
    }

    fun monoToRate(mono: ShortArray, sourceRateHz: Int, targetRateHz: Int): ShortArray {
        require(sourceRateHz > 0 && targetRateHz > 0)
        if (sourceRateHz == targetRateHz || mono.size <= 1) return mono
        val outputSize = ((mono.size.toLong() * targetRateHz) / sourceRateHz).toInt().coerceAtLeast(1)
        val ratio = sourceRateHz.toDouble() / targetRateHz
        return ShortArray(outputSize) { outputIndex ->
            val source = outputIndex * ratio
            val left = floor(source).toInt().coerceIn(0, mono.lastIndex)
            val right = (left + 1).coerceAtMost(mono.lastIndex)
            val fraction = source - left
            (mono[left] + (mono[right] - mono[left]) * fraction).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
