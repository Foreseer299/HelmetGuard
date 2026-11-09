package com.helmet.guard.core.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.room.withTransaction
import androidx.core.content.ContextCompat
import com.helmet.guard.appGraph
import com.helmet.guard.data.db.GuardDatabase
import com.helmet.guard.data.db.SmsPartEntity
import com.helmet.guard.domain.EmergencyContact
import com.helmet.guard.domain.LocationFix
import com.helmet.guard.domain.SmsPartState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val ACTION_SMS_SENT = "com.helmet.guard.action.SMS_SENT"
const val ACTION_SMS_DELIVERED = "com.helmet.guard.action.SMS_DELIVERED"
private const val ACTION_SMS_TEST_SENT = "com.helmet.guard.action.SMS_TEST_SENT"
private const val EXTRA_EVENT = "event_id"
private const val EXTRA_CONTACT = "contact_id"
private const val EXTRA_CONTACT_NAME = "contact_name"
private const val EXTRA_PHONE_MASKED = "phone_masked"
private const val EXTRA_PART = "part_index"
private const val EXTRA_COUNT = "part_count"
private const val EXTRA_KIND = "kind"

data class SmsLaunchReport(
    val queuedParts: Int,
    val queuedContacts: Int,
    val subscriptionId: Int?,
    val errors: List<String>
) {
    val launched: Boolean get() = queuedParts > 0
}

