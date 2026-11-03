package com.helmet.guard.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** IMA ADPCM v1: independent mono blocks, low nibble first, little-endian header. */
data class ImaAdpcmBlock(
    val predictor: Short,
    val stepIndex: Int,
    val validSamples: Int,
    val encoded: ByteArray
) {
    fun toByteArray(): ByteArray = ByteBuffer.allocate(8 + encoded.size).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(predictor)
        .put(stepIndex.coerceIn(0, 88).toByte())
        .put(0)
        .putShort(validSamples.toShort())
        .putShort(encoded.size.toShort())
        .put(encoded)
        .array()

    companion object {
        fun fromByteArray(bytes: ByteArray): ImaAdpcmBlock {
            require(bytes.size >= 8) { "ADPCM block header is truncated" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val predictor = b.short
            val index = b.get().toInt() and 0xff
            require(index in 0..88) { "Invalid IMA step index" }
            require((b.get().toInt() and 0xff) == 0) { "Reserved byte must be zero" }
            val samples = b.short.toInt() and 0xffff
            val encodedBytes = b.short.toInt() and 0xffff
            require(samples in 1..256) { "Invalid sample count" }
            require(encodedBytes == (samples - 1 + 1) / 2) { "Encoded byte count does not match samples" }
            require(bytes.size == 8 + encodedBytes) { "ADPCM block length mismatch" }
            return ImaAdpcmBlock(predictor, index, samples, ByteArray(encodedBytes).also { b.get(it) })
        }
    }
}

object ImaAdpcmCodec {
    const val MAX_BLOCK_SAMPLES = 256

    fun encode(samples: ShortArray, maximumBlockSamples: Int = MAX_BLOCK_SAMPLES): List<ImaAdpcmBlock> {
        require(maximumBlockSamples in 1..MAX_BLOCK_SAMPLES)
        if (samples.isEmpty()) return emptyList()
        return buildList {
            var offset = 0
            while (offset < samples.size) {
                val count = minOf(maximumBlockSamples, samples.size - offset)
                add(encodeBlock(samples, offset, count))
                offset += count
            }
        }
    }

    fun encodeBlock(samples: ShortArray, offset: Int = 0, count: Int = samples.size - offset): ImaAdpcmBlock {
        require(offset >= 0 && count in 1..MAX_BLOCK_SAMPLES && offset + count <= samples.size)
        val first = samples[offset]
        var predictor = first.toInt()
        var stepIndex = chooseInitialIndex(samples, offset, count)
        val initialIndex = stepIndex
        val output = ByteArray((count - 1 + 1) / 2)
        for (i in 1 until count) {
            val codeAndState = encodeNibble(samples[offset + i].toInt(), predictor, stepIndex)
            val code = codeAndState[0]
            predictor = codeAndState[1]
            stepIndex = codeAndState[2]
            val byteIndex = (i - 1) / 2
            if ((i - 1) and 1 == 0) output[byteIndex] = code.toByte()
            else output[byteIndex] = (output[byteIndex].toInt() or (code shl 4)).toByte()
        }
        return ImaAdpcmBlock(first, initialIndex, count, output)
    }

    fun decode(block: ImaAdpcmBlock): ShortArray {
        require(block.stepIndex in 0..88)
        require(block.validSamples in 1..MAX_BLOCK_SAMPLES)
        require(block.encoded.size == (block.validSamples - 1 + 1) / 2)
        val result = ShortArray(block.validSamples)
        var predictor = block.predictor.toInt()
        var index = block.stepIndex
        result[0] = predictor.toShort()
        for (sampleIndex in 1 until block.validSamples) {
            val packed = block.encoded[(sampleIndex - 1) / 2].toInt() and 0xff
            val code = if ((sampleIndex - 1) and 1 == 0) packed and 0x0f else (packed ushr 4) and 0x0f
            val state = decodeNibble(code, predictor, index)
            predictor = state[0]
            index = state[1]
            result[sampleIndex] = predictor.toShort()
        }
        return result
    }

    private fun chooseInitialIndex(samples: ShortArray, offset: Int, count: Int): Int {
        if (count <= 1) return 0
        val inspected = minOf(count - 1, 16)
        var total = 0L
        for (i in 1..inspected) total += abs(samples[offset + i].toInt() - samples[offset + i - 1].toInt())
        val average = (total / inspected).toInt().coerceAtLeast(1)
        var best = 0
        var error = Int.MAX_VALUE
        STEP_TABLE.forEachIndexed { index, step ->
            val candidate = abs(step - average)
            if (candidate < error) { error = candidate; best = index }
        }
        return best
    }

    private fun encodeNibble(sample: Int, oldPredictor: Int, oldIndex: Int): IntArray {
        val step = STEP_TABLE[oldIndex]
        var delta = sample - oldPredictor
        var code = 0
        if (delta < 0) { code = 8; delta = -delta }
        var difference = step shr 3
        if (delta >= step) { code = code or 4; delta -= step; difference += step }
        val half = step shr 1
        if (delta >= half) { code = code or 2; delta -= half; difference += half }
        val quarter = step shr 2
        if (delta >= quarter) { code = code or 1; difference += quarter }
        val predictor = (if (code and 8 != 0) oldPredictor - difference else oldPredictor + difference).coerceIn(-32768, 32767)
        val index = (oldIndex + INDEX_TABLE[code]).coerceIn(0, 88)
        return intArrayOf(code, predictor, index)
    }

    private fun decodeNibble(code: Int, oldPredictor: Int, oldIndex: Int): IntArray {
        val step = STEP_TABLE[oldIndex]
        var difference = step shr 3
        if (code and 4 != 0) difference += step
        if (code and 2 != 0) difference += step shr 1
        if (code and 1 != 0) difference += step shr 2
        val predictor = (if (code and 8 != 0) oldPredictor - difference else oldPredictor + difference).coerceIn(-32768, 32767)
        val index = (oldIndex + INDEX_TABLE[code]).coerceIn(0, 88)
        return intArrayOf(predictor, index)
    }

    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
        598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
        7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
        22385, 24623, 27086, 29794, 32767
    )
}
