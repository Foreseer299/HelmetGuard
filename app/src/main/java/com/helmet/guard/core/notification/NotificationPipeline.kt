package com.helmet.guard.core.notification

import com.helmet.guard.domain.Announcement
import com.helmet.guard.domain.AnnouncementDecision
import com.helmet.guard.domain.AnnouncementEvaluation
import com.helmet.guard.domain.AnnouncementPriority
import com.helmet.guard.domain.AnnouncementProfile
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.domain.ContentDetail
import com.helmet.guard.domain.NotificationEvent
import com.helmet.guard.domain.RiderEventCategory
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Android-free input so parsing and privacy rules can be exhaustively unit-tested. */
data class RawNotificationInput(
    val packageName: String,
    val notificationKey: String,
    val channelId: String?,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val textLines: List<String>,
    val category: String?,
    val importance: Int,
    val postedAtMs: Long,
    val receivedElapsedMs: Long,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean
)

data class NotificationSourceConfig(
    val smsPackages: Set<String> = emptySet(),
    val callPackages: Set<String> = emptySet(),
    val riderPackages: Set<String> = emptySet(),
    val ownPackages: Set<String> = setOf("com.helmet.guard", "com.helmet.guard.debug")
)

object NotificationNormalizer {
    private val whitespace = Regex("\\s+")
    private val orderId = Regex("(?:订单|任务|单号|订单号|任务号)\\s*[：:#号]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{4,31})", RegexOption.IGNORE_CASE)

    fun normalize(raw: RawNotificationInput, config: NotificationSourceConfig): NotificationEvent? {
        if (raw.packageName in config.ownPackages || raw.isGroupSummary) return null
        val title = clean(raw.title.orEmpty())
        val bodyCandidates = buildList {
            raw.bigText?.let(::add)
            raw.text?.let(::add)
            addAll(raw.textLines)
        }.map(::clean).filter { it.isNotBlank() }.distinct()
        val text = bodyCandidates.maxByOrNull { it.length }.orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        val source = when (raw.packageName) {
            in config.smsPackages -> AnnouncementSource.SMS
            in config.callPackages -> AnnouncementSource.CALL
            in config.riderPackages -> AnnouncementSource.RIDER_APP
            else -> AnnouncementSource.UNKNOWN
        }
        val searchable = "$title $text"
        val businessKey = orderId.find(searchable)?.groupValues?.getOrNull(1)?.takeIf { it.length >= 5 }
        return NotificationEvent(
            traceId = UUID.randomUUID().toString(),
            packageName = raw.packageName,
            notificationKey = raw.notificationKey,
            channelId = raw.channelId,
            title = title,
            text = text,
            category = raw.category,
            importance = raw.importance,
            postedAtMs = raw.postedAtMs,
            receivedElapsedMs = raw.receivedElapsedMs,
            isGroupSummary = raw.isGroupSummary,
            isOngoing = raw.isOngoing,
            source = source,
            businessKey = businessKey
        )
    }

    private fun clean(value: String): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\p{Cc}&&[^\\n\\t]]"), " ")
        .replace(whitespace, " ")
        .trim()
}

object AnnouncementPolicy {
    private val url = Regex("(?:https?://|www\\.)\\S+", RegexOption.IGNORE_CASE)
    private val phoneNumber = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
    private val longNumber = Regex("(?<!\\d)\\d{6,}(?!\\d)")
    private val verification = Regex("(?:验证码|校验码|动态码|verification|otp)[^0-9]{0,8}[0-9]{4,8}", RegexOption.IGNORE_CASE)
    private val orderIdentifier = Regex("((?:订单|任务|单号|订单号|任务号)\\s*[：:#号]?\\s*)([A-Za-z0-9][A-Za-z0-9_-]{4,31})", RegexOption.IGNORE_CASE)
    private val riderIncome = Regex("(预计收入|预计可得|配送费|跑腿费|本单收入|奖励|补贴)\\s*[：:]?\\s*(?:[¥￥]\\s*)?(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块钱?)?", RegexOption.IGNORE_CASE)
    private val anyAmount = Regex("(?:[¥￥]\\s*\\d+(?:\\.\\d{1,2})?|(?:人民币|RMB)\\s*\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?\\s*(?:元|块钱?))", RegexOption.IGNORE_CASE)
    private val explicitAddress = Regex("(?:收货地址|配送地址|取货地址|送达地址|地址)\\s*[：:]?\\s*[^，,。;；]{3,60}")
    private val administrativeAddress = Regex("[\\p{IsHan}A-Za-z0-9-]{2,}(?:省|自治区|特别行政区)[^，,。;；]{2,50}|[\\p{IsHan}A-Za-z0-9-]{2,}(?:市|区|县)[^，,。;；]{2,45}(?:路|街|巷|弄|大道|小区|村|栋|单元|室|号楼)[^，,。;；]{0,25}")
    private val roadAddress = Regex("[\\p{IsHan}A-Za-z0-9-]{2,}(?:路|街|巷|弄|大道|小区|村)[^，,。;；]{0,30}(?:\\d+号(?:楼)?|栋|单元|室)?")
    private val promotion = Regex("优惠券|领券|红包|满减|直播|签到|抽奖|砍价|秒杀|拉新|猜你喜欢|促销|特惠|广告")

