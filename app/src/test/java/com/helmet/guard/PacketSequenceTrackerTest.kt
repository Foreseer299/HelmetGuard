package com.helmet.guard

import com.helmet.guard.core.ble.PacketSequenceTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketSequenceTrackerTest {
    @Test fun `interleaved event and telemetry completion heals suspected gap`() {
        val tracker = PacketSequenceTracker()
        assertEquals(0L, tracker.accept(99))
        assertEquals(1L, tracker.accept(101))
        assertEquals(0L, tracker.accept(100))
        assertEquals(0L, tracker.accept(101))
        assertEquals(0L, tracker.accept(102))
    }

    @Test fun `wraparound and repeated frame do not inflate loss`() {
        val tracker = PacketSequenceTracker()
        listOf(254, 255, 0, 0, 1).forEach { assertEquals(0L, tracker.accept(it)) }
        assertEquals(1L, tracker.accept(3))
        assertEquals(0L, tracker.accept(2))
    }

    @Test fun `reconnection resets sequence baseline without counting a large gap`() {
        val tracker = PacketSequenceTracker()
        tracker.accept(10)
        assertEquals(2L, tracker.accept(13))
        tracker.resetConnection()
        assertEquals(2L, tracker.accept(77))
        assertEquals(2L, tracker.accept(78))
    }
}
