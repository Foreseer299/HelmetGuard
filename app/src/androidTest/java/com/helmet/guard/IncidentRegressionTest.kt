package com.helmet.guard

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import com.helmet.guard.ui.components.EmergencyDialog
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.data.db.AccidentEntity
import com.helmet.guard.data.db.SmsPartEntity
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.HardwareIncidentIdentity
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.service.IncidentCoordinator
import com.helmet.guard.ui.screens.HistoryScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class IncidentRegressionTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var graph: AppGraph
    private val acks = CopyOnWriteArrayList<HardwareIncidentIdentity>()
    private val cancels = CopyOnWriteArrayList<HardwareIncidentIdentity>()

    @Before fun prepare() = runBlocking<Unit> {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        graph = context.appGraph
        graph.database.accidents().clear()
    }

    private fun coordinator() = IncidentCoordinator(
        InstrumentationRegistry.getInstrumentation().targetContext,
        graph.database, graph.preferences, graph.location, graph.sms,
        onHardwareAck = { identity ->
            runBlocking { assertEquals(identity.eventId, graph.database.accidents().byHardwareId(identity.deviceId, identity.eventId)!!.hardwareEventId) }
            acks.add(identity)
        },
        onHardwareCancel = { cancels.add(it); true }
    )

    private fun hardware(event: Long = 7) = IncidentCandidate(
        123, System.currentTimeMillis(), IncidentSource.HELMET_HARDWARE, 0.94f,
        DetectionFeatures(6.42f, 731f, 38f),
        hardware = HardwareIncidentIdentity(0x11223344L, event, "00:11:22:33:44:55")
    )

    private suspend fun awaitCondition(check: suspend () -> Boolean) {
        withTimeout(5_000) { while (!check()) delay(10) }
    }

    @Test fun accidentTabAndDetailRenderAndRefresh() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        graph.database.accidents().upsert(AccidentEntity(
            101, now, now, "HELMET_HARDWARE", "CANCELLED", 0.94f,
            6.42f, 731f, 38f, 0, now, null, null, null, null, null, "用户确认安全", false
        ))
        compose.setContent { MaterialTheme { HistoryScreen(graph) } }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("事故事件 1")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("事故事件 1").performClick()
        compose.onNodeWithText("可信度 94% · 峰值 6.42g · 姿态 38°").assertIsDisplayed().performClick()
        compose.onNode(hasAnyDescendant(hasText("事故与短信回执")) and isRoot()).captureToImage().asAndroidBitmap().let { bitmap ->
            saveUiPreview(InstrumentationRegistry.getInstrumentation().targetContext, "incident-detail.png", bitmap)
        }
        compose.onNodeWithText("事故与短信回执").assertIsDisplayed()
        compose.onNodeWithText("头盔检测").assertIsDisplayed()
        graph.database.accidents().updateStage(101, "PARTIAL_SUCCESS", "部分提交，需要跟进")
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("部分提交，需要跟进")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("部分提交，需要跟进") and hasAnyAncestor(isRoot() and hasAnyDescendant(hasText("事故与短信回执")))).assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
    }

    @Test fun hardwareRetransmissionAfterCancelAndCoordinatorRecreationIsDeduplicated() = runBlocking<Unit> {
        val first = coordinator()
        first.trigger(hardware(), 30)
        awaitCondition { first.state.value.stage == EmergencyStage.COUNTDOWN && acks.size == 1 }
        val localId = first.state.value.eventId!!
        assertNotEquals(7L, localId)
        first.cancel(localId)
        awaitCondition { first.state.value.stage == EmergencyStage.CANCELLED }
        assertEquals(hardware().hardware, cancels.single())
        val restored = coordinator()
        restored.trigger(hardware(), 30)
        awaitCondition { acks.size == 2 }
        assertEquals(EmergencyStage.SAFE, restored.state.value.stage)
        assertEquals(2, cancels.size)
        assertEquals(localId, graph.database.accidents().byHardwareId(0x11223344L, 7)!!.eventId)
        assertEquals(localId, graph.database.accidents().maxEventId())
        assertEquals(emptyList<Any>(), graph.database.smsParts().forEvent(localId))
    }

    @Test fun realHardwarePreemptsSimulationAndOriginalIdSurvivesRestore() = runBlocking<Unit> {
        val first = coordinator()
        first.trigger(hardware().copy(hardware = null, isSimulation = true), 30)
        awaitCondition { first.state.value.stage == EmergencyStage.COUNTDOWN }
        val simulatedId = first.state.value.eventId!!
        first.trigger(hardware(), 30)
        awaitCondition { !first.state.value.isSimulation && acks.size == 1 }
        val realId = first.state.value.eventId!!
        assertNotEquals(simulatedId, realId)
        assertEquals("CANCELLED", graph.database.accidents().byId(simulatedId)!!.stage)
        // Only the resumed coordinator is used to act; original job is cancelled first.
        first.cancel(realId)
        awaitCondition { first.state.value.stage == EmergencyStage.CANCELLED }
        graph.database.accidents().updateStage(realId, "COUNTDOWN", "测试恢复")
        val restored = coordinator()
        restored.restorePending()
        awaitCondition { restored.state.value.stage == EmergencyStage.COUNTDOWN }
        restored.cancel(realId)
        awaitCondition { restored.state.value.stage == EmergencyStage.CANCELLED }
        assertEquals(hardware().hardware, cancels.last())
    }

    @Test fun globalCountdownIsActionableOutsideHome() = runBlocking<Unit> {
        compose.setContent { MaterialTheme { EmergencyDialog(graph) } }
        graph.incidents.trigger(hardware().copy(hardware = null, isSimulation = true), 30)
        awaitCondition { graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN }
        compose.onNodeWithText("模拟事故演练").assertIsDisplayed()
        compose.onNode(hasAnyDescendant(hasText("模拟事故演练")) and isRoot()).captureToImage().asAndroidBitmap().let { bitmap ->
            saveUiPreview(InstrumentationRegistry.getInstrumentation().targetContext, "incident-countdown.png", bitmap)
        }
        compose.onNodeWithText("我安全，取消报警").performClick()
        awaitCondition { graph.incidents.state.value.stage == EmergencyStage.CANCELLED }
    }

    @Test fun interruptedSendingUsesPersistedReceiptsAndPreservesPartialFailure() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        graph.database.accidents().upsert(AccidentEntity(
            301, now, now, "MANUAL", "SENDING", 1f,
            0f, 0f, 0f, 0, now, null, null, null, null, null, "中断发送", false
        ))
        graph.database.smsParts().upsert(SmsPartEntity(301, 1, "演示甲", "***", 0, 1, "SENT", "SUBMITTED", -1, "已提交", now))
        graph.database.smsParts().upsert(SmsPartEntity(301, 2, "演示乙", "***", 0, 1, "SENT", "FAILED", 1, "失败", now))
        val restored = coordinator()
        restored.restorePending()
        awaitCondition { restored.state.value.stage == EmergencyStage.PARTIAL_SUCCESS }
        restored.cancel(301)
        assertEquals("PARTIAL_SUCCESS", graph.database.accidents().byId(301)!!.stage)
        assertEquals(2, graph.database.smsParts().forEvent(301).size)
    }

    @Test fun simulatedSendingRecoveryNeverCallsRealLocationOrSms() = runBlocking<Unit> {
        val now = System.currentTimeMillis()
        graph.database.accidents().upsert(AccidentEntity(
            201, now, now, "PHONE_ALGORITHM", "SENDING", 0.9f,
            5f, 300f, 60f, 0, now, null, null, null, null, null, "中断演练", true
        ))
        val restored = coordinator()
        restored.restorePending()
        awaitCondition { restored.state.value.stage == EmergencyStage.SIMULATED }
        assertEquals("SIMULATED", graph.database.accidents().byId(201)!!.stage)
        assertEquals(emptyList<Any>(), graph.database.smsParts().forEvent(201))
    }
}
