package com.helmet.guard

import com.helmet.guard.core.notification.AnnouncementPolicy
import com.helmet.guard.core.notification.AnnouncementScheduler
import com.helmet.guard.core.notification.NotificationNormalizer
import com.helmet.guard.core.notification.NotificationSourceConfig
import com.helmet.guard.core.notification.RawNotificationInput
import com.helmet.guard.domain.AnnouncementDecision
import com.helmet.guard.domain.AnnouncementProfile
import com.helmet.guard.domain.AnnouncementSource
import com.helmet.guard.domain.ContentDetail
import com.helmet.guard.domain.RiderEventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPipelineTest {
    private val config = NotificationSourceConfig(
        smsPackages = setOf("sms"),
        callPackages = setOf("dialer"),
        riderPackages = setOf("rider")
    )

    @Test
    fun `different reliable order ids are never merged as duplicates`() {
        val one = normalize("新订单", "订单号 A12345，请及时处理", 1_000)
        val two = normalize("新订单", "订单号 B98765，请及时处理", 1_100)
        assertEquals("A12345", one.businessKey)
        assertEquals("B98765", two.businessKey)
        val a = AnnouncementPolicy.evaluate(one).announcement!!
        val b = AnnouncementPolicy.evaluate(two).announcement!!
        assertNotEquals(a.fingerprint, b.fingerprint)
        val scheduler = AnnouncementScheduler()
        assertEquals(AnnouncementDecision.SPEAK, scheduler.enqueue(a, 1_000).decision)
        assertEquals(AnnouncementDecision.SPEAK, scheduler.enqueue(b, 1_100).decision)
    }

    @Test
    fun `notification key alone does not become business key`() {
        val event = NotificationNormalizer.normalize(raw("same-key", "新订单", "请查看订单", 2_000), config)!!
        assertNull(event.businessKey)
    }

    @Test
    fun `verification digits are never spoken`() {
        val event = NotificationNormalizer.normalize(
            raw("otp", "短信", "验证码 839204，五分钟内有效", 3_000, packageName = "sms"), config
        )!!
        val result = AnnouncementPolicy.evaluate(event)
        assertEquals(AnnouncementDecision.REDACTED_ONLY, result.decision)
        assertEquals(RiderEventCategory.SMS_MESSAGE, result.announcement!!.category)
        assertFalse(result.announcement!!.speechText.contains("839204"))
        assertTrue(result.announcement!!.speechText.contains("已隐藏"))
    }

    @Test
    fun `hidden caller identity results in safe generic incoming call`() {
        val event = NotificationNormalizer.normalize(
            raw("call", "正在来电", "来电", 4_000, packageName = "dialer"), config
        )!!
        val result = AnnouncementPolicy.evaluate(event)
        assertEquals(AnnouncementSource.CALL, event.source)
        assertEquals("有来电", result.announcement!!.speechText)
    }

    @Test
    fun `raw caller number is not spoken as identity`() {
        val event = NotificationNormalizer.normalize(
            raw("call-number", "13800138000", "正在来电", 4_100, packageName = "dialer"), config
        )!!
        assertEquals("有来电", AnnouncementPolicy.evaluate(event).announcement!!.speechText)
    }

    @Test
    fun `individual rider event can be disabled`() {
        val event = normalize("订单已取消", "订单号 A12345", 4_500)
        val profile = AnnouncementProfile(
            enabledCategories = RiderEventCategory.entries.toSet() - RiderEventCategory.CANCELLED
        )
        val result = AnnouncementPolicy.evaluate(event, profile)
        assertEquals(AnnouncementDecision.FILTERED, result.decision)
        assertEquals("EVENT_DISABLED_CANCELLED", result.reasonCode)
    }

    @Test
    fun `promotion is filtered only after rider business classification`() {
        val promotionOnly = normalize("限时优惠券", "直播间红包，立即领取", 5_000)
        assertEquals("PROMOTION", AnnouncementPolicy.evaluate(promotionOnly).reasonCode)

        val realOrder = normalize("新订单", "订单号 A12345，使用优惠券立减", 5_100)
        val result = AnnouncementPolicy.evaluate(realOrder)
        assertEquals(AnnouncementDecision.SPEAK, result.decision)
        assertEquals(RiderEventCategory.NEW_ORDER, result.announcement!!.category)
    }

    @Test
    fun `rider income is spoken by default but customer money is hidden`() {
        val income = normalize("新订单", "订单号 A12345，预计收入 ¥12.50", 5_200)
        assertTrue(AnnouncementPolicy.evaluate(income).announcement!!.speechText.contains("预计收入12.50元"))

        val cash = normalize("新订单", "订单号 A12345，代收货款18.50元", 5_300)
        val detailed = AnnouncementPolicy.evaluate(cash, AnnouncementProfile(detail = ContentDetail.DETAILED, maxCharacters = 90))
        assertFalse(detailed.announcement!!.speechText.contains("18.50"))
        assertTrue(detailed.announcement!!.speechText.contains("金额已隐藏"))

        val mutedIncome = AnnouncementPolicy.evaluate(
            income,
            AnnouncementProfile(detail = ContentDetail.DETAILED, maxCharacters = 90, announceRiderIncome = false)
        )
        assertFalse(mutedIncome.announcement!!.speechText.contains("12.50"))
    }

    @Test
    fun `detailed mode hides address phone and full order id`() {
        val event = normalize(
            "新订单",
            "订单号 A12345，配送地址：北京市朝阳区建国路88号1栋，联系13800138000",
            5_400
        )
        val speech = AnnouncementPolicy.evaluate(
            event,
            AnnouncementProfile(detail = ContentDetail.DETAILED, maxCharacters = 90)
        ).announcement!!.speechText
        assertFalse(speech.contains("A12345"))
        assertFalse(speech.contains("建国路"))
        assertFalse(speech.contains("13800138000"))
        assertTrue(speech.contains("地址详情已隐藏"))
        assertTrue(speech.contains("电话号码已隐藏"))
    }

    @Test
    fun `same reliable order keeps only newest pending state`() {
        val scheduler = AnnouncementScheduler()
        val accepted = AnnouncementPolicy.evaluate(normalize("新订单", "订单号 A12345", 5_500)).announcement!!
        val cancelled = AnnouncementPolicy.evaluate(normalize("订单已取消", "订单号 A12345", 5_600)).announcement!!
        scheduler.enqueue(accepted, 5_500)
        val replacement = scheduler.enqueue(cancelled, 5_600)
        assertEquals("REPLACED_SAME_BUSINESS_KEY", replacement.reasonCode)
        assertEquals(RiderEventCategory.CANCELLED, scheduler.poll(5_600)!!.category)
        assertNull(scheduler.poll(5_600))
    }

    @Test
    fun `late state cannot replace newer state for same order`() {
        val scheduler = AnnouncementScheduler()
        val newer = AnnouncementPolicy.evaluate(normalize("订单已取消", "订单号 A12345", 5_800)).announcement!!
        val older = AnnouncementPolicy.evaluate(normalize("新订单", "订单号 A12345", 5_700)).announcement!!
        scheduler.enqueue(newer, 5_800)
        assertEquals("STALE_BUSINESS_UPDATE", scheduler.enqueue(older, 5_900).reasonCode)
        assertEquals(RiderEventCategory.CANCELLED, scheduler.poll(5_900)!!.category)
    }

    @Test
    fun `pending queue is capped at three without merging unrelated orders`() {
        val scheduler = AnnouncementScheduler()
        repeat(5) { index ->
            val announcement = AnnouncementPolicy.evaluate(
                normalize("新订单", "订单号 CAP00$index 预计收入${index + 5}元", 6_000L + index)
            ).announcement!!
            scheduler.enqueue(announcement, 6_000L + index)
        }
        val keys = buildList {
            repeat(3) { scheduler.poll(7_000L)?.businessKey?.let(::add) }
        }
        assertEquals(3, keys.size)
        assertEquals(3, keys.distinct().size)
        assertNull(scheduler.poll(7_000L))
    }

    @Test
    fun `hint profile does not invent distance or remaining time`() {
        val event = normalize("新订单", "订单号 A12345", 6_000)
        val result = AnnouncementPolicy.evaluate(
            event,
            AnnouncementProfile(detail = ContentDetail.HINT_ONLY, maxCharacters = 0)
        )
        assertEquals("有新订单", result.announcement!!.speechText)
        assertFalse(result.announcement!!.speechText.contains("分钟"))
        assertFalse(result.announcement!!.speechText.contains("公里"))
    }

    @Test
    fun `group summary and own notifications are ignored`() {
        assertNull(NotificationNormalizer.normalize(raw("g", "标题", "正文", 7_000, group = true), config))
        assertNull(NotificationNormalizer.normalize(raw("self", "标题", "正文", 7_000, packageName = "com.helmet.guard"), config))
    }

    @Test fun safetyEpochRejectsLateResultsEvenAfterIncidentEnds() {
        val repo = com.helmet.guard.core.notification.AnnouncementRepository()
        val epoch = repo.epoch
        assertTrue(repo.permits("old", epoch))
        repo.setSafetyBlocked(true)
        repo.setSafetyBlocked(false)
        assertFalse(repo.permits("old", epoch))
        assertTrue(repo.permits("new", repo.epoch))
        repo.revoke("new")
        assertFalse(repo.permits("new", repo.epoch))
    }

    private fun normalize(title: String, text: String, elapsed: Long) =
        NotificationNormalizer.normalize(raw("key-$elapsed", title, text, elapsed), config)!!

    private fun raw(
        key: String,
        title: String,
        text: String,
        elapsed: Long,
        packageName: String = "rider",
        group: Boolean = false
    ) = RawNotificationInput(
        packageName = packageName,
        notificationKey = key,
        channelId = "orders",
        title = title,
        text = text,
        bigText = null,
        textLines = emptyList(),
        category = null,
        importance = 3,
        postedAtMs = 100,
        receivedElapsedMs = elapsed,
        isGroupSummary = group,
        isOngoing = false
    )
}
