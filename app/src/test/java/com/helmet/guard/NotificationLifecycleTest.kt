package com.helmet.guard

import com.helmet.guard.core.notification.*
import com.helmet.guard.domain.*
import org.junit.Assert.*
import org.junit.Test

class NotificationLifecycleTest {
    private fun call(trace: String, now: Long = 100) = NotificationEvent(
        trace, "dialer", "call-key", null, "张三", "来电", "call", 4,
        1000, now, false, true, AnnouncementSource.CALL
    )

    @Test fun `unchanged call repost retains original revocable trace`() {
        val repository = AnnouncementRepository()
        val tracker = NotificationSubmissionTracker()
        assertNull(tracker.accept("call-key", repository.submit(call("first"))))
        val repeated = repository.submit(call("second", 200))
        assertEquals(AnnouncementDecision.DUPLICATE, repeated.decision)
        assertNull(tracker.accept("call-key", repeated))
        val removed = tracker.remove("call-key")!!
        assertEquals("first", removed)
        repository.revoke(removed)
        assertNull(repository.poll(250))
    }

    @Test fun `changed caller replaces previous accepted trace`() {
        val repository = AnnouncementRepository()
        val tracker = NotificationSubmissionTracker()
        tracker.accept("call-key", repository.submit(call("first")))
        val updated = repository.submit(call("second", 200).copy(title = "李四"))
        assertEquals("first", tracker.accept("call-key", updated))
        assertEquals("second", tracker.remove("call-key"))
    }

    @Test fun `clear invalidates already polled synthesis as well as queue`() {
        val repository = AnnouncementRepository()
        repository.submit(call("first"))
        val active = repository.poll(150)!!
        val epoch = repository.epoch
        assertTrue(repository.permits(active.traceId, epoch))
        repository.clear()
        assertFalse(repository.permits(active.traceId, epoch))
    }

    @Test fun `safety block is idempotent and never revives old synthesis`() {
        val repository = AnnouncementRepository()
        val epoch = repository.epoch
        repository.setSafetyBlocked(true)
        val blockedEpoch = repository.epoch
        repository.setSafetyBlocked(true)
        assertEquals(blockedEpoch, repository.epoch)
        repository.setSafetyBlocked(false)
        assertFalse(repository.permits("first", epoch))
        assertTrue(repository.permits("new", repository.epoch))
    }

    @Test fun `dialer outgoing ended and missed notifications are not incoming calls`() {
        for (text in listOf("正在拨号", "通话中", "未接来电", "Outgoing call", "Calling", "Call ended", "Missed call", "Voicemail")) {
            assertFalse(text, IncomingCallNotificationPolicy.canDescribeIncomingCall("call", "张三", text))
            assertFalse(text, IncomingCallNotificationPolicy.canDescribeIncomingCall("call", text, "张三"))
        }
        assertTrue(IncomingCallNotificationPolicy.canDescribeIncomingCall("call", "张三", "Incoming call"))
        assertTrue(IncomingCallNotificationPolicy.canDescribeIncomingCall("call", "张三", "正在来电"))
        assertFalse(IncomingCallNotificationPolicy.canDescribeIncomingCall("msg", "张三", "Incoming call"))
    }
}
