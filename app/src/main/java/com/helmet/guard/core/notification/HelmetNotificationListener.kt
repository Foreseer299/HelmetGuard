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

/**
 * System-managed notification listener. It extracts only transient fields and hands them to the
 * privacy policy; raw notification bodies are never persisted by this service.
 */
class HelmetNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); connected.value = true }
    override fun onListenerDisconnected() { connected.value = false; super.onListenerDisconnected() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val notification = item.notification ?: return
        val ranking = Ranking()
        val importance = if (currentRanking.getRanking(item.key, ranking)) ranking.importance
        else NotificationManager.IMPORTANCE_DEFAULT
        val extras = notification.extras
        val raw = RawNotificationInput(
            packageName = item.packageName,
            notificationKey = item.key,
            channelId = notification.channelId,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
            category = notification.category,
            importance = importance,
            postedAtMs = item.postTime,
            receivedElapsedMs = SystemClock.elapsedRealtime(),
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        )
        val event = NotificationNormalizer.normalize(raw, sourceConfig()) ?: return
        val settings = appGraph.announcementSettings.state.value
        if (!settings.enabled || !settings.allows(event.source)) return
        if (event.source == com.helmet.guard.domain.AnnouncementSource.CALL) {
            appGraph.callSessions.onDialerNotification(event)
            appGraph.diagnostics.log(event.traceId, "notification", "DIALER_IDENTITY", "JOIN_PENDING")
        } else {
            val evaluation = appGraph.announcements.submit(event, settings.profile())
            appGraph.diagnostics.log(
                event.traceId,
                "notification",
                event.source.name,
                evaluation.reasonCode,
                metrics = mapOf("category" to evaluation.announcement?.category?.name, "importance" to event.importance)
            )
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
