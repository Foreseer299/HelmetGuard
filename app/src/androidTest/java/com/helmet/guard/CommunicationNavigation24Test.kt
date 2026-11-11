package com.helmet.guard

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.data.db.EmergencyContactEntity
import com.helmet.guard.ui.screens.AnnouncementSettingsScreen
import com.helmet.guard.ui.screens.ContactsScreen
import com.helmet.guard.ui.theme.HelmetGuardTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CommunicationNavigation24Test {
    @get:Rule val compose = createComposeRule()
    private val graph get() = InstrumentationRegistry.getInstrumentation().targetContext.appGraph

    @Test fun smsDetailSavesChoiceAndReturnsToOverview() = runBlocking<Unit> {
        val oldMode = graph.preferences.smsSpeechMode.first()
        try {
            compose.setContent { HelmetGuardTheme { AnnouncementSettingsScreen(graph) {} } }
            compose.onNodeWithText("短信提醒").performScrollTo().performClick()
            compose.onNodeWithText("通讯录联系人的内容").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("简短提醒").performScrollTo().performClick()
            withTimeout(5_000) { graph.preferences.smsSpeechMode.first { it == "NOTICE" } }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("通讯与播报").assertIsDisplayed()
            compose.onNodeWithText("短信提醒").performScrollTo().performClick()
            assertEquals("NOTICE", graph.preferences.smsSpeechMode.first())
        } finally { graph.preferences.setSmsSpeechMode(oldMode) }
    }

    @Test fun contactEditorRejectsShortAndDuplicateNumbersBeforeSaving() = runBlocking<Unit> {
        val dao = graph.database.contacts()
        val existing = dao.upsert(EmergencyContactEntity(name="重复号码测试", phone="+12025550148", relationship="", priority=98, enabled=false))
        var added: Long? = null
        try {
            compose.setContent { HelmetGuardTheme { ContactsScreen(graph) {} } }
            compose.onNodeWithText("添加联系人").performClick()
            compose.onNodeWithText("姓名 / 称呼").performTextInput("新增联系人测试")
            compose.onNodeWithText("接收短信的号码").performTextInput("110")
            compose.onNodeWithText("保存联系人").assertIsNotEnabled()
            compose.onNodeWithText("接收短信的号码").performTextClearance()
            compose.onNodeWithText("接收短信的号码").performTextInput("+1 202-555-0148")
            compose.onNodeWithText("这个号码已经添加，请编辑已有联系人。").assertIsDisplayed()
            compose.onNodeWithText("保存联系人").assertIsNotEnabled()
            compose.onNodeWithText("接收短信的号码").performTextClearance()
            compose.onNodeWithText("接收短信的号码").performTextInput("+1 202-555-0149")
            compose.onNodeWithText("保存联系人").performClick()
            added = withTimeout(5_000) { dao.observeAll().first { list -> list.any { it.name == "新增联系人测试" } }.single { it.name == "新增联系人测试" }.id }
            assertEquals("+12025550149", dao.observeAll().first().single { it.id == added }.phone)
        } finally { dao.delete(existing); added?.let { dao.delete(it) } }
    }
}
