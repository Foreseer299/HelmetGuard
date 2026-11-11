package com.helmet.guard

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.audio.LocalTtsSynthesizer
import com.helmet.guard.ui.screens.AnnouncementSettingsScreen
import com.helmet.guard.ui.theme.HelmetGuardTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Voice232Test {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun newVoiceRunsInsideAndroidAndOldModelIsNotPackaged() = runBlocking<Unit> {
        assertFalse(context.assets.list("offline_tts").orEmpty().contains("vits-icefall-zh-aishell3"))
        assertTrue(context.assets.list("offline_tts").orEmpty().contains("vits-piper-zh_CN-xiao_ya-medium-int8"))
        lateinit var tts: LocalTtsSynthesizer
        withContext(Dispatchers.Main) { tts = LocalTtsSynthesizer(context) }
        try {
            val pcm = withTimeout(20000) { tts.synthesize("小明发来短信。有来电。请注意骑行安全。",LocalTtsSynthesizer.EMBEDDED_VOICE) }
            assertTrue(pcm.size > 16000)
            assertTrue(pcm.any { kotlin.math.abs(it.toInt()) > 500 })
            assertTrue(pcm.count { kotlin.math.abs(it.toInt()) >= 32760 } < pcm.size/100)
            val data = ByteBuffer.allocate(44+pcm.size*2).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36+pcm.size*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            data.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size*2)
            pcm.forEach { data.putShort(it) }
            File(context.getExternalFilesDir(null),"xiaoya-android-preview.wav").writeBytes(data.array())
        } finally { tts.shutdown() }
    }
    @Test fun waitingLabelIncludesPermissionResetHint() = runBlocking<Unit> {
        val component="${context.packageName}/com.helmet.guard.core.notification.HelmetNotificationListener"
        try {
            shell("cmd notification disallow_listener $component")
            withTimeout(10000) { while(com.helmet.guard.core.notification.HelmetNotificationListener.connected.value) delay(20) }
            compose.setContent { HelmetGuardTheme { AnnouncementSettingsScreen(context.appGraph) {} } }
            compose.onNodeWithText("通知监听：等待系统连接（需要关闭通知使用权再重新打开）").performScrollTo().assertIsDisplayed()
        } finally { shell("cmd notification allow_listener $component") }
    }
    private fun shell(command:String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
}
