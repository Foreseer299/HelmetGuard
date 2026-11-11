package com.helmet.guard

import com.helmet.guard.core.sms.SmsDeliveryStatus
import com.helmet.guard.core.sms.classifySmsDelivery
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsDeliveryStatusTest {
    @Test fun gsmReportDistinguishesRetryAndFailureFromDelivery() {
        assertEquals(SmsDeliveryStatus.DELIVERED, classifySmsDelivery("3gpp", 0))
        assertEquals(SmsDeliveryStatus.PENDING, classifySmsDelivery("3gpp", 0x20))
        assertEquals(SmsDeliveryStatus.FAILED, classifySmsDelivery("3gpp", 0x40))
        assertEquals(SmsDeliveryStatus.FAILED, classifySmsDelivery("3gpp", 0x60))
        assertEquals(SmsDeliveryStatus.UNKNOWN, classifySmsDelivery("3gpp", 1))
        assertEquals(SmsDeliveryStatus.UNKNOWN, classifySmsDelivery("3gpp", -1))
    }

    @Test fun cdmaReportUsesShiftedErrorClassAndRequiresDeliveredStatus() {
        assertEquals(SmsDeliveryStatus.DELIVERED, classifySmsDelivery("3gpp2", 2 shl 16))
        assertEquals(SmsDeliveryStatus.UNKNOWN, classifySmsDelivery("3gpp2", 0))
        assertEquals(SmsDeliveryStatus.PENDING, classifySmsDelivery("3gpp2", 0x204 shl 16))
        assertEquals(SmsDeliveryStatus.FAILED, classifySmsDelivery("3gpp2", 0x30d shl 16))
        assertEquals(SmsDeliveryStatus.UNKNOWN, classifySmsDelivery("unknown", 0))
    }
}
