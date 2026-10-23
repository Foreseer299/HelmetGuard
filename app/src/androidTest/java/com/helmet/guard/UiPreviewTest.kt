package com.helmet.guard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.ui.GuardApp
import com.helmet.guard.ui.theme.HelmetGuardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class UiPreviewTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph

    @Before fun prepare() = runBlocking<Unit> {
        cancelCountdown()
        graph.recorder.stop()
        // Deterministic empty-state fixtures in the instrumented debug application only.
        graph.database.clearAllTables()
        graph.preferences.setMonitoringEnabled(false)
    }

    @After fun cleanUp() = runBlocking<Unit> { cancelCountdown() }

    private suspend fun cancelCountdown() {
        if (graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN) {
            graph.incidents.cancel(graph.incidents.state.value.eventId)
            withTimeout(5_000) {
                while (graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN) delay(10)
            }
        }
    }

    private fun show(dark: Boolean = false, narrowLargeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            // 320 logical dp across the real device screen, including popup/dialog content.
            val previewDensity = if (narrowLargeText) Density(context.resources.displayMetrics.widthPixels / 320f, 1.6f) else density
            CompositionLocalProvider(LocalDensity provides previewDensity) {
                HelmetGuardTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        GuardApp(graph, { _ -> }, {}, {}, {}, { _, _ -> })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureUiPreview(context, name)
    }

    private fun selectTab(route: String) {
        compose.onNodeWithTag("nav_$route").performClick()
        compose.waitForIdle()
    }

    /** Lazy items must be created by the container before selecting their text. */
    private fun scrollPageTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun assertTextFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("No text layout result for $text", results.isNotEmpty())
        results.forEach { assertFalse("Text overflows: $text", it.hasVisualOverflow) }
    }

    @Test fun homeLightPreview() { show(); capture("home-light.png") }
    @Test fun homeDarkPreview() { show(dark = true); capture("home-dark.png") }

    @Test fun deviceEmptyPreview() {
        show()
        selectTab("device")
        compose.onNodeWithText("我的头盔").assertIsDisplayed()
        capture("device-empty.png")
        scrollPageTo("找不到头盔？")
        capture("device-empty-help.png")
    }

    @Test fun historyEmptyPreview() {
        show()
        selectTab("history")
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("暂无参数记录")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("事故事件 0")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暂无参数记录").assertIsDisplayed()
        capture("history-empty.png")
        compose.onNodeWithText("事故事件 0").performClick()
        compose.onNodeWithText("暂无事故事件").assertIsDisplayed()
        capture("history-incidents-empty.png")
    }

    @Test fun narrowHomeAndNavigationLargeTextFitAndActionsRemainReachable() {
        show(narrowLargeText = true)
        val logicalDensity = context.resources.displayMetrics.widthPixels / 320f
        val width = compose.onNodeWithTag("guard_navigation").fetchSemanticsNode().boundsInRoot.width
        assertEquals(320f, width / logicalDensity, 1f)
        capture("home-320dp-font160-initial.png")
        listOf("守护", "设备", "数据", "记录", "设置").forEach { assertTextFits(it) }
        scrollPageTo("开启骑行守护")
        capture("home-320dp-font160.png")
        assertTextFits("开启骑行守护")
        scrollPageTo("手动触发紧急求救")
        capture("home-320dp-font160-actions.png")
        assertTextFits("手动触发紧急求救")
        selectTab("device")
        scrollPageTo("扫描附近头盔")
        capture("device-320dp-font160.png")
        assertTextFits("扫描附近头盔")
        selectTab("telemetry")
        compose.onNodeWithText("实时数据").assertIsDisplayed()
        capture("telemetry-320dp-font160.png")
    }

    @Test fun narrowSettingsAndContactEditorCanScroll() {
        show(narrowLargeText = true)
        selectTab("settings")
        compose.onNodeWithText("可靠性中心").assertIsDisplayed()
        capture("settings-320dp-font160.png")
        scrollPageTo("紧急联系人")
        compose.onNodeWithText("紧急联系人").performClick()
        compose.waitForIdle()
        scrollPageTo(" 添加联系人")
        compose.onNodeWithText(" 添加联系人").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("姓名", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("发送优先级", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        capture("contact-editor-320dp-font160.png")
    }

    @Test fun narrowCountdownKeepsBothActionsReadable() = runBlocking<Unit> {
        show(narrowLargeText = true)
        selectTab("settings")
        graph.incidents.trigger(
            IncidentCandidate(System.currentTimeMillis(), System.currentTimeMillis(), IncidentSource.PHONE_ALGORITHM,
                0.94f, DetectionFeatures(6.42f, 731f, 38f), isSimulation = true), 30
        )
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN }
        compose.onNodeWithTag("cancel_emergency").assertIsDisplayed()
        compose.onNodeWithTag("send_emergency_now").assertIsDisplayed()
        capture("countdown-320dp-font160.png")
        assertTextFits("我安全，取消报警")
        assertTextFits("立即完成演练")
        compose.onNodeWithTag("cancel_emergency").performClick()
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.CANCELLED }
        compose.onNodeWithText("可靠性中心").assertIsDisplayed()
    }
}
