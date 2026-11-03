package com.helmet.guard

import com.helmet.guard.core.audio.Crc16Ccitt
import com.helmet.guard.core.audio.CumulativeAuthorizationWindow
import com.helmet.guard.core.audio.HelmetAudioProtocol
import com.helmet.guard.core.audio.ReliableAudioSendCursor
import com.helmet.guard.core.audio.ImaAdpcmBlock
import com.helmet.guard.core.audio.ImaAdpcmCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioProtocolTest {
    @Test
    fun `fixed ADPCM vector decodes exactly the same on Android and firmware`() {
        // This expected output is the lossy ADPCM decode result, not the original PCM input.
        val blockBytes = hex("0000530015000A008090A2D3C4C4D4D5E6F6")
        val expectedDecoded = shortArrayOf(
            0, 2543, 231, 2333, -3400, 5286, -2610, 7439, -6918, 10282, -10530,
            14653, -15818, 21044, -24009, 21044, -24009, 29236, -24009, 29236, -32200
        )
        assertArrayEquals(expectedDecoded, ImaAdpcmCodec.decode(ImaAdpcmBlock.fromByteArray(blockBytes)))

        val original = shortArrayOf(
            0, 1000, -1000, 2000, -2000, 4000, -4000, 8000, -8000, 12000, -12000,
            16000, -16000, 20000, -20000, 24000, -24000, 28000, -28000, 32000, -32000
        )
        assertArrayEquals(blockBytes, ImaAdpcmCodec.encodeBlock(original).toByteArray())
        assertFalse(original.contentEquals(expectedDecoded)) // IMA ADPCM is intentionally lossy.
    }

    @Test
    fun `800ms startup buffer requires fifty blocks and initial frontier can fill it`() {
        assertEquals(50, HelmetAudioProtocol.requiredBlocksForBuffer(800, 16_000, 256))
        val window = CumulativeAuthorizationWindow(firstSequence = 0)
        window.updateAuthorizedThrough(HelmetAudioProtocol.minimumInitialAuthorizationThrough(0, 800))
        repeat(50) { expected -> assertEquals(expected, window.claimNext()) }
        assertNull(window.claimNext())
    }

    @Test
    fun `cumulative frontier ignores stale status and never forgets in-flight sends`() {
        val window = CumulativeAuthorizationWindow(firstSequence = 0)
        window.updateAuthorizedThrough(49)
        repeat(20) { assertNotNull(window.claimNext()) }
        assertEquals(20, window.nextToSend)

        // A delayed old status must neither rewind nextToSend nor grant another 50 blocks.
        window.updateAuthorizedThrough(31)
        assertEquals(20, window.nextToSend)
        assertEquals(49, window.authorizedThrough)

        // A newer cumulative authorization extends the absolute frontier to 79.
        window.updateAuthorizedThrough(79)
        assertEquals(79, window.authorizedThrough)
        repeat(60) { assertNotNull(window.claimNext()) }
        assertEquals(80, window.nextToSend)
        assertFalse(window.canSend())
    }

    @Test
    fun `concurrent claims and duplicate late status never grant a sequence twice`() {
        val window = CumulativeAuthorizationWindow(firstSequence = 0)
        window.updateAuthorizedThrough(999)
        val claimed = ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(9)
        repeat(8) {
            pool.submit {
                while (true) claimed.add(window.claimNext() ?: break)
            }
        }
        pool.submit {
            repeat(1_000) {
                window.updateAuthorizedThrough(if (it % 2 == 0) 999 else 500) // duplicate + stale
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1_000, claimed.size)
        assertEquals((0..999).toSet(), claimed.toSet())
        assertEquals(1_000, window.nextToSend)
    }

    @Test
    fun `cumulative frontier handles uint16 wrap`() {
        val window = CumulativeAuthorizationWindow(firstSequence = 65_520)
        window.updateAuthorizedThrough(15)
        repeat(32) { assertNotNull(window.claimNext()) }
        assertEquals(16, window.nextToSend)
        assertFalse(window.canSend())
        window.updateAuthorizedThrough(31)
        assertTrue(window.canSend())
    }

    @Test
    fun `firmware capabilities and status fixed vectors decode`() {
        assertArrayEquals(
            hex("0101443322110000000000007822"),
            HelmetAudioProtocol.encodeControl(HelmetAudioProtocol.OP_OPEN_SESSION, 0x11223344, 0)
        )
        val oneBlock = hex("0000530015000A008090A2D3C4C4D4D5E6F6")
        val startPayload = HelmetAudioProtocol.startPayload(
            totalSamples = 256,
            totalBlocks = 1,
            totalEncodedBytes = oneBlock.size.toLong(),
            wholeCrc32 = HelmetAudioProtocol.wholeAudioCrc32(listOf(oneBlock))
        )
        assertArrayEquals(hex("0101803E0001000001000001120000002003983A143C42731D38"), startPayload)
        assertArrayEquals(
            hex("010244332211070000001A000101803E0001000001000001120000002003983A143C42731D38AB03"),
            HelmetAudioProtocol.encodeControl(HelmetAudioProtocol.OP_START_AUDIO, 0x11223344, 7, startPayload)
        )
        val capabilities = HelmetAudioProtocol.decodeCapabilities(hex("01010101803E0000000132008800F7004C9A"))!!
        assertTrue(capabilities.decoderSupported)
        assertFalse(capabilities.outputReady)
        assertEquals(16_000, capabilities.sampleRateHz)
        assertEquals(256, capabilities.blockSamples)
        assertEquals(50, capabilities.startupBlocks)
        assertEquals(247, capabilities.preferredMtu)

        val status = HelmetAudioProtocol.decodeStatus(hex("0100443322110300310000008C6E"))!!
        assertEquals(0x11223344, status.sessionId)
        assertEquals(3, status.expectedSequence)
        assertEquals(49, status.authorizedThrough)
        assertFalse(status.outputReady)
    }

    @Test
    fun `audio data packet detects corruption`() {
        val block = hex("0000530015000A008090A2D3C4C4D4D5E6F6")
        val encoded = HelmetAudioProtocol.encodeDataPacket(0x11223344, 7, 3, 0, block)
        val parsed = HelmetAudioProtocol.decodeDataPacket(encoded)
        assertNotNull(parsed)
        assertEquals(0x11223344, parsed!!.sessionId)
        assertEquals(7, parsed.audioId)
        assertEquals(3, parsed.blockSequence)
        assertArrayEquals(block, parsed.block)
        encoded[14] = (encoded[14].toInt() xor 0x01).toByte()
        assertNull(HelmetAudioProtocol.decodeDataPacket(encoded))
    }

    @Test
    fun `sequence gap retransmits without rewinding fresh authorization`() {
        val cursor = ReliableAudioSendCursor(totalBlocks = 60)
        cursor.updateAuthorization(49)
        repeat(50) { expected ->
            val claim = cursor.nextClaim()!!
            assertEquals(expected, claim.blockIndex)
            assertFalse(claim.retransmission)
        }
        assertNull(cursor.nextClaim())
        assertTrue(cursor.onStatus(HelmetAudioProtocol.STATUS_SEQUENCE_GAP, 10))
        repeat(40) { offset ->
            val claim = cursor.nextClaim()!!
            assertEquals(10 + offset, claim.blockIndex)
            assertTrue(claim.retransmission)
        }
        assertNull(cursor.nextClaim())
        cursor.updateAuthorization(59)
        repeat(10) { expected -> assertEquals(50 + expected, cursor.nextClaim()!!.blockIndex) }
        assertTrue(cursor.allFreshBlocksSent())
    }

    @Test
    fun `stale gap after newer acknowledgement does not trigger duplicate retransmit`() {
        val cursor = ReliableAudioSendCursor(totalBlocks = 20)
        cursor.updateAuthorization(19)
        repeat(20) { cursor.nextClaim() }
        assertTrue(cursor.onStatus(HelmetAudioProtocol.STATUS_PLAYING, 15))
        assertTrue(cursor.onStatus(HelmetAudioProtocol.STATUS_SEQUENCE_GAP, 8))
        assertNull(cursor.nextClaim())
    }

    @Test
    fun `CRC16 CCITT check vector`() {
        assertEquals(0x29B1, Crc16Ccitt.compute("123456789".toByteArray()))
    }

    @Test fun repeatedRealGapRetriesAfterCooldownAndEventuallyStops() {
        var now = 0L
        val cursor = ReliableAudioSendCursor(10, 3) { now }
        cursor.updateAuthorization(9)
        repeat(10) { assertNotNull(cursor.nextClaim()) }
        repeat(3) {
            assertTrue(cursor.onStatus(HelmetAudioProtocol.STATUS_SEQUENCE_GAP, 0))
            repeat(10) { assertNotNull(cursor.nextClaim()) }
            assertNull(cursor.nextClaim())
            now += 501
        }
        assertFalse(cursor.onStatus(HelmetAudioProtocol.STATUS_SEQUENCE_GAP, 0))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
