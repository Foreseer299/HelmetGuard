package com.helmet.guard.domain

/** A privacy-preserving normalized event produced from a system notification or call state. */
data class NotificationEvent(
    val traceId: String,
    val packageName: String,
    val notificationKey: String,
    val channelId: String?,
    val title: String,
    val text: String,
    val category: String?,
    val importance: Int,
    val postedAtMs: Long,
    val receivedElapsedMs: Long,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val source: AnnouncementSource,
    val businessKey: String? = null
)

enum class AnnouncementSource { SMS, CALL, RIDER_APP, UNKNOWN }

enum class RiderEventCategory {
    NEW_ORDER,
    ASSIGNED,
    REASSIGNED,
    CANCELLED,
    PICKUP_TIMEOUT,
    DELIVERY_TIMEOUT,
    ORDER_CHANGED,
    IMPORTANT_CONTACT,
    SMS_MESSAGE,
    INCOMING_CALL,
    GENERIC
}

enum class AnnouncementPriority(val rank: Int) {
    LOW(10), NORMAL(20), IMPORTANT(30), CALL(40), SAFETY(100)
}

enum class ContentDetail { HINT_ONLY, BRIEF, STANDARD, DETAILED, CUSTOM }

enum class AnnouncementDecision { SPEAK, FILTERED, DUPLICATE, EXPIRED, REDACTED_ONLY }

data class Announcement(
    val traceId: String,
    val source: AnnouncementSource,
    val category: RiderEventCategory,
    val priority: AnnouncementPriority,
    val speechText: String,
    val businessKey: String?,
    val createdElapsedMs: Long,
    val expiresAtElapsedMs: Long,
    val fingerprint: String,
    val sourcePackage: String
)

data class AnnouncementEvaluation(
    val decision: AnnouncementDecision,
    val reasonCode: String,
    val announcement: Announcement? = null
)

data class AnnouncementProfile(
    val enabled: Boolean = true,
    val detail: ContentDetail = ContentDetail.STANDARD,
    val maxCharacters: Int = 48,
    val maxQueueAgeMs: Long = 60_000,
    val allowUnknownSmsBody: Boolean = false,
    val announceVerificationCodePresence: Boolean = true,
    val announceRiderIncome: Boolean = true,
    val announceAmounts: Boolean = false,
    val announceFullAddress: Boolean = false,
    val enabledCategories: Set<RiderEventCategory> = RiderEventCategory.entries.toSet()
)
