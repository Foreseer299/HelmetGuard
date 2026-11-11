package com.helmet.guard

import com.helmet.guard.domain.ConnectionPhase
import com.helmet.guard.domain.TelemetrySample
import com.helmet.guard.ui.screens.freshDeviceSample
import org.junit.Assert.*
import org.junit.Test

class DeviceFreshnessTest {
    private val sample = TelemetrySample.STATIC.copy(receivedElapsedMs = 10_000)
    @Test fun cachedTelemetryCannotPassDisconnectedOrStaleHealthCheck() {
        assertNull(freshDeviceSample(ConnectionPhase.DISCONNECTED, 10_000, sample, 10_500))
        assertNull(freshDeviceSample(ConnectionPhase.STALE, 10_000, sample, 10_500))
        assertNull(freshDeviceSample(ConnectionPhase.READY, 10_000, sample, 15_001))
        assertNull(freshDeviceSample(ConnectionPhase.READY, 0, sample, 10_500))
        assertNull(freshDeviceSample(ConnectionPhase.READY, 14_000, sample, 14_500))
        assertEquals(sample, freshDeviceSample(ConnectionPhase.READY, 10_000, sample, 10_500))
    }
}
