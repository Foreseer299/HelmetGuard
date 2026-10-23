package com.helmet.guard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test

class UiPreviewTest : PreviewTestBase() {
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
}
