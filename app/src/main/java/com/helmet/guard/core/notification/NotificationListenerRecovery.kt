package com.helmet.guard.core.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.helmet.guard.appGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ListenerRecoveryState(val accessGranted: Boolean = false, val result: String = "NOT_CHECKED", val requestedElapsedMs: Long = 0)

/** Requests only the user's already-authorized listener; never changes permission/components. */
object NotificationListenerRecovery {
    private val mutable = MutableStateFlow(ListenerRecoveryState())
    val state = mutable.asStateFlow()
    private var lastAttempt = -30_000L
    fun hasAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(
            ComponentName(context, HelmetNotificationListener::class.java))
    }.getOrDefault(false)

    @Synchronized fun refresh(context: Context) {
        mutable.value = mutable.value.copy(accessGranted = hasAccess(context))
    }

    @Synchronized fun reconnect(context: Context, reason: String, manual: Boolean = false): String {
        val allowed = hasAccess(context)
        val now = SystemClock.elapsedRealtime()
        val result = when {
            !allowed -> "ACCESS_NOT_GRANTED"
            HelmetNotificationListener.connected.value -> "ALREADY_CONNECTED"
            now - lastAttempt < (if (manual) 1_000L else 15_000L) -> "THROTTLED"
            else -> {
                lastAttempt = now
                runCatching {
                    NotificationListenerService.requestRebind(ComponentName(context, HelmetNotificationListener::class.java))
                    "REBIND_REQUESTED"
                }.getOrDefault("REBIND_FAILED")
            }
        }
        val previous = mutable.value
        mutable.value = ListenerRecoveryState(allowed, result,
            if (result == "REBIND_REQUESTED") now else previous.requestedElapsedMs)
        if (result == "REBIND_REQUESTED" || result == "REBIND_FAILED" || (!allowed && previous.result != result)) {
            context.appGraph.diagnostics.log("listener", "notification_listener", reason, result,
                metrics = mapOf("accessGranted" to allowed, "connected" to HelmetNotificationListener.connected.value))
        }
        return result
    }
}
