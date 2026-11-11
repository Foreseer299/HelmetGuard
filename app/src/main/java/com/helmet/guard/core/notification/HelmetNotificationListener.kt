package com.helmet.guard.core.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.helmet.guard.appGraph
import com.helmet.guard.core.contacts.NotificationIdentityResolver
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * System-managed notification listener. It extracts only transient fields and hands them to the
 * privacy policy; raw notification bodies are never persisted by this service.
 */
class HelmetNotificationListener : NotificationListenerService() {
    private val processing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revisions = ConcurrentHashMap<String, Long>()
    private val sequence = AtomicLong()
    private val submittedCalls = NotificationSubmissionTracker()
    override fun onDestroy() {
        connected.value = false
        synchronized(revisions) {
            revisions.clear()
            submittedCalls.clear().forEach { appGraph.announcements.revoke(it) }
        }
        appGraph.diagnostics.log("listener", "notification_listener", "LIFECYCLE", "DESTROYED")
        processing.cancel(); super.onDestroy()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        synchronized(revisions) {
            sbn?.key?.let { key ->
                // SMS may auto-clear while contact resolution is still running. Its event remains
                // useful; only a removed call invalidates pending identity work immediately.
                if (sbn.notification.category == Notification.CATEGORY_CALL) revisions.remove(key)
                submittedCalls.remove(key)?.let { appGraph.announcements.revoke(it) }
            }
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected(); connected.value = true
        NotificationListenerRecovery.refresh(this)
        appGraph.diagnostics.log("listener", "notification_listener", "LIFECYCLE", "CONNECTED")
    }
    override fun onListenerDisconnected() {
        connected.value = false; super.onListenerDisconnected()
        synchronized(revisions) {
            revisions.clear()
            submittedCalls.clear().forEach { appGraph.announcements.revoke(it) }
        }
        appGraph.diagnostics.log("listener", "notification_listener", "LIFECYCLE", "DISCONNECTED")
        NotificationListenerRecovery.reconnect(this, "DISCONNECT")
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val notification = item.notification ?: return
        val ranking = Ranking()
        val importance = if (currentRanking.getRanking(item.key, ranking)) ranking.importance
        else NotificationManager.IMPORTANCE_DEFAULT
        val extras = notification.extras
        @Suppress("DEPRECATION")
        val latestMessage = runCatching {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES)).lastOrNull()
        }.getOrNull()
        val person = latestMessage?.senderPerson
        val raw = RawNotificationInput(
            packageName = item.packageName,
            notificationKey = item.key,
            channelId = notification.channelId,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = latestMessage?.text?.toString() ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = if (latestMessage == null) extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() else null,
            textLines = if (latestMessage == null) extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty() else emptyList(),
            category = notification.category,
            importance = importance,
            postedAtMs = item.postTime,
            receivedElapsedMs = SystemClock.elapsedRealtime(),
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
        val event = NotificationNormalizer.normalize(raw, sourceConfig()) ?: return
        val revision = sequence.incrementAndGet()
        synchronized(revisions) { revisions[item.key] = revision }
        processing.launch {
        val resolved = NotificationIdentityResolver(this@HelmetNotificationListener).resolve(event, person?.uri, person?.name?.toString())
        val enriched = if (latestMessage == null && raw.textLines.size > 1) resolved.copy(bodyReadable = false) else resolved
        synchronized(revisions) {
        if (revisions[item.key] != revision) return@launch
        if (event.source != com.helmet.guard.domain.AnnouncementSource.CALL) revisions.remove(item.key)
        val settings = appGraph.announcementSettings.state.value
        if (!settings.enabled || !settings.allows(event.source)) {
            appGraph.diagnostics.log(event.traceId, "notification", event.source.name, if (!settings.enabled) "PROFILE_DISABLED" else "SOURCE_NOT_ALLOWED")
            return@launch
        }
        if (event.source == com.helmet.guard.domain.AnnouncementSource.CALL) {
            val phoneAllowed = androidx.core.content.ContextCompat.checkSelfPermission(this@HelmetNotificationListener, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val telephonyActive = phoneAllowed && com.helmet.guard.core.call.MultiSimCallMonitor.isMonitoring
            if (telephonyActive && IncomingCallNotificationPolicy.canDescribeIncomingCall(event.category, event.title, event.text)) {
                appGraph.callSessions.onDialerNotification(enriched)
                appGraph.diagnostics.log(event.traceId, "notification", "DIALER_IDENTITY", "JOIN_PENDING")
            } else if (!telephonyActive && IncomingCallNotificationPolicy.canDescribeIncomingCall(event.category, event.title, event.text)) {
                val result = appGraph.announcements.submit(enriched, settings.profile())
                submittedCalls.accept(item.key, result)?.let { appGraph.announcements.revoke(it) }
                appGraph.diagnostics.log(event.traceId, "notification", "CALL", result.reasonCode)
            } else {
                submittedCalls.remove(item.key)?.let { appGraph.announcements.revoke(it) }
            }
        } else {
            val evaluation = appGraph.announcements.submit(enriched, settings.profile())
            appGraph.diagnostics.log(
                event.traceId,
                "notification",
                event.source.name,
                evaluation.reasonCode,
                metrics = mapOf("category" to evaluation.announcement?.category?.name, "importance" to event.importance, "contentAvailable" to enriched.bodyReadable, "contactMatched" to enriched.senderContactMatched, "hasSender" to (enriched.senderLabel != null))
            )
        }
        }
        }
    }

    private fun sourceConfig(): NotificationSourceConfig {
        val sms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        val dialer = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
        return NotificationSourceConfig(
            smsPackages = setOfNotNull(sms),
            callPackages = setOfNotNull(dialer),
            // Defaults are intentionally conservative. The 2.2 UI will let the user select the
            // actual rider apps installed on the phone; package names differ by platform/region.
            riderPackages = appGraph.announcementSettings.state.value.riderPackages,
            ownPackages = setOf(packageName, "com.helmet.guard", "com.helmet.guard.debug")
        )
    }

    companion object {
        val connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        val DEFAULT_RIDER_PACKAGES = setOf(
            // Known rider-side package only. Consumer shopping/takeaway apps are deliberately absent.
            "com.sankuai.meituan.dispatch.homebrew"
        )

        fun settingsIntent(context: Context) = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
