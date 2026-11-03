package com.helmet.guard

import com.helmet.guard.core.audio.OfflineTtsPolicy
import com.helmet.guard.core.audio.OfflineVoiceDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTtsPolicyTest {
    @Test
    fun `only Chinese non-network voice from system engine is eligible`() {
        assertTrue(
            OfflineTtsPolicy.isEligible(
                OfflineVoiceDescriptor("local-zh", "zh", false, setOf("embeddedTts"), true)
            )
        )
        assertFalse(
            OfflineTtsPolicy.isEligible(
                OfflineVoiceDescriptor("network-zh", "zh", true, emptySet(), true)
            )
        )
        assertFalse(
            OfflineTtsPolicy.isEligible(
                OfflineVoiceDescriptor("hidden-network", "zh", false, setOf("networkTts"), true)
            )
        )
        assertFalse(
            OfflineTtsPolicy.isEligible(
                OfflineVoiceDescriptor("third-party", "zh", false, setOf("embeddedTts"), false)
            )
        )
        assertFalse(
            OfflineTtsPolicy.isEligible(
                OfflineVoiceDescriptor("english", "en", false, setOf("embeddedTts"), true)
            )
        )
    }
}
