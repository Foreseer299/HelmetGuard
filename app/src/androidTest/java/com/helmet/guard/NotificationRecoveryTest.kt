package com.helmet.guard

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.notification.HelmetNotificationListener
import com.helmet.guard.core.notification.NotificationListenerRecovery
import com.helmet.guard.service.AnnouncementService
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Real permission removal and reauthorization through the emulator notification manager. */
class NotificationRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val component get() = "${context.packageName}/com.helmet.guard.core.notification.HelmetNotificationListener"
    @Test fun revokedPermissionIsNotReportedAsConnectedAndGrantRestoresListener() = runBlocking<Unit> {
        compose.setContent { Text("通知连接恢复测试") }
        context.appGraph.preferences.setAnnouncementsEnabled(false)
        AnnouncementService.stop(context)
        await { !AnnouncementService.running.value }
        try {
            shell("cmd notification disallow_listener $component")
            await { !HelmetNotificationListener.connected.value && !NotificationListenerRecovery.hasAccess(context) }
            assertEquals("ACCESS_NOT_GRANTED", NotificationListenerRecovery.reconnect(context,"TEST_DENIED",manual=true))
            assertFalse(NotificationListenerRecovery.state.value.accessGranted)
            shell("cmd notification allow_listener $component")
            await { NotificationListenerRecovery.hasAccess(context) && HelmetNotificationListener.connected.value }
            assertEquals("ALREADY_CONNECTED", NotificationListenerRecovery.reconnect(context,"TEST_RESTORED",manual=true))
            assertTrue(NotificationListenerRecovery.state.value.accessGranted)
            context.appGraph.diagnostics.flush()
            val logs=context.appGraph.database.logs().recentForExport()
            assertTrue(logs.any { it.message.contains("event=LIFECYCLE;result=CONNECTED") })
        } finally { shell("cmd notification allow_listener $component") }
    }
    private suspend fun await(check:()->Boolean) { withTimeout(20000) { while(!check()) delay(50) } }
    private fun shell(command:String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
    }
}
