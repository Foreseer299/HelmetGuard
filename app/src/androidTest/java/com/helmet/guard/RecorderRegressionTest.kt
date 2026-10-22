package com.helmet.guard

import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.domain.DetectorSnapshot
import com.helmet.guard.domain.RecordingChannels
import com.helmet.guard.domain.RecordingLabel
import com.helmet.guard.domain.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderRegressionTest {
    @Test fun sourceSelectionPreventsMixingRealAndSimulatedSamples() = runBlocking<Unit> {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val id = graph.recorder.start(RecordingLabel.CRASH_TEST, "test", RecordingChannels(), "模拟", true)
        try {
            graph.recorder.record(TelemetrySample.STATIC, DetectorSnapshot(), false)
            graph.recorder.record(TelemetrySample.STATIC, DetectorSnapshot(), true)
        } finally { graph.recorder.stop() }
        assertEquals(1, graph.recorder.loadSamples(id).size)
        assertEquals(1, graph.database.telemetry().session(id)!!.sampleCount)
    }

    @Test fun concurrentSamplesKeepPersistedCountConsistent() = runBlocking<Unit> {
        val graph = InstrumentationRegistry.getInstrumentation().targetContext.appGraph
        val id = graph.recorder.start(RecordingLabel.NORMAL_RIDE, "test", RecordingChannels(), "test", false)
        try {
            coroutineScope {
                repeat(40) { index -> launch(Dispatchers.Default) {
                    graph.recorder.record(TelemetrySample.STATIC.copy(receivedAtMs = index.toLong()), DetectorSnapshot(), false)
                } }
            }
        } finally { graph.recorder.stop() }
        assertEquals(40, graph.recorder.loadSamples(id).size)
        assertEquals(40, graph.database.telemetry().session(id)!!.sampleCount)
    }
}