class SmsPipeline(
    private val context: Context,
    private val database: GuardDatabase
) {
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun availableSubscriptions(): List<Pair<Int, String>> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            manager.activeSubscriptionInfoList.orEmpty().map { info ->
                info.subscriptionId to (info.displayName?.toString()?.ifBlank { "SIM ${info.simSlotIndex + 1}" } ?: "SIM ${info.simSlotIndex + 1}")
            }
        }.getOrDefault(emptyList())
    }

    private val testReceipts = ConcurrentHashMap<String, CompletableDeferred<Int>>()

    fun isTestRecipient(phone: String): Boolean =
        phone.filterNot(Char::isWhitespace).matches(Regex("[+]?[0-9]{7,15}"))

    /** Sends one harmless test message over the same SIM and SMS API as emergency dispatch. */
    suspend fun sendTest(
        phone: String,
        requestedSubscriptionId: Int,
        requireScreenOff: Boolean = false,
        assistConfirmation: Boolean = false
    ): String {
        if (!hasPermission()) {
            return "未授予发送短信权限，请在应用权限中允许后再试。"
        }
        if (!isTestRecipient(phone)) {
            return "请选择完整的手机号码作为测试收件人。"
        }
        val subscriptionId = resolveSubscription(requestedSubscriptionId)
            ?: return "请检查 SIM 卡，并在设置中选择求救 SIM。"
        val manager = SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        val parts = manager.divideMessage("【HelmetGuard测试】这是一条骑行守护短信测试，无需救援。")
        if (parts.size != 1) return "无法生成单条测试短信。"

        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<Int>()
        val callback = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SmsStatusReceiver::class.java).apply {
                action = ACTION_SMS_TEST_SENT
                data = Uri.parse("helmetguard://sms/test/$token")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
        testReceipts[token] = result
        val diagnostics = context.appGraph.diagnostics
        try {
            val screenOff = context.getSystemService(PowerManager::class.java)?.isInteractive == false
            if (requireScreenOff && !screenOff) {
                return "提交时手机仍亮屏，本次未发送。请重新测试并在倒计时结束前锁屏。"
            }
            diagnostics.log(token, "sms", "preflight", "requested",
                metrics = mapOf("screenOff" to screenOff, "assistEnabled" to assistConfirmation))
            SmsConfirmationService.begin(token, if (assistConfirmation) 1 else 0)
            manager.sendMultipartTextMessage(
                phone.filterNot(Char::isWhitespace), null, parts, arrayListOf(callback), null
            )
            val code = withTimeoutOrNull(30_000) { result.await() }
            diagnostics.log(token, "sms", "preflight", code?.toString() ?: "no_receipt")
            if (code == null) {
                return "30 秒内未收到提交回执。请检查是否有系统弹窗，并向收件人确认后再试。"
            }
            if (code == Activity.RESULT_OK) {
                return "本次测试已提交运营商，请向收件人确认。若你点过系统“发送”，仍需另测无人操作时能否发出。"
            }
            return "测试短信未提交：${smsResultDescription(code, "SENT")}。"
        } finally {
            SmsConfirmationService.gate.end(token)
            testReceipts.remove(token)
            callback.cancel()
        }
    }

    fun finishEmergencyConfirmation(eventId: Long) {
        SmsConfirmationService.gate.end("incident:$eventId")
    }

    internal fun completeTest(token: String, resultCode: Int) {
        testReceipts[token]?.complete(resultCode)
    }

    suspend fun sendEmergency(
        eventId: Long,
        contacts: List<EmergencyContact>,
        requestedSubscriptionId: Int,
        riderName: String,
        riderPhone: String,
        location: LocationFix?,
        confidence: Float,
        detectedAtMs: Long,
        isSimulation: Boolean
    ): SmsLaunchReport {
        if (isSimulation) return SmsLaunchReport(0, 0, null, listOf("模拟事件不会发送真实短信"))
        if (!hasPermission()) return SmsLaunchReport(0, 0, null, listOf("未授予短信权限"))
        val validContacts = contacts.filter { it.enabled && PhoneNumberUtils.isGlobalPhoneNumber(it.phone.filterNot(Char::isWhitespace)) }
        if (validContacts.isEmpty()) return SmsLaunchReport(0, 0, null, listOf("没有有效的紧急联系人"))

        val selected = resolveSubscription(requestedSubscriptionId)
            ?: return SmsLaunchReport(0, 0, null, listOf("存在多张 SIM 卡，请先在设置中明确选择求救 SIM"))
        val smsManager = runCatching { SmsManager.getSmsManagerForSubscriptionId(selected) }.getOrNull()
            ?: return SmsLaunchReport(0, 0, selected, listOf("无法访问所选 SIM"))
        val message = buildMessage(riderName, riderPhone, location, confidence, detectedAtMs)
        val parts = smsManager.divideMessage(message)
        val errors = mutableListOf<String>()
        var queuedParts = 0
        var queuedContacts = 0

        // Persist the full dispatch plan atomically. After process death, unsent contacts
        // remain visibly unconfirmed instead of a partial send being reported as complete.
        database.withTransaction {
            validContacts.forEach { contact -> parts.indices.forEach { part ->
                database.smsParts().upsert(row(eventId, contact, part, parts.size, "SENT", SmsPartState.QUEUED, 0, "等待提交至运营商"))
                database.smsParts().upsert(row(eventId, contact, part, parts.size, "DELIVERED", SmsPartState.QUEUED, 0, "等待送达回执"))
            } }
        }
        SmsConfirmationService.begin("incident:$eventId", validContacts.size)
        validContacts.sortedBy { it.priority }.forEach { contact ->
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { part ->
                sent += statusIntent(ACTION_SMS_SENT, eventId, contact, part, parts.size, "SENT")
                delivered += statusIntent(ACTION_SMS_DELIVERED, eventId, contact, part, parts.size, "DELIVERED")
            }
            runCatching {
                smsManager.sendMultipartTextMessage(contact.phone, null, parts, sent, delivered)
            }.onSuccess {
                queuedParts += parts.size
                queuedContacts++
            }.onFailure { throwable ->
                errors += "${contact.name}：${throwable.message ?: "发送调用失败"}"
                parts.indices.forEach { part ->
                    database.smsParts().upsert(row(eventId, contact, part, parts.size, "SENT", SmsPartState.FAILED, -1, throwable.javaClass.simpleName))
                    database.smsParts().upsert(row(eventId, contact, part, parts.size, "DELIVERED", SmsPartState.FAILED, -1, "短信未提交"))
                }
            }
        }
        return SmsLaunchReport(queuedParts, queuedContacts, selected, errors)
    }

    private fun resolveSubscription(requested: Int): Int? {
        val active = availableSubscriptions()
        if (requested >= 0 && (active.isEmpty() || active.any { it.first == requested })) return requested
        if (active.size == 1) return active.first().first
        if (active.size > 1) return null // Never guess on a dual-SIM device.
        val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
        return defaultId.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }

    private fun buildMessage(name: String, riderPhone: String, location: LocationFix?, confidence: Float, atMs: Long): String {
        val time = SimpleDateFormat("MM月dd日 HH:mm:ss", Locale.CHINA).format(Date(atMs))
        val locationText = if (location == null) {
            "定位暂时不可用，请立即电话联系并根据骑行路线寻找。"
        } else {
            val locationTime = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(location.timeMs))
            val freshness = if (location.historical) "最近位置（$locationTime）" else "事故位置"
            val coordinate = String.format(Locale.US, "%.6f,%.6f", location.longitude, location.latitude)
            "$freshness（误差约${location.accuracyMeters.toInt()}米）：https://uri.amap.com/marker?position=$coordinate&name=HelmetGuard求救点"
        }
        val callback = riderPhone.trim().takeIf { it.isNotBlank() }?.let { " 本人电话：$it。" }.orEmpty()
        return "【HelmetGuard紧急求救】${name.ifBlank { "骑手" }}疑似发生事故（可信度${(confidence * 100).toInt()}%，$time）。$locationText$callback 请尽快联系本人；如无法取得联系，请拨打120/110。本信息由本人预先授权的智能头盔自动发送。"
    }

    private fun statusIntent(action: String, eventId: Long, contact: EmergencyContact, part: Int, count: Int, kind: String): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("helmetguard://sms/$eventId/${contact.id}/$kind/$part")
            putExtra(EXTRA_EVENT, eventId)
            putExtra(EXTRA_CONTACT, contact.id)
            putExtra(EXTRA_CONTACT_NAME, contact.name)
            putExtra(EXTRA_PHONE_MASKED, mask(contact.phone))
            putExtra(EXTRA_PART, part)
            putExtra(EXTRA_COUNT, count)
            putExtra(EXTRA_KIND, kind)
        }
        val requestCode = (eventId xor contact.id xor (part.toLong() shl 8) xor kind.hashCode().toLong()).toInt()
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun row(eventId: Long, contact: EmergencyContact, part: Int, count: Int, kind: String, state: SmsPartState, code: Int, detail: String) =
        SmsPartEntity(eventId, contact.id, contact.name, mask(contact.phone), part, count, kind, state.name, code, detail, System.currentTimeMillis())

    private fun mask(phone: String): String {
        val cleaned = phone.filter { it.isDigit() || it == '+' }
        return if (cleaned.length <= 7) "***" else "${cleaned.take(3)}****${cleaned.takeLast(4)}"
    }
}