    private val categories = listOf(
        RiderEventCategory.PICKUP_TIMEOUT to Regex("取餐.{0,8}(超时|即将超时)|到店.{0,8}超时"),
        RiderEventCategory.DELIVERY_TIMEOUT to Regex("配送.{0,8}(超时|即将超时)|送达.{0,8}超时"),
        RiderEventCategory.CANCELLED to Regex("订单.{0,8}(取消|已取消)|客户取消|商家取消"),
        RiderEventCategory.REASSIGNED to Regex("改派|转派|重新指派"),
        RiderEventCategory.ASSIGNED to Regex("已指派|指派给你|分配订单"),
        RiderEventCategory.NEW_ORDER to Regex("新订单|新任务|抢单成功|接单成功"),
        RiderEventCategory.ORDER_CHANGED to Regex("订单.{0,8}(变更|修改)|地址变更|备注变更"),
        RiderEventCategory.IMPORTANT_CONTACT to Regex("客户来电|商家来电|调度|站长联系|顾客联系")
    )

    fun evaluate(event: NotificationEvent, profile: AnnouncementProfile = AnnouncementProfile()): AnnouncementEvaluation {
        if (!profile.enabled) return filtered("PROFILE_DISABLED")
        if (event.source == AnnouncementSource.UNKNOWN) return filtered("SOURCE_NOT_ALLOWED")
        if (event.isOngoing && event.source != AnnouncementSource.CALL) return filtered("ONGOING_NON_CALL")

        val combined = listOf(event.title, event.text).filter { it.isNotBlank() }.joinToString("，")
        if (combined.isBlank()) return filtered("EMPTY")

        // Recognize rider work first. A valid new-order/cancellation/timeout must not disappear just
        // because the body also mentions a coupon or promotion seen by the customer.
        val category = when (event.source) {
            AnnouncementSource.CALL -> RiderEventCategory.INCOMING_CALL
            AnnouncementSource.SMS -> RiderEventCategory.SMS_MESSAGE
            AnnouncementSource.RIDER_APP -> categories.firstOrNull { it.second.containsMatchIn(combined) }?.first
                ?: RiderEventCategory.GENERIC
            AnnouncementSource.UNKNOWN -> RiderEventCategory.GENERIC
        }
        if (category == RiderEventCategory.GENERIC && promotion.containsMatchIn(combined)) return filtered("PROMOTION")
        if (category !in profile.enabledCategories) return filtered("EVENT_DISABLED_${category.name}")
        val priority = when (category) {
            RiderEventCategory.INCOMING_CALL -> AnnouncementPriority.CALL
            RiderEventCategory.NEW_ORDER,
            RiderEventCategory.CANCELLED,
            RiderEventCategory.PICKUP_TIMEOUT,
            RiderEventCategory.DELIVERY_TIMEOUT,
            RiderEventCategory.IMPORTANT_CONTACT -> AnnouncementPriority.IMPORTANT
            else -> AnnouncementPriority.NORMAL
        }
        val sensitive = verification.containsMatchIn(combined)
        if (sensitive && !profile.announceVerificationCodePresence) return filtered("VERIFICATION_CODE_MUTED")

        val redacted = redact(combined, event.source, profile, sensitive)
        val speech = buildSpeech(event, category, redacted, profile, sensitive)
        if (speech.isBlank()) return filtered("NO_SAFE_SPEECH")
        val fingerprint = sha256(
            listOf(event.packageName, event.businessKey.orEmpty(), category.name, speech.lowercase(Locale.ROOT)).joinToString("|")
        )
        val expiry = when (category) {
            RiderEventCategory.NEW_ORDER -> 25_000L
            RiderEventCategory.INCOMING_CALL -> 15_000L
            RiderEventCategory.CANCELLED,
            RiderEventCategory.PICKUP_TIMEOUT,
            RiderEventCategory.DELIVERY_TIMEOUT -> 45_000L
            else -> profile.maxQueueAgeMs
        }
        return AnnouncementEvaluation(
            decision = if (sensitive) AnnouncementDecision.REDACTED_ONLY else AnnouncementDecision.SPEAK,
            reasonCode = if (sensitive) "SENSITIVE_REDACTED" else "ALLOWED",
            announcement = Announcement(
                traceId = event.traceId,
                source = event.source,
                category = category,
                priority = priority,
                speechText = speech,
                businessKey = event.businessKey,
                createdElapsedMs = event.receivedElapsedMs,
                expiresAtElapsedMs = event.receivedElapsedMs + expiry,
                fingerprint = fingerprint,
                sourcePackage = event.packageName
            )
        )
    }

