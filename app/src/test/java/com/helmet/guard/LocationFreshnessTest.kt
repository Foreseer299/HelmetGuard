package com.helmet.guard

import com.helmet.guard.core.location.locationAgeMs
import org.junit.Assert.*
import org.junit.Test

class LocationFreshnessTest {
    @Test fun changedWallClockDoesNotMakeAnOldFixNew() {
        assertEquals(600_000L, locationAgeMs(1_000_000_000, 601_000_000_000, 1_000, 1_000))
        assertEquals(1_000L, locationAgeMs(1_000_000_000, 2_000_000_000, 100_000, 1))
    }
    @Test fun absentMonotonicTimestampUsesBoundedWallAgeAndRejectsFutureFixes() {
        assertEquals(5_000L, locationAgeMs(0, 10, 1_000, 6_000))
        assertNull(locationAgeMs(20, 10, 1, 2))
        assertNull(locationAgeMs(0, 10, 3, 2))
    }
}
