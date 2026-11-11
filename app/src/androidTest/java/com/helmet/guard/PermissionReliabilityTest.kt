package com.helmet.guard

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class PermissionReliabilityTest {
    @Test fun missingBluetoothPermissionDoesNotCrashReliabilityChecks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Run without nearby devices permission", PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT))
        val checks = context.appGraph.reliability.checks(0, false)
        assertFalse(checks.single { it.key == "bluetooth" }.passed)
        assertFalse(checks.single { it.key == "blePermission" }.passed)
        assertFalse(checks.single { it.key == "sim" }.passed)
    }
}
