package com.helmet.guard

import android.content.Context
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.*
import com.helmet.guard.core.notification.HelmetNotificationListener
import com.helmet.guard.service.AnnouncementService
import com.helmet.guard.ui.screens.AnnouncementSettingsScreen
import com.helmet.guard.ui.screens.HomeScreen
import com.helmet.guard.ui.theme.HelmetGuardTheme
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Real Android notifications from an emulator-only default SMS/dialer fixture, not carrier SMS. */
class Communication23Test {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph

    private suspend fun prepare(testPhone: Boolean = true, body: Boolean = false) {
        compose.setContent { HelmetGuardTheme { Text("通讯端到端测试") } }
        graph.announcements.setSafetyBlocked(false); graph.announcements.clear(); graph.callSessions.clear()
        graph.preferences.setAnnouncementMode("PERSONAL")
        graph.preferences.setAnnouncementSources(true,true,false)
        graph.preferences.setSmsSpeechMode(if(body) "CONTACT_BODY" else "SOURCE")
        graph.preferences.setCallSpeechMode("SOURCE")
        graph.preferences.setAnnouncementsEnabled(true)
        graph.preferences.setPhoneTestPlayback(testPhone)
        await { graph.announcementSettings.state.value.let { it.enabled && it.mode=="PERSONAL" && it.phoneTestPlayback==testPhone && it.smsSpeechMode.name==(if(body) "CONTACT_BODY" else "SOURCE") } }
        withContext(Dispatchers.Main) { assertTrue(AnnouncementService.start(context)) }
        await { AnnouncementService.running.value && HelmetNotificationListener.connected.value }
        assertEquals("com.helmet.guard.fixture", android.provider.Telephony.Sms.getDefaultSmsPackage(context))
    }
    @After fun cleanup() = runBlocking<Unit> {
        graph.preferences.setPhoneTestPlayback(false)
        graph.preferences.setAnnouncementsEnabled(false)
        withContext(Dispatchers.Main) { AnnouncementService.stop(context) }
        graph.announcements.clear();graph.callSessions.clear()
        await { !AnnouncementService.running.value }
    }

    @Test fun smsCapturedAndPhoneActuallyConsumesAudioWithoutHelmetMonitoring() = runBlocking<Unit> {
        prepare()
        val ann = sms("sms", "测试小明", "我已经到门口了", true)
        assertEquals("测试小明发来短信",ann.speechText)
        awaitResult(ann.traceId,"PHONE_TEST_PLAYED")
        assertFalse(graph.preferences.monitoringEnabled.first())
        saveDiagnostics("sms-phone-playback.zip")
    }

    @Test fun removingSmsNotificationDoesNotRevokeCapturedSpeech() = runBlocking<Unit> {
        prepare()
        val now = SystemClock.elapsedRealtime()
        val id = send("sms", "测试小明", "通知清除后仍应完成提醒", true)
        val announcement = awaitAnnouncement(now, RiderEventCategory.SMS_MESSAGE)
        shell("am broadcast --include-stopped-packages -a helmetguard.TEST_NOTIFICATION -p com.helmet.guard.fixture --es kind remove --ei id $id")
        awaitResult(announcement.traceId, "PHONE_TEST_PLAYED")
        assertFalse(hasResult(announcement.traceId, "CANCELLED"))
    }

    @Test fun otpAndHiddenSmsGiveUnknownSourceWithoutReadingCode() = runBlocking<Unit> {
        prepare(body=true)
        val otp = sms("sms", "5550100", "验证码839204", false)
        assertEquals("未知来源短信",otp.speechText)
        assertFalse(otp.speechText.contains("839204"))
        awaitResult(otp.traceId,"PHONE_TEST_PLAYED")
        val hidden = sms("hidden", "", "", false)
        assertEquals("未知来源短信",hidden.speechText)
        awaitResult(hidden.traceId,"PHONE_TEST_PLAYED")
    }

    @Test fun contactBodyRequiresRealContactsMatch() = runBlocking<Unit> {
        prepare(body=true)
        val known = sms("mixed", "测试小明", "我已经到门口了", true)
        assertTrue(known.speechText.contains("测试小明"));assertTrue(known.speechText.contains("我已经到门口了"));assertFalse(known.speechText.contains("陌生旧消息"))
        awaitResult(known.traceId,"PHONE_TEST_PLAYED")
        val unknown = sms("sms","5550100","这段未知来源正文不应朗读",false)
        assertEquals("未知来源短信",unknown.speechText)
        awaitResult(unknown.traceId,"PHONE_TEST_PLAYED")
    }

    @Test fun phoneTestSwitchOffCapturesButDoesNotPlay() = runBlocking<Unit> {
        prepare(testPhone=false)
        val ann=sms("sms","5550100","测试开关关闭",false)
        awaitResult(ann.traceId,"NO_HELMET_PHONE_TEST_OFF")
        assertFalse(hasResult(ann.traceId,"PHONE_TEST_STARTED"))
    }

