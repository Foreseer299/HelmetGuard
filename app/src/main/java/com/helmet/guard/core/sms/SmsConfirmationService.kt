package com.helmet.guard.core.sms

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.helmet.guard.appGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SmsConfirmationService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connection.value = true
    }

    override fun onInterrupt() { gate.clear() }

    override fun onUnbind(intent: Intent?): Boolean {
        connection.value = false
        gate.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connection.value = false
        gate.clear()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            inspectConfirmation(event)
        } catch (_: RuntimeException) {
            // A window can vanish while the OS is serving its accessibility nodes.
        }
    }

    private fun inspectConfirmation(event: AccessibilityEvent) {
        val opened = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (!gate.shouldInspect(event.windowId, event.eventTime, SystemClock.uptimeMillis(), opened)) return
        val owner = event.packageName?.toString() ?: return
        if (!isTrustedSystemOwner(owner)) return
        val root = rootInActiveWindow ?: return
        val acquired = mutableListOf(root)
        try {
            if (root.windowId != event.windowId || root.packageName?.toString() != owner) return

            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            val nodes = ArrayDeque<AccessibilityNodeInfo>()
            nodes.add(root)
            var hasTitle = false
            val sendButtons = mutableSetOf<AccessibilityNodeInfo>()
            val cancelButtons = mutableSetOf<AccessibilityNodeInfo>()
            var inspected = 0
            while (nodes.isNotEmpty() && inspected++ < 256) {
                val node = nodes.removeFirst()
                if (!node.isVisibleToUser || node.packageName?.toString() != owner) continue
                val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                hasTitle = hasTitle || labels.any { isSmsConfirmationTitle(it, appLabel) }
                val send = labels.any { it.trim() == "发送" || it.trim() == "Send" }
                val cancel = labels.any(::isSmsConfirmationCancel)
                if (node.isEnabled && send != cancel) {
                    // Some ROMs place the label in a non-clickable TextView inside a button.
                    // Only accept a one-child wrapper: never climb into a whole dialog/list.
                    val target = if (node.isClickable) node else node.parent?.also(acquired::add)?.takeIf {
                        it.childCount == 1 && it.isClickable && it.isEnabled && it.isVisibleToUser &&
                            it.windowId == root.windowId && it.packageName?.toString() == owner
                    }
                    if (target != null) {
                        if (send) sendButtons.add(target) else cancelButtons.add(target)
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let {
                    acquired.add(it)
                    nodes.add(it)
                }
            }
            if (nodes.isNotEmpty() || !hasTitle || sendButtons.size != 1 || cancelButtons.size != 1) return
            val clicked = gate.click(event.windowId, event.eventTime, SystemClock.uptimeMillis()) {
                sendButtons.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (clicked) {
                appGraph.diagnostics.log("sms-assist", "sms", "system_confirmation", "clicked", metrics = mapOf("owner" to owner))
            }
        } finally {
            // Android 12 still pools these objects; repeated window events otherwise leak nodes.
            @Suppress("DEPRECATION")
            acquired.forEach { it.recycle() }
        }
    }

    private fun isTrustedSystemOwner(owner: String): Boolean {
        if (owner == packageName) return false
        val info = try {
            packageManager.getApplicationInfo(owner, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        if (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return false
        return packageManager.checkSignatures("android", owner) == PackageManager.SIGNATURE_MATCH ||
            packageManager.checkPermission("android.permission.MODIFY_PHONE_STATE", owner) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val connection = MutableStateFlow(false)
        val connected = connection.asStateFlow()
        internal val gate = SmsConfirmationGate()

        internal fun begin(id: String, clicks: Int) {
            if (connection.value) gate.begin(id, SystemClock.uptimeMillis(), clicks)
        }
    }
}