    private fun redact(
        input: String,
        source: AnnouncementSource,
        profile: AnnouncementProfile,
        verificationCode: Boolean
    ): String {
        val preservedIncome = mutableListOf<String>()
        var value = input.replace(url, "链接已省略")
        value = if (verificationCode) verification.replace(value, "验证码内容已隐藏") else value
        value = value.replace(phoneNumber, "电话号码已隐藏")
        value = value.replace(orderIdentifier) { match ->
            val id = match.groupValues[2]
            "订单号尾号${id.takeLast(4)}"
        }
        if (source == AnnouncementSource.RIDER_APP && profile.announceRiderIncome) {
            value = value.replace(riderIncome) { match ->
                val marker = "\uE100${('A'.code + preservedIncome.size).toChar()}\uE101"
                val label = match.groupValues[1]
                val number = match.groupValues[2]
                preservedIncome += "$label${number}元"
                marker
            }
        }
        value = value.replace(anyAmount, "金额已隐藏")
        value = value.replace(longNumber) { match ->
            val raw = match.value
            if (raw.length <= 8) "数字已隐藏" else "编号尾号${raw.takeLast(4)}"
        }
        if (!profile.announceFullAddress) {
            value = value.replace(explicitAddress, "地址详情已隐藏")
                .replace(administrativeAddress, "地址详情已隐藏")
                .replace(roadAddress, "地址详情已隐藏")
        }
        preservedIncome.forEachIndexed { index, income ->
            value = value.replace("\uE100${('A'.code + index).toChar()}\uE101", income)
        }
        return value.replace(Regex("([!！?？。,.，])\\1{2,}"), "$1").trim(' ', '，', ',', '。')
    }

    private fun buildSpeech(
        event: NotificationEvent,
        category: RiderEventCategory,
        redacted: String,
        profile: AnnouncementProfile,
        sensitive: Boolean
    ): String {
        if (sensitive) return "收到一条验证码通知，具体内容已隐藏"
        if (category == RiderEventCategory.INCOMING_CALL) {
            val identity = event.title.takeIf {
                it.isNotBlank() && !it.contains("电话") && !it.contains("来电") && !it.contains(Regex("\\d{5,}"))
            }
            return identity?.let { "${safeSlice(it, 18)}来电" } ?: "有来电"
        }
        val prefix = when (category) {
            RiderEventCategory.NEW_ORDER -> "有新订单"
            RiderEventCategory.ASSIGNED -> "订单已指派"
            RiderEventCategory.REASSIGNED -> "订单已改派"
            RiderEventCategory.CANCELLED -> "订单已取消"
            RiderEventCategory.PICKUP_TIMEOUT -> "取餐即将或已经超时"
            RiderEventCategory.DELIVERY_TIMEOUT -> "配送即将或已经超时"
            RiderEventCategory.ORDER_CHANGED -> "订单信息有变更"
            RiderEventCategory.IMPORTANT_CONTACT -> "有重要联系"
            RiderEventCategory.SMS_MESSAGE -> "收到短信"
            else -> "收到骑手应用通知"
        }
        return when (profile.detail) {
            ContentDetail.HINT_ONLY, ContentDetail.BRIEF -> prefix
            ContentDetail.STANDARD -> {
                val income = if (event.source == AnnouncementSource.RIDER_APP && profile.announceRiderIncome) {
                    riderIncome.find(redacted)?.value
                } else null
                if (income.isNullOrBlank()) prefix else "$prefix，$income"
            }
            ContentDetail.DETAILED, ContentDetail.CUSTOM -> {
                val detail = safeSlice(redacted, minOf(profile.maxCharacters, 90))
                if (detail.isBlank() || detail == prefix) prefix else "$prefix，$detail"
            }
        }
    }

    private fun safeSlice(value: String, max: Int): String = if (value.length <= max) value else value.take(max).trimEnd() + "，后续请查看手机"
    private fun filtered(reason: String) = AnnouncementEvaluation(AnnouncementDecision.FILTERED, reason)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