    @Test fun namedCallNotificationAndTelephonyStateJoinAndHangupCancelsPlayback() = runBlocking<Unit> {
        prepare()
        val now=SystemClock.elapsedRealtime()
        send("call","测试小明","来电",false)
        await { hasResultAfter(now,"JOIN_PENDING") }
        graph.callSessions.onState(11,android.telephony.TelephonyManager.CALL_STATE_RINGING,SystemClock.elapsedRealtime())
        val call=awaitAnnouncement(now,RiderEventCategory.INCOMING_CALL)
        assertEquals("测试小明来电",call.speechText)
        awaitResult(call.traceId,"PHONE_TEST_STARTED")
        graph.callSessions.onState(11,android.telephony.TelephonyManager.CALL_STATE_IDLE,SystemClock.elapsedRealtime())
        awaitResult(call.traceId,"CANCELLED")
        assertFalse(hasResult(call.traceId,"PHONE_TEST_PLAYED"))
    }

    @Test fun settingsShowSeparateChoicesAndHomeUsesOriginalShield() = runBlocking<Unit> {
        graph.preferences.setAnnouncementMode("PERSONAL")
        compose.setContent { HelmetGuardTheme { AnnouncementSettingsScreen(graph) {} } }
        compose.onNodeWithText("手机测试播报").performScrollTo().assertIsDisplayed()
        shot("settings-phone-test.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("通讯录联系人的内容"))
        compose.onNodeWithText("通讯录联系人的内容").assertIsDisplayed()
        shot("settings-sms.png")
    }

    @Test fun changingSmsPrivacyModeStopsActiveBodyPlayback() = runBlocking<Unit> {
        prepare(body=true)
        val ann = sms("sms", "测试小明", "我已经到门口了请你慢慢下楼不用着急", true)
        awaitResult(ann.traceId,"PHONE_TEST_STARTED")
        graph.preferences.setSmsSpeechMode("SOURCE")
        awaitResult(ann.traceId,"CANCELLED")
        assertFalse(hasResult(ann.traceId,"PHONE_TEST_PLAYED"))
    }

    @Test fun homeShieldPreview() {
        compose.setContent { HelmetGuardTheme { HomeScreen(graph, { action -> action() }, {}, {}, {}, {}, { _, _ -> }) } }
        compose.waitForIdle()
        shot("home-230.png")
    }

    private suspend fun sms(kind:String,sender:String,text:String,known:Boolean): Announcement {
        val now=SystemClock.elapsedRealtime();send(kind,sender,text,known)
        return awaitAnnouncement(now,RiderEventCategory.SMS_MESSAGE)
    }
    private fun send(kind:String,sender:String,text:String,known:Boolean): Int {
        val id=(SystemClock.elapsedRealtime()%100000).toInt()
        // UiAutomation.executeShellCommand tokenizes arguments without shell quote expansion.
        require(listOf(kind,sender,text).none { value -> value.any(Char::isWhitespace) })
        shell("am broadcast --include-stopped-packages -a helmetguard.TEST_NOTIFICATION -p com.helmet.guard.fixture --es kind $kind --ei id $id" +
            (if(sender.isNotEmpty()) " --es sender $sender" else "") + (if(text.isNotEmpty()) " --es text $text" else "") +
            (if(known) " --es phone +12025550148" else ""))
        return id
    }
    private suspend fun awaitAnnouncement(now:Long,category:RiderEventCategory):Announcement {
        await { graph.announcements.lastEvaluation.value?.announcement?.let { it.createdElapsedMs>=now && it.category==category }==true }
        return graph.announcements.lastEvaluation.value!!.announcement!!
    }
    private suspend fun hasResult(trace:String,result:String):Boolean {
        graph.diagnostics.flush()
        return graph.database.logs().recentForExport().any { it.message.contains("trace=$trace;") && (it.message.contains("result=$result") || it.message.contains("reason=$result")) }
    }
    private suspend fun hasResultAfter(now:Long,result:String):Boolean {
        graph.diagnostics.flush()
        return graph.database.logs().recentForExport().any { it.message.contains("result=$result") && Regex("elapsedMs=(\\d+)").find(it.message)?.groupValues?.get(1)?.toLong()?.let { time->time>=now }==true }
    }
    private suspend fun awaitResult(trace:String,result:String) { await { hasResult(trace,result) } }
    private suspend fun await(check:suspend ()->Boolean) { withTimeout(60000) { while(!check()) delay(30) } }
    private suspend fun saveDiagnostics(name:String) { graph.diagnosticExporter.export().copyTo(File(dir(),name),overwrite=true) }
    private fun dir()=File(context.getExternalFilesDir(null),"communication23").apply {mkdirs()}
    private fun shot(name:String) { compose.waitForIdle();val bitmap=compose.onRoot().captureToImage().asAndroidBitmap();File(dir(),name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle() }
    private fun shell(cmd:String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).let{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()}
}
