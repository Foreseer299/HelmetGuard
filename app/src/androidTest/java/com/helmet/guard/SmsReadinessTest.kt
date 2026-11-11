package com.helmet.guard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.data.db.EmergencyContactEntity
import com.helmet.guard.ui.screens.SettingsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SmsReadinessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun testRequiresConfirmationAndCanBeCancelledBeforeSubmission() = runBlocking<Unit> {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        assertFalse("Run this test without SEND_SMS granted", graph.sms.hasPermission())
        val contactId = graph.database.contacts().upsert(
            EmergencyContactEntity(
                name = "测试联系人", phone = "+12025550123", relationship = "",
                priority = Int.MIN_VALUE, enabled = true
            )
        )
        try {
            compose.setContent { MaterialTheme { SettingsScreen(graph, {}, {}, {}) } }
            compose.onNodeWithText("短信发送与无障碍").performClick()
            val sendTest = "测试求救短信发送"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(sendTest))
            compose.onNodeWithText(sendTest).performClick()
            compose.onNodeWithText("发送一条测试短信").assertIsDisplayed()
            compose.onNodeWithText("延时 10 秒，测试锁屏发送").performClick()
            compose.onNodeWithText("确认测试").performClick()
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("取消测试"))
            compose.onNodeWithText("取消测试").performClick()
            compose.onNodeWithText("已取消本次测试。").assertIsDisplayed()

            compose.onNodeWithText(sendTest).performClick()
            compose.onNodeWithText("确认测试").performClick()
            val denied = "未授予发送短信权限，请在应用权限中允许后再试。"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(denied))
            compose.onNodeWithText(denied).assertIsDisplayed()
        } finally {
            graph.database.contacts().delete(contactId)
        }
    }
}
