package com.helmet.guard.core.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.helmet.guard.core.notification.AnnouncementRepository
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.domain.NotificationEvent
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** API 31+ multi-SIM call-state monitor. Notification content is used only as optional identity. */
class MultiSimCallMonitor(
    private val context: Context,
    private val onState: (subscriptionId: Int, state: Int, elapsedMs: Long) -> Unit
) {
    private val callbacks = mutableMapOf<Int, Pair<TelephonyManager, CallCallback>>()
    private val executor = ContextCompat.getMainExecutor(context)

    @Synchronized
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            stop()
            return false
        }
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java) ?: return false
        val base = context.getSystemService(TelephonyManager::class.java) ?: return false
        val subscriptions = runCatching { subscriptionManager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val ids = subscriptions.map { it.subscriptionId }.ifEmpty { listOf(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) }
        val activeIds = ids.toSet()
        (callbacks.keys - activeIds).forEach { id ->
            callbacks.remove(id)?.let { (manager, callback) ->
                runCatching { manager.unregisterTelephonyCallback(callback) }
                onState(id, TelephonyManager.CALL_STATE_IDLE, SystemClock.elapsedRealtime())
            }
        }
        activeIds.filterNot { it in callbacks }.forEach { id ->
            val manager = if (id == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID) base else base.createForSubscriptionId(id)
            val callback = CallCallback(id)
            runCatching { manager.registerTelephonyCallback(executor, callback) }
                .onSuccess { callbacks[id] = manager to callback }
        }
        isMonitoring = callbacks.isNotEmpty()
        return isMonitoring
    }

    @Synchronized
    fun stop() {
        callbacks.values.forEach { (manager, callback) -> runCatching { manager.unregisterTelephonyCallback(callback) } }
        callbacks.clear()
        isMonitoring = false
    }

    companion object {
        @Volatile var isMonitoring: Boolean = false
            private set
    }

    private inner class CallCallback(private val subscriptionId: Int) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            synchronized(this@MultiSimCallMonitor) {
                if (callbacks[subscriptionId]?.second !== this) return
                onState(subscriptionId, state, SystemClock.elapsedRealtime())
            }
        }
    }
}

/**
 * Joins Telephony lifecycle with dialer notification identity. A short delay gives the dialer
 * notification time to arrive; if it does not, the safe text is simply “有来电”.
 */
class CallSessionCoordinator(private val announcements: AnnouncementRepository,
    private val profile: () -> com.helmet.guard.domain.AnnouncementProfile = { com.helmet.guard.domain.AnnouncementProfile() }) {
    private data class Session(
        val subscriptionId: Int,
        val traceId: String,
        val startedElapsedMs: Long,
        var identity: String? = null,
        var emitted: Boolean = false,
        var emitJob: Job? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = linkedMapOf<Int, Session>()
    private var pendingIdentity: NotificationEvent? = null
    @Synchronized fun clear() { sessions.values.forEach { it.emitJob?.cancel(); announcements.revoke(it.traceId) }; sessions.clear(); pendingIdentity = null }

    @Synchronized
    fun onState(subscriptionId: Int, state: Int, elapsedMs: Long) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val existing = sessions[subscriptionId]
                if (existing != null) return
                val session = Session(subscriptionId, UUID.randomUUID().toString(), elapsedMs)
                sessions[subscriptionId] = session
                if (sessions.size > 1) sessions.values.forEach { it.identity = null }
                else pendingIdentity?.takeIf { elapsedMs - it.receivedElapsedMs in 0..5000 }?.let { session.identity = extractIdentity(it) }
                pendingIdentity = null
                session.emitJob = scope.launch {
                    delay(700)
                    emitIfActive(subscriptionId)
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK,
            TelephonyManager.CALL_STATE_IDLE -> sessions.remove(subscriptionId)?.let { it.emitJob?.cancel(); announcements.revoke(it.traceId) }
        }
    }

    @Synchronized
    fun onDialerNotification(event: NotificationEvent) {
        if (!com.helmet.guard.core.notification.IncomingCallNotificationPolicy.canDescribeIncomingCall(event.category, event.title, event.text)) return
        if (sessions.isEmpty()) { pendingIdentity = event; return }
        val candidates = sessions.values.filter { event.receivedElapsedMs - it.startedElapsedMs in -1_000L..5_000L }
        if (candidates.size != 1) { candidates.forEach { it.identity = null }; return }
        val session = candidates.single()
        if (event.receivedElapsedMs - session.startedElapsedMs !in -1_000L..5_000L) return
        session.identity = extractIdentity(event)
    }

    @Synchronized
    private fun emitIfActive(subscriptionId: Int) {
        val session = sessions[subscriptionId] ?: return
        if (session.emitted) return
        session.emitted = true
        val now = android.os.SystemClock.elapsedRealtime()
        announcements.submit(
            NotificationEvent(
                traceId = session.traceId,
                packageName = "system.telephony.subscription.$subscriptionId",
                notificationKey = "call:${session.traceId}",
                channelId = null,
                title = session.identity.orEmpty(),
                text = "来电",
                category = android.app.Notification.CATEGORY_CALL,
                importance = android.app.NotificationManager.IMPORTANCE_HIGH,
                postedAtMs = System.currentTimeMillis(),
                receivedElapsedMs = now,
                isGroupSummary = false,
                isOngoing = false,
                source = AnnouncementSource.CALL,
                senderLabel = session.identity
            ), profile()
        )
    }

    private fun extractIdentity(event: NotificationEvent): String? {
        event.senderLabel?.let { return it }
        val candidates = listOf(event.title, event.text)
        return candidates.firstOrNull { text ->
            text.isNotBlank() && text.length <= 40 && !text.contains("正在来电") && !text.equals("来电", true)
        }?.replace(Regex("(正在)?来电|电话"), "")?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains(Regex("\\d{5,}")) }
    }
}
