package com.helmet.guard

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.EmergencyStage
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

abstract class PreviewTestBase {
    @get:Rule val compose = createComposeRule()
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val graph get() = context.appGraph
    protected var previewDensity = 1f
        private set
    private var appFontScale = 1f
    protected open val requiredFontScale: Float? = null
    protected open val requiredWidthDp: Float? = null

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

    protected fun show(dark: Boolean = false) {
        // Accessibility CI configures the actual emulator before instrumentation starts.
        // No local override: Compose, Android resources and separate Dialog windows must
        // all use the same real system density and font scale.
        requiredFontScale?.let { expected ->
            assertEquals("Wrong emulator configuration font scale", expected,
                context.resources.configuration.fontScale, 0.001f)
        }
        compose.setContent { PreviewApp(dark) }
        compose.waitForIdle()
        requiredFontScale?.let { expected ->
            assertEquals("Wrong application Compose font scale", expected, appFontScale, 0.001f)
        }
        requiredWidthDp?.let { expected ->
            val bounds = compose.onNodeWithTag("guard_navigation").fetchSemanticsNode().boundsInRoot
            println("HelmetGuard viewport bounds=$bounds density=$previewDensity fontScale=$appFontScale")
            assertEquals("Wrong emulator logical viewport width", expected, bounds.width / previewDensity, 1f)
        }
    }

    @Composable
    private fun PreviewApp(dark: Boolean) {
        val density = LocalDensity.current
        SideEffect { previewDensity = density.density; appFontScale = density.fontScale }
        HelmetGuardTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                GuardApp(graph, { _ -> }, {}, {}, {}, { _, _ -> })
            }
        }
    }

    protected fun capture(name: String) {
        compose.waitForIdle()
        captureUiPreview(context, name)
    }

    protected fun selectTab(route: String) {
        compose.onNodeWithTag("nav_$route").performClick()
        compose.waitForIdle()
    }

    /** Lazy items must be created by the container before selecting their text. */
    protected fun scrollPageTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
        compose.waitForIdle()
        // A Lazy item can be taller than its viewport. Scroll the specific descendant
        // after the container has created it, rather than stopping at the item's top.
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
    }

    protected fun assertTextFits(text: String) {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue("No text layout result for $text", results.isNotEmpty())
        results.forEach {
            val diagnostics = "text=$text bounds=${compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot} " +
                "size=${it.size} paragraph=${it.multiParagraph.width}x${it.multiParagraph.height} " +
                "didOverflowWidth=${it.didOverflowWidth} didOverflowHeight=${it.didOverflowHeight} " +
                "density=${it.layoutInput.density.density} fontScale=${it.layoutInput.density.fontScale} " +
                "lines=${it.lineCount} maxLines=${it.layoutInput.maxLines} constraints=${it.layoutInput.constraints}"
            println("HelmetGuard text layout: $diagnostics")
            requiredFontScale?.let { expected ->
                assertEquals("Wrong text scale: $diagnostics", expected, it.layoutInput.density.fontScale, 0.001f)
            }
            // Foundation 1.7.5 TextStringSimpleNode synthesizes this result with
            // slowCreateTextLayoutResultOrNull: MultiParagraph uses prevConstraints,
            // but size is the original compact layoutSize. Its wider paragraph container
            // makes hasVisualOverflow report false positives for short, intact text.
            // Check actual line extents and complete character coverage instead.
            assertTrue("No laid-out lines: $diagnostics", it.lineCount > 0)
            assertFalse("Maximum lines exceeded: $diagnostics", it.multiParagraph.didExceedMaxLines)
            for (line in 0 until it.lineCount) {
                val left = it.getLineLeft(line)
                val right = it.getLineRight(line)
                val top = it.getLineTop(line)
                val bottom = it.getLineBottom(line)
                val lineDiagnostics = "$diagnostics line=$line left=$left right=$right top=$top bottom=$bottom"
                assertTrue("Glyph width exceeds layout: $lineDiagnostics", right - left <= it.size.width + 1f)
                assertTrue("Line begins above layout: $lineDiagnostics", top >= -1f)
                assertTrue("Line height exceeds layout: $lineDiagnostics", bottom <= it.size.height + 1f)
                assertFalse("Line is ellipsized: $lineDiagnostics", it.isLineEllipsized(line))
            }
            assertEquals("Characters missing from layout: $diagnostics",
                it.layoutInput.text.length, it.getLineEnd(it.lineCount - 1, visibleEnd = false))
        }
    }
}
