package com.helmet.guard

import com.helmet.guard.ui.screens.contactPhoneKey
import com.helmet.guard.ui.screens.normalizedEmergencyPhone
import org.junit.Assert.*
import org.junit.Test

class ContactInputPolicyTest {
    @Test fun acceptsFormattedFullNumbersButRejectsEmergencyShortCodesAndDialCommands() {
        assertEquals("+8613800138000", normalizedEmergencyPhone("+86 (138) 0013-8000"))
        listOf("110", "120", "10086", "13800138000,123", "*123#", "alice", "+1234567890123456").forEach { assertNull(it, normalizedEmergencyPhone(it)) }
    }
    @Test fun duplicateKeyRecognizesChineseMobileCountryPrefixWithoutTruncatingOtherNumbers() {
        assertEquals(contactPhoneKey("13800138000"), contactPhoneKey("+86 138-0013-8000"))
        assertEquals(contactPhoneKey("13800138000"), contactPhoneKey("008613800138000"))
        assertNotEquals(contactPhoneKey("+12025550148"), contactPhoneKey("2025550148"))
    }
}
