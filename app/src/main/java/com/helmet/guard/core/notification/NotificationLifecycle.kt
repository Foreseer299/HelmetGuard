package com.helmet.guard.core.notification

import com.helmet.guard.domain.AnnouncementEvaluation

/** Keeps the accepted trace when the system reposts an unchanged notification. */
class NotificationSubmissionTracker {
    private val accepted = mutableMapOf<String, String>()

    @Synchronized
    fun accept(key: String, evaluation: AnnouncementEvaluation): String? {
        val trace = evaluation.announcement?.traceId ?: return null
        return accepted.put(key, trace)?.takeIf { it != trace }
    }

    @Synchronized fun remove(key: String): String? = accepted.remove(key)
    @Synchronized fun clear(): List<String> = accepted.values.toList().also { accepted.clear() }
}

/** Notifications are only a fallback for ringing when telephony access is unavailable. */
object IncomingCallNotificationPolicy {
    private val endedOrOutgoing = Regex(
        "正在通话|通话中|未接来电|已挂断|通话结束|正在拨号|正在呼叫|呼出|语音信箱|" +
            "missed call|outgoing call|dialling|dialing|calling|ongoing call|call in progress|call ended|voicemail",
        RegexOption.IGNORE_CASE
    )

    fun canDescribeIncomingCall(category: String?, title: String, text: String): Boolean =
        category == "call" && !endedOrOutgoing.containsMatchIn("$title $text")
}
