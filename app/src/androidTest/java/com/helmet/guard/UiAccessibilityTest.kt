package com.helmet.guard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run only in accessibility CI: real 320 dp emulator width and system font_scale = 1.6. */
class UiAccessibilityTest : PreviewTestBase() {
    override val requiredFontScale = 1.6f
    override val requiredWidthDp = 320f

    @Test fun narrowHomeAndNavigationLargeTextFitAndActionsRemainReachable() {
        show()
        val width = compose.onNodeWithTag("guard_navigation").fetchSemanticsNode().boundsInRoot.width
        assertEquals(320f, width / previewDensity, 1f)
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
        show()
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
        assertTextFits("取消")
    }

    @Test fun narrowCountdownKeepsBothActionsReadable() = runBlocking<Unit> {
        show()
        selectTab("settings")
        graph.incidents.trigger(
            IncidentCandidate(System.currentTimeMillis(), System.currentTimeMillis(), IncidentSource.PHONE_ALGORITHM,
                0.94f, DetectionFeatures(6.42f, 731f, 38f), isSimulation = true), 30
        )
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN }
        compose.onNodeWithTag("cancel_emergency").assertIsDisplayed()
        compose.onNodeWithTag("send_emergency_now").assertIsDisplayed()
        val dialogBounds = compose.onNodeWithTag("emergency_dialog").fetchSemanticsNode().boundsInRoot
        println("HelmetGuard dialog bounds=$dialogBounds widthDp=${dialogBounds.width / previewDensity} density=$previewDensity")
        capture("countdown-320dp-font160.png")
        assertTextFits("我安全，取消报警")
        assertTextFits("立即完成演练")
        compose.onNodeWithTag("cancel_emergency").performClick()
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.CANCELLED }
        compose.onNodeWithText("可靠性中心").assertIsDisplayed()
    }
}
