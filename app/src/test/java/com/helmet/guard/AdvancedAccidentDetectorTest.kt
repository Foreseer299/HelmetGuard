package com.helmet.guard

import com.helmet.guard.core.detector.AdvancedAccidentDetector
import com.helmet.guard.domain.DetectorStage
import com.helmet.guard.domain.RejectionReason
import com.helmet.guard.domain.TelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedAccidentDetectorTest {
    @Test fun `normal riding does not trigger`() {
        val detector = AdvancedAccidentDetector()
        repeat(220) { i ->
            val result = detector.process(sample(i, ax = if (i % 3 == 0) 0.2f else 0f, az = 1f, gy = 40f))
            assertNull(result.incident)
        }
        assertEquals(DetectorStage.IDLE, detector.currentSnapshot().stage)
    }

    @Test fun `speed bump vertical impulse is rejected`() {
        val detector = AdvancedAccidentDetector()
        repeat(40) { detector.process(sample(it)) }
        var result = detector.process(sample(40, az = 4.5f, gx = 50f, gy = 60f, roll = 4f))
        repeat(26) { result = detector.process(sample(41 + it, roll = 4f)) }
        assertNull(result.incident)
        assertEquals(RejectionReason.SPEED_BUMP_LIKELY, detector.currentSnapshot().lastRejection)
    }

    @Test fun `unattended helmet drop is rejected after stillness`() {
        val detector = AdvancedAccidentDetector()
        repeat(40) { detector.process(sample(it)) }
        detector.process(sample(40, ax = 0.05f, ay = 0.05f, az = 0.08f, gx = 20f))
        detector.process(sample(41, ax = 0.04f, ay = 0.03f, az = 0.07f, gx = 30f))
        detector.process(sample(42, ax = 0.03f, ay = 0.04f, az = 0.06f, gx = 35f))
        detector.process(sample(43, ax = 3.4f, ay = 2.4f, az = 2.5f, gx = 340f, gy = 300f, roll = 62f))
        var result = detector.process(sample(44, roll = 62f))
        repeat(100) { result = detector.process(sample(45 + it, roll = 62f, gx = 0.2f, gy = 0.2f)) }
        assertNull(result.incident)
        assertEquals(RejectionReason.HELMET_DROP_LIKELY, detector.currentSnapshot().lastRejection)
    }

    @Test fun `crash shape with prior riding and post impact stillness triggers`() {
        val detector = AdvancedAccidentDetector()
        repeat(40) { i -> detector.process(sample(i, ax = if (i % 2 == 0) 0.25f else -0.2f, gy = if (i % 2 == 0) 80f else -70f)) }
        detector.process(sample(40, ax = 0.05f, ay = 0.05f, az = 0.10f))
        detector.process(sample(41, ax = 0.06f, ay = 0.04f, az = 0.09f))
        detector.process(sample(42, ax = 5.4f, ay = 3.2f, az = 2.3f, gx = 500f, gy = 380f, roll = 70f, pitch = 45f))
        var incident = detector.process(sample(43, roll = 72f, pitch = 45f)).incident
        repeat(100) { i ->
            val result = detector.process(sample(44 + i, ax = 0f, ay = 0.7f, az = 0.714f, gx = 1f, gy = 1f, roll = 72f, pitch = 45f))
            if (result.incident != null) incident = result.incident
        }
        assertNotNull(incident)
        assertEquals(DetectorStage.COOLDOWN, detector.currentSnapshot().stage)
    }

    @Test fun `large sample gap resets candidate instead of mixing stale data`() {
        val detector = AdvancedAccidentDetector()
        repeat(10) { detector.process(sample(it)) }
        detector.process(sample(10, az = 4f, gx = 300f))
        val afterGap = detector.process(sample(40, roll = 50f))
        assertNull(afterGap.incident)
        assertEquals(RejectionReason.STALE_DATA, detector.currentSnapshot().lastRejection)
    }

    private fun sample(
        index: Int,
        ax: Float = 0f,
        ay: Float = 0f,
        az: Float = 1f,
        gx: Float = 0f,
        gy: Float = 0f,
        gz: Float = 0f,
        roll: Float = 0f,
        pitch: Float = 0f
    ) = TelemetrySample(
        deviceTimestampMs = index * 50L,
        receivedElapsedMs = 1_000L + index * 50L,
        receivedAtMs = 1_700_000_000_000L + index * 50L,
        ax, ay, az, gx, gy, gz, roll, pitch, 0f,
        batteryPercent = 80,
        imuHealthy = true,
        calibrated = true,
        sequence = index and 0xff
    )
}
