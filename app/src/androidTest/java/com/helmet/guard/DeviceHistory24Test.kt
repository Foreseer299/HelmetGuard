package com.helmet.guard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.RecordingChannels
import com.helmet.guard.domain.RecordingLabel
import com.helmet.guard.ui.screens.DeviceScreen
import com.helmet.guard.ui.screens.HistoryScreen
import com.helmet.guard.ui.theme.HelmetGuardTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DeviceHistory24Test {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun devicePageExplainsDisconnectedStateAndScanAction() {
        context.appGraph.ble.disconnect()
        compose.setContent { HelmetGuardTheme { DeviceScreen(context.appGraph, {}, {}, {}) } }
        compose.onNodeWithText("扫描附近头盔").assertIsDisplayed()
        compose.waitForIdle()
        saveUiPreview(context, "device-240.png", requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }

    @Test fun deletingRecordRequiresConfirmationAndCancelKeepsData() = runBlocking<Unit> {
        val graph = context.appGraph
        val id = graph.recorder.start(RecordingLabel.NORMAL_RIDE, "删除确认测试", RecordingChannels(), "测试头盔", false)
        graph.recorder.stop()
        try {
            withTimeout(5000) { while (graph.recorder.sessions.value.none { it.id == id }) delay(20) }
            compose.setContent { HelmetGuardTheme { HistoryScreen(graph) } }
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("session-$id"))
            fun delete() = compose.onNode(hasContentDescription("删除这条参数记录") and hasAnyAncestor(hasTestTag("session-$id")), useUnmergedTree = true).performClick()
            delete()
            compose.onNodeWithText("保留记录").performClick()
            assertNotNull(graph.database.telemetry().session(id))
            compose.waitForIdle()
            saveUiPreview(context, "history-240.png", requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
            delete()
            compose.onNodeWithText("确认删除").performClick()
            withTimeout(5000) { while (graph.database.telemetry().session(id) != null) delay(20) }
            assertNull(graph.database.telemetry().session(id))
        } finally { graph.recorder.delete(id) }
    }
}
