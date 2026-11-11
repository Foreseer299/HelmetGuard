package com.helmet.guard

import com.helmet.guard.core.audio.synthesizeWithFallback
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SynthesisFallbackTest {
    @Test fun `working engine does not invoke fallback`() = runTest {
        assertEquals("system", synthesizeWithFallback(primary = { "system" }, fallback = { error("unexpected") }))
    }

    @Test fun `stalled engine is stopped before fallback begins`() = runTest {
        var stopped = false
        val result = synthesizeWithFallback(timeoutMs = 50, primary = {
            try { delay(500); "late" } finally { stopped = true }
        }, fallback = { assertTrue(stopped); "embedded" })
        assertEquals("embedded", result)
    }

    @Test fun `engine failure uses embedded provider`() = runTest {
        assertEquals("embedded", synthesizeWithFallback(primary = { error("engine died") }, fallback = { "embedded" }))
    }

    @Test fun `incident cancellation never starts fallback`() = runTest {
        var fallbackCalled = false
        val job = launch {
            synthesizeWithFallback(primary = { delay(5_000); "system" }, fallback = { fallbackCalled = true; "embedded" })
        }
        yield()
        job.cancelAndJoin()
        assertFalse(fallbackCalled)
    }
}
