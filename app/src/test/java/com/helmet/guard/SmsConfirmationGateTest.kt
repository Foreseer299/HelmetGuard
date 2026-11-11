package com.helmet.guard

import com.helmet.guard.core.sms.SmsConfirmationGate
import com.helmet.guard.core.sms.isSmsConfirmationCancel
import com.helmet.guard.core.sms.isSmsConfirmationTitle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsConfirmationGateTest {
    @Test fun confirmationMatchesOurSmsTitleAndRejectsOtherPrompts() {
        assertTrue(isSmsConfirmationTitle("“HelmetGuard 骑行守护”将发短信", "HelmetGuard 骑行守护"))
        assertTrue(isSmsConfirmationTitle("HelmetGuard wants to send an SMS", "HelmetGuard"))
        assertFalse(isSmsConfirmationTitle("“其他应用”将发短信", "HelmetGuard"))
        assertFalse(isSmsConfirmationTitle("“HelmetGuard”试图发送短信，同意发送请进行身份验证。", "HelmetGuard"))
        assertFalse(isSmsConfirmationTitle("允许 HelmetGuard 发送短信吗？", "HelmetGuard"))
        assertTrue(isSmsConfirmationCancel("取消（9）"))
        assertFalse(isSmsConfirmationCancel("始终允许"))
    }

    @Test fun clickRequiresFreshWindowAndIsBoundedByExpiryAndRequestCount() {
        val gate = SmsConfirmationGate()
        gate.begin("incident:1", 1_000, 2)
        assertFalse(gate.shouldInspect(1, 999, 1_001, opened = true))
        assertFalse(gate.shouldInspect(1, 1_001, 1_002, opened = false))
        assertTrue(gate.shouldInspect(1, 1_002, 1_003, opened = true))
        assertTrue(gate.click(1, 1_002, 1_004) { true })
        assertFalse(gate.click(1, 1_005, 1_005) { error("Duplicate click") })
        assertTrue(gate.shouldInspect(2, 1_010, 1_010, opened = true))
        assertTrue(gate.click(2, 1_010, 1_010) { true })
        assertFalse(gate.shouldInspect(3, 1_011, 1_011, opened = true))

        gate.end("incident:1")
        gate.begin("incident:2", 2_000, 1)
        assertFalse(gate.shouldInspect(4, 17_000, 17_000, opened = true))
    }

    @Test fun manualTestAndOverlappingDispatchNeverAuthorizeClicks() {
        val gate = SmsConfirmationGate()
        gate.begin("manual-test", 1_000, 0)
        assertFalse(gate.shouldInspect(3, 1_010, 1_010, opened = true))
        gate.begin("incident:2", 1_020, 2)
        gate.end("incident:2")
        gate.begin("incident:3", 17_000, 1)
        assertFalse(gate.shouldInspect(4, 17_010, 17_010, opened = true))
        gate.end("manual-test")
        assertFalse(gate.shouldInspect(4, 17_020, 17_020, opened = true))
        gate.end("incident:3")
        assertFalse(gate.click(4, 17_020, 17_030) { error("Ended attempt") })
    }
}
