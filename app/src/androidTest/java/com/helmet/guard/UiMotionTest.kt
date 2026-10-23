package com.helmet.guard

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.DetectionFeatures
import com.helmet.guard.domain.EmergencyStage
import com.helmet.guard.domain.IncidentCandidate
import com.helmet.guard.domain.IncidentSource
import com.helmet.guard.ui.GuardApp
import com.helmet.guard.ui.components.CountdownIndicator
import com.helmet.guard.ui.theme.HelmetGuardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Runs in CI both with system animations disabled and with animator scale = 1. */
class UiMotionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = context.appGraph

    @Before fun prepare() = runBlocking<Unit> {
        cancelPendingSimulation()
        graph.preferences.setMonitoringEnabled(false)
    }

    @After fun cleanUp() = runBlocking<Unit> { cancelPendingSimulation() }

    private suspend fun cancelPendingSimulation() {
        if (graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN) {
            graph.incidents.cancel(graph.incidents.state.value.eventId)
            withTimeout(5_000) {
                while (graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN) delay(10)
            }
        }
    }

    private fun assertActualProgress(value: Float) {
        val range = compose.onNodeWithTag("countdown_indicator").fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(value, range.current, 0.001f)
    }

    @Test fun actualSecondsDriveProgressAndNewEventResetsInterval() {
        var seconds by mutableIntStateOf(20)
        var event by mutableLongStateOf(101)
        compose.setContent {
            HelmetGuardTheme {
                Surface { CountdownIndicator(event, seconds, MaterialTheme.colorScheme.primary) }
            }
        }
        assertActualProgress(1f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { seconds = 10 }
        // The coordinator value changes immediately; the ring reaches it after 180 ms.
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertActualProgress(0.5f)
        val renderedRange = compose.onNodeWithTag("countdown_ring", useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.5f, renderedRange.current, 0.001f)
        compose.onNodeWithText("10").assertIsDisplayed()
        saveUiPreview(context, "motion-countdown-half.png",
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        compose.runOnIdle { event = 102; seconds = 5 }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertActualProgress(1f)
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.runOnIdle { seconds = 0 }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertActualProgress(0f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun recompositionAndSavedStateKeepCurrentEventInterval() {
        var seconds by mutableIntStateOf(20)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HelmetGuardTheme { CountdownIndicator(201, seconds, MaterialTheme.colorScheme.primary) }
        }
        compose.runOnIdle { seconds = 10 }
        assertActualProgress(0.5f)
        restoration.emulateSavedInstanceStateRestore()
        assertActualProgress(0.5f)
        compose.onNodeWithText("10").assertIsDisplayed()
    }

    @Test fun navigationFinishesAndGlobalCountdownRemainsCancellable() = runBlocking<Unit> {
        compose.setContent {
            HelmetGuardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GuardApp(graph, { _ -> }, {}, {}, {}, { _, _ -> })
                }
            }
        }
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithText("可靠性中心").assertIsDisplayed()
        compose.onNodeWithTag("nav_history").performClick()
        compose.onNodeWithText("历史记录").assertIsDisplayed()
        graph.incidents.trigger(
            IncidentCandidate(System.currentTimeMillis(), System.currentTimeMillis(),
                IncidentSource.PHONE_ALGORITHM, 0.94f, DetectionFeatures(6.42f, 731f, 38f), isSimulation = true),
            30
        )
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.COUNTDOWN }
        compose.onNodeWithTag("cancel_emergency").assertIsDisplayed()
        compose.onNodeWithTag("send_emergency_now").assertIsDisplayed()
        val eventId = requireNotNull(graph.incidents.state.value.eventId)
        saveUiPreview(context, "motion-global-countdown-history.png",
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        compose.onNodeWithTag("cancel_emergency").performClick()
        compose.waitUntil(5_000) { graph.incidents.state.value.stage == EmergencyStage.CANCELLED }
        compose.onNodeWithTag("emergency_dialog").assertDoesNotExist()
        compose.onNodeWithText("历史记录").assertIsDisplayed()
        assertEquals(emptyList<Any>(), graph.database.smsParts().forEvent(eventId))
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithText("骑行守护").assertIsDisplayed()
        saveUiPreview(context, "motion-navigation-home.png",
            requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
    }
}
