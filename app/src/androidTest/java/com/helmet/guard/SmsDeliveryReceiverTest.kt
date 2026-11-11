package com.helmet.guard

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.telephony.SmsMessage
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.sms.ACTION_SMS_DELIVERED
import com.helmet.guard.core.sms.SmsStatusReceiver
import com.helmet.guard.domain.SmsPartState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDeliveryReceiverTest {
    @Test fun deliveryBroadcastSuccessStillRequiresPositiveNetworkReport() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val event = System.currentTimeMillis()
        val uri = Uri.parse("helmetguard://sms/$event/42/DELIVERED/0")
        val callback = PendingIntent.getBroadcast(context, 42,
            Intent(context, SmsStatusReceiver::class.java).apply {
                action = ACTION_SMS_DELIVERED
                data = uri
                putExtra("event_id", event)
                putExtra("contact_id", 42L)
                putExtra("part_index", 0)
                putExtra("part_count", 1)
            }, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val dao = context.appGraph.database.smsParts()
        suspend fun awaitState(state: SmsPartState) {
            withTimeout(5_000) {
                while (dao.forEvent(event).singleOrNull()?.state != state.name) delay(20)
            }
        }
        fun report(status: String): ByteArray =
            ("0002010b912143658709f16211010000000062110100000000" + status)
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        try {
            val failedPdu = report("40")
            val parsed = SmsMessage.createFromPdu(failedPdu, "3gpp")
            assertTrue(parsed.isStatusReportMessage)
            assertEquals(0x40, parsed.status)
            callback.send(context, Activity.RESULT_OK, Intent().putExtra("format", "3gpp").putExtra("pdu", failedPdu))
            awaitState(SmsPartState.FAILED)
            // Missing/late temporary reports cannot overwrite a final failure.
            callback.send(context, Activity.RESULT_OK, Intent())
            delay(200)
            assertEquals(SmsPartState.FAILED.name, dao.forEvent(event).single().state)
        } finally { callback.cancel() }
    }
}
