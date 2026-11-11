package com.helmet.guard.core.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import java.util.regex.Pattern

/** Uses Android's system-owned chooser; the app never receives unrelated scan results. */
class CompanionPairing(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun request(launchChooser: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return onFailure("系统不支持配套设备关联")
        // Filter by the stable name prefix only. Requiring both name and service UUID hides older
        // ESP32 advertisements that place FFF0 exclusively in the scan response packet.
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("${HelmetProtocol.DEVICE_PREFIX}.*", Pattern.CASE_INSENSITIVE))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        manager.associate(request, object : CompanionDeviceManager.Callback() {
            @Deprecated("Used on Android 8-12")
            override fun onDeviceFound(chooserLauncher: IntentSender) = launchChooser(chooserLauncher)

            override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "关联失败")

            override fun onAssociationPending(intentSender: IntentSender) = launchChooser(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit
        }, null)
    }

    fun associations(): List<String> {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            @Suppress("DEPRECATION") manager.associations
        }
    }
}