/** Manifest receiver: persists callbacks even when Android recreates the app process for PendingIntent. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SMS_TEST_SENT) {
            intent.data?.lastPathSegment?.let { context.appGraph.sms.completeTest(it, resultCode) }
            return
        }
        if (intent.action != ACTION_SMS_SENT && intent.action != ACTION_SMS_DELIVERED) return
        val callbackResultCode = resultCode
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val kind = intent.getStringExtra(EXTRA_KIND) ?: return@launch
                val state = when {
                    callbackResultCode == Activity.RESULT_OK && kind == "SENT" -> SmsPartState.SUBMITTED
                    callbackResultCode == Activity.RESULT_OK && kind == "DELIVERED" -> SmsPartState.DELIVERED
                    else -> SmsPartState.FAILED
                }
                val detail = smsResultDescription(callbackResultCode, kind)
                GuardDatabase.create(context).smsParts().upsert(
                    SmsPartEntity(
                        eventId = intent.getLongExtra(EXTRA_EVENT, -1),
                        contactId = intent.getLongExtra(EXTRA_CONTACT, -1),
                        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME).orEmpty(),
                        phoneMasked = intent.getStringExtra(EXTRA_PHONE_MASKED).orEmpty(),
                        partIndex = intent.getIntExtra(EXTRA_PART, 0),
                        partCount = intent.getIntExtra(EXTRA_COUNT, 1),
                        kind = kind,
                        state = state.name,
                        resultCode = callbackResultCode,
                        detail = detail,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            } finally {
                pending.finish()
            }
        }
    }
}

private fun smsResultDescription(code: Int, kind: String): String = when (code) {
    Activity.RESULT_OK -> if (kind == "SENT") "运营商已接受短信" else "对方网络已返回送达回执"
    SmsManager.RESULT_CANCELLED -> "系统取消了发送"
    SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "系统未允许发送到此短号码"
    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "通用发送失败"
    SmsManager.RESULT_ERROR_NO_SERVICE -> "当前无蜂窝网络服务"
    SmsManager.RESULT_ERROR_NULL_PDU -> "短信数据无效"
    SmsManager.RESULT_ERROR_RADIO_OFF -> "飞行模式或蜂窝无线电关闭"
    else -> "发送/送达失败（代码 $code）"
}
