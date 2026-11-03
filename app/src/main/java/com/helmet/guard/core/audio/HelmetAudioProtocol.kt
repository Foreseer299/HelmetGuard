package com.helmet.guard.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import kotlin.math.ceil

object HelmetAudioProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("8F7A0000-6B1D-4A63-9F2A-4F5E52A21000")
    val CAPABILITIES_UUID: UUID = UUID.fromString("8F7A0001-6B1D-4A63-9F2A-4F5E52A21000")
    val CONTROL_UUID: UUID = UUID.fromString("8F7A0002-6B1D-4A63-9F2A-4F5E52A21000")
    val DATA_UUID: UUID = UUID.fromString("8F7A0003-6B1D-4A63-9F2A-4F5E52A21000")
    val STATUS_UUID: UUID = UUID.fromString("8F7A0004-6B1D-4A63-9F2A-4F5E52A21000")

    const val VERSION: Int = 1
    const val CODEC_IMA_ADPCM_BLOCK_V1: Int = 1
    const val SAMPLE_RATE_HZ: Int = 16_000
    const val BLOCK_SAMPLES: Int = 256
    const val DEFAULT_START_BUFFER_MS: Int = 800
    const val MINIMUM_DYNAMIC_AUDIO_MTU: Int = 185
    const val DATA_FIXED_OVERHEAD_BYTES: Int = 16 // header 14 + trailing CRC16

    const val OP_OPEN_SESSION: Int = 0x01
    const val OP_START_AUDIO: Int = 0x02
    const val OP_ABORT_AUDIO: Int = 0x03
    const val OP_SET_VOLUME: Int = 0x04
    const val OP_PLAY_FIXED_PROMPT: Int = 0x05
    const val OP_QUERY_STATUS: Int = 0x06

    const val STATUS_IDLE: Int = 0
    const val STATUS_OPEN: Int = 1
    const val STATUS_INTEGRITY_ERROR: Int = 2
    const val STATUS_SESSION_MISMATCH: Int = 3
    const val STATUS_SEQUENCE_GAP: Int = 4
    const val STATUS_MALFORMED: Int = 5
    const val STATUS_CANCELLED: Int = 6
    const val STATUS_BUFFERING: Int = 7
    const val STATUS_PLAYING: Int = 8
    const val STATUS_COMPLETED: Int = 9
    const val STATUS_UNDERRUN: Int = 10
    const val STATUS_START_REJECTED: Int = 11
    const val STATUS_EXPIRED: Int = 12

    fun requiredBlocksForBuffer(bufferMs: Int, sampleRate: Int = SAMPLE_RATE_HZ, blockSamples: Int = BLOCK_SAMPLES): Int =
        ceil(bufferMs.toDouble() * sampleRate / 1_000.0 / blockSamples).toInt()

    /** For 800 ms, 16 kHz, 256 samples/block this intentionally returns 50, not 32. */
    fun minimumInitialAuthorizationThrough(firstSequence: Int, bufferMs: Int = DEFAULT_START_BUFFER_MS): Int =
        Sequence16.add(firstSequence, requiredBlocksForBuffer(bufferMs) - 1)

    fun encodeDataPacket(sessionId: Long, audioId: Long, blockSequence: Int, flags: Int, block: ByteArray): ByteArray {
        require(sessionId in 1..0xffff_ffffL && audioId in 1..0xffff_ffffL)
        require(blockSequence in 0..0xffff && flags in 0..0xff)
        require(block.size <= 0xffff)
        val body = ByteBuffer.allocate(14 + block.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION.toByte())
            .put(flags.toByte())
            .putInt(sessionId.toInt())
            .putInt(audioId.toInt())
            .putShort(blockSequence.toShort())
            .putShort(block.size.toShort())
            .put(block)
            .array()
        return body + byteArrayOf(Crc16Ccitt.compute(body).toByte(), (Crc16Ccitt.compute(body) ushr 8).toByte())
    }

    fun decodeDataPacket(bytes: ByteArray): AudioDataPacket? {
        if (bytes.size < DATA_FIXED_OVERHEAD_BYTES) return null
        val expected = (bytes[bytes.lastIndex - 1].toInt() and 0xff) or ((bytes.last().toInt() and 0xff) shl 8)
        val body = bytes.copyOfRange(0, bytes.size - 2)
        if (Crc16Ccitt.compute(body) != expected) return null
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.get().toInt() and 0xff) != VERSION) return null
        val flags = b.get().toInt() and 0xff
        val sessionId = b.int.toLong() and 0xffff_ffffL
        val audioId = b.int.toLong() and 0xffff_ffffL
        val sequence = b.short.toInt() and 0xffff
        val length = b.short.toInt() and 0xffff
        if (sessionId == 0L || audioId == 0L || length != b.remaining()) return null
        return AudioDataPacket(sessionId, audioId, sequence, flags, ByteArray(length).also { b.get(it) })
    }

    fun decodeCapabilities(bytes: ByteArray): AudioCapabilities? {
        if (bytes.size != 18 || !hasValidTrailingCrc(bytes)) return null
        val b = ByteBuffer.wrap(bytes, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.get().toInt() and 0xff) != VERSION) return null
        val codec = b.get().toInt() and 0xff
        val flags = b.get().toInt() and 0xff
        val channels = b.get().toInt() and 0xff
        val sampleRate = b.int
        val blockSamples = b.short.toInt() and 0xffff
        val startupBlocks = b.short.toInt() and 0xffff
        val maxBlockBytes = b.short.toInt() and 0xffff
        val preferredMtu = b.short.toInt() and 0xffff
        return AudioCapabilities(codec, flags and 0x01 != 0, flags and 0x02 != 0, channels, sampleRate, blockSamples, startupBlocks, maxBlockBytes, preferredMtu)
    }

    fun decodeStatus(bytes: ByteArray): AudioStatus? {
        if (bytes.size !in setOf(14, 18) || !hasValidTrailingCrc(bytes)) return null
        val b = ByteBuffer.wrap(bytes, 0, bytes.size - 2).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.get().toInt() and 0xff) != VERSION) return null
        val code = b.get().toInt() and 0xff
        val sessionId = b.int.toLong() and 0xffff_ffffL
        val expectedSequence = b.short.toInt() and 0xffff
        val authorizedThrough = b.short.toInt() and 0xffff
        val outputReady = b.get().toInt() and 0x01 != 0
        b.get() // reserved
        return AudioStatus(code, sessionId, expectedSequence, authorizedThrough, outputReady, if (bytes.size == 18) b.short.toInt() and 0xffff else null, if (bytes.size == 18) b.short.toInt() and 0xffff else null)
    }

    private fun hasValidTrailingCrc(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        val expected = (bytes[bytes.lastIndex - 1].toInt() and 0xff) or ((bytes.last().toInt() and 0xff) shl 8)
        return Crc16Ccitt.compute(bytes, 0, bytes.size - 2) == expected
    }

    fun wholeAudioCrc32(blocks: List<ByteArray>): Long = CRC32().apply { blocks.forEach { update(it, 0, it.size) } }.value

    fun encodeControl(opcode: Int, sessionId: Long, audioId: Long, payload: ByteArray = ByteArray(0)): ByteArray {
        require(opcode in 0..0xff && sessionId in 0..0xffff_ffffL && audioId in 0..0xffff_ffffL && payload.size <= 0xffff)
        val body = ByteBuffer.allocate(12 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(VERSION.toByte()).put(opcode.toByte())
            .putInt(sessionId.toInt()).putInt(audioId.toInt())
            .putShort(payload.size.toShort()).put(payload).array()
        val crc = Crc16Ccitt.compute(body)
        return body + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    fun startPayload(
        totalSamples: Long,
        totalBlocks: Int,
        totalEncodedBytes: Long,
        wholeCrc32: Long,
        startBufferMs: Int = DEFAULT_START_BUFFER_MS,
        expiryAfterAcceptMs: Int = 15_000,
        priority: Int = 20,
        volume: Int = 60
    ): ByteArray {
        require(totalSamples in 1..0xffff_ffffL && totalBlocks in 1..0xffff)
        require(totalEncodedBytes in 1..0xffff_ffffL && wholeCrc32 in 0..0xffff_ffffL)
        return ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .put(CODEC_IMA_ADPCM_BLOCK_V1.toByte()).put(1)
            .putShort(SAMPLE_RATE_HZ.toShort()).putInt(totalSamples.toInt())
            .putShort(totalBlocks.toShort()).putShort(BLOCK_SAMPLES.toShort())
            .putInt(totalEncodedBytes.toInt()).putShort(startBufferMs.coerceIn(100, 5_000).toShort())
            .putShort(expiryAfterAcceptMs.coerceIn(1_000, 60_000).toShort())
            .put(priority.coerceIn(0, 100).toByte()).put(volume.coerceIn(0, 100).toByte())
            .putInt(wholeCrc32.toInt()).array()
    }
}

data class AudioCapabilities(
    val codec: Int,
    val decoderSupported: Boolean,
    val outputReady: Boolean,
    val channels: Int,
    val sampleRateHz: Int,
    val blockSamples: Int,
    val startupBlocks: Int,
    val maximumBlockBytes: Int,
    val preferredMtu: Int
)

data class AudioStatus(
    val code: Int,
    val sessionId: Long,
    val expectedSequence: Int,
    val authorizedThrough: Int,
    val outputReady: Boolean,
    val receivedBlocks: Int? = null,
    val submittedBlocks: Int? = null
)

data class AudioDataPacket(
    val sessionId: Long,
    val audioId: Long,
    val blockSequence: Int,
    val flags: Int,
    val block: ByteArray
)

object Crc16Ccitt {
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var crc = 0xffff
        for (i in offset until offset + length) {
            crc = crc xor ((bytes[i].toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xffff else (crc shl 1) and 0xffff }
        }
        return crc
    }
}

object Sequence16 {
    fun add(sequence: Int, amount: Int): Int = (sequence + amount) and 0xffff
    fun forwardDistance(from: Int, to: Int): Int = (to - from) and 0xffff
    fun isStrictlyAfter(candidate: Int, reference: Int): Boolean {
        val distance = forwardDistance(reference, candidate)
        return distance in 1..0x7fff
    }
    fun isAtOrBefore(candidate: Int, frontier: Int): Boolean = candidate == frontier || isStrictlyAfter(frontier, candidate)
}

/**
 * Sender-side cumulative authorization frontier.
 *
 * The receiver advertises the highest block sequence the sender may transmit, inclusive. A stale
 * or duplicate status can never add credit twice. Blocks sent after a status was generated remain
 * accounted for because nextToSend is never overwritten by a new status.
 */
class CumulativeAuthorizationWindow(firstSequence: Int = 0) {
    var nextToSend: Int = firstSequence and 0xffff
        private set
    var authorizedThrough: Int? = null
        private set

    @Synchronized
    fun updateAuthorizedThrough(candidate: Int) {
        val normalized = candidate and 0xffff
        val current = authorizedThrough
        if (current == null || Sequence16.isStrictlyAfter(normalized, current)) authorizedThrough = normalized
    }

    @Synchronized
    fun canSend(): Boolean = authorizedThrough?.let { Sequence16.isAtOrBefore(nextToSend, it) } == true

    @Synchronized
    fun claimNext(): Int? {
        if (!canSend()) return null
        val result = nextToSend
        nextToSend = Sequence16.add(nextToSend, 1)
        return result
    }

    @Synchronized
    fun reset(firstSequence: Int = 0) {
        nextToSend = firstSequence and 0xffff
        authorizedThrough = null
    }
}

data class AudioSendClaim(val blockIndex: Int, val sequence: Int, val retransmission: Boolean)

/** Independent fresh-send and retransmit cursors; a NACK never rewinds cumulative authorization. */
class ReliableAudioSendCursor(
    private val totalBlocks: Int,
    private val maximumRetriesPerBlock: Int = 3,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    init { require(totalBlocks in 1..0xffff && maximumRetriesPerBlock >= 1) }

    private var authorizedThrough: Int? = null
    private var nextFreshIndex = 0
    private var highestAcknowledgedIndex = 0
    private var retransmitIndex: Int? = null
    private var lastRequestedGapIndex: Int? = null
    private val retries = IntArray(totalBlocks)
    private var lastRetryMs = Long.MIN_VALUE

    @Synchronized
    fun updateAuthorization(candidate: Int) {
        val normalized = candidate and 0xffff
        val current = authorizedThrough
        if (current == null || Sequence16.isStrictlyAfter(normalized, current)) authorizedThrough = normalized
    }

    /** Returns false when a receiver repeatedly requests the same missing block beyond the retry limit. */
    @Synchronized
    fun onStatus(code: Int, expectedSequence: Int): Boolean {
        val expectedIndex = expectedSequence and 0xffff
        if (expectedIndex > highestAcknowledgedIndex && expectedIndex <= nextFreshIndex) {
            highestAcknowledgedIndex = expectedIndex
            lastRequestedGapIndex = null
        }
        val requestsRetransmit = code == HelmetAudioProtocol.STATUS_SEQUENCE_GAP || code == HelmetAudioProtocol.STATUS_UNDERRUN
        if (!requestsRetransmit || expectedIndex >= nextFreshIndex || expectedIndex < highestAcknowledgedIndex) {
            return true
        }
        if (lastRequestedGapIndex == expectedIndex && (retransmitIndex != null || nowMs() - lastRetryMs < 500)) return true
        lastRetryMs = nowMs()
        lastRequestedGapIndex = expectedIndex
        retries[expectedIndex]++
        if (retries[expectedIndex] > maximumRetriesPerBlock) return false
        retransmitIndex = expectedIndex
        return true
    }

    @Synchronized
    fun nextClaim(): AudioSendClaim? {
        retransmitIndex?.let { index ->
            if (index < nextFreshIndex) {
                retransmitIndex = if (index + 1 < nextFreshIndex) index + 1 else null
                return AudioSendClaim(index, index and 0xffff, true)
            }
            retransmitIndex = null
        }
        if (nextFreshIndex >= totalBlocks) return null
        val frontier = authorizedThrough ?: return null
        val sequence = nextFreshIndex and 0xffff
        if (!Sequence16.isAtOrBefore(sequence, frontier)) return null
        return AudioSendClaim(nextFreshIndex, sequence, false).also { nextFreshIndex++ }
    }

    @Synchronized
    fun allFreshBlocksSent(): Boolean = nextFreshIndex == totalBlocks
}
