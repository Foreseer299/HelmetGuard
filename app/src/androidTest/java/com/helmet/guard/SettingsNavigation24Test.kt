package com.helmet.guard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.ui.screens.SettingsScreen
import com.helmet.guard.ui.screens.DeviceScreen
import com.helmet.guard.ui.screens.HistoryScreen
import com.helmet.guard.ui.screens.AnnouncementSettingsScreen
import com.helmet.guard.ui.screens.ContactsScreen
import com.helmet.guard.ui.theme.HelmetGuardTheme
import org.junit.Rule
import org.junit.Test

class SettingsNavigation24Test {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph
    private fun capture(name: String) {
        compose.waitForIdle()
        saveUiPreview(context, name, requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }
    @Test fun smsAssistIsDirectlyDiscoverableAndRequiresDisclosure() {
        compose.setContent { HelmetGuardTheme { SettingsScreen(graph, {}, {}, {}) } }
        compose.onNodeWithText("短信发送与无障碍").assertIsDisplayed().performClick()
        compose.onNodeWithText("管理短信确认辅助").assertIsDisplayed().performClick()
        compose.onNodeWithText("去系统设置").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("使用前检查").assertIsDisplayed()
        capture("settings-240.png")
    }
    @Test fun rescueAndPermissionsAreSeparateReachablePages() {
        compose.setContent { HelmetGuardTheme { SettingsScreen(graph, {}, {}, {}) } }
        compose.onNodeWithText("求救资料与倒计时").performClick()
        compose.onNodeWithText("误报取消时间").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("使用前检查").performClick()
        compose.onNodeWithText("检查并申请必要权限").assertIsDisplayed()
    }
    @Test fun devicePreview() {
        compose.setContent { HelmetGuardTheme { DeviceScreen(graph, {}, {}, {}) } }
        compose.onNodeWithText("扫描附近头盔").assertIsDisplayed()
        capture("device-240.png")
    }
    @Test fun historyPreview() {
        compose.setContent { HelmetGuardTheme { HistoryScreen(graph) } }
        capture("history-240.png")
    }
    @Test fun communicationsPreview() {
        compose.setContent { HelmetGuardTheme { AnnouncementSettingsScreen(graph) {} } }
        capture("communications-240.png")
    }
    @Test fun contactsPreview() {
        compose.setContent { HelmetGuardTheme { ContactsScreen(graph) {} } }
        capture("contacts-240.png")
    }
}
