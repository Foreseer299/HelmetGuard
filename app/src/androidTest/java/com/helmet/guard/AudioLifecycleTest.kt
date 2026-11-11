package com.helmet.guard

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helmet.guard.core.audio.EmbeddedSherpaTtsSynthesizer
import com.helmet.guard.core.audio.PhoneTestPlayer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shutdownDoesNotBlockUiWhileNativeInferenceOwnsLock() {
        val synth = EmbeddedSherpaTtsSynthesizer(context)
        val field = synth.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val nativeLock = field.get(synth)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Thread {
            synchronized(nativeLock) { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            var elapsed = Long.MAX_VALUE
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val start = SystemClock.elapsedRealtime()
                synth.shutdown()
                elapsed = SystemClock.elapsedRealtime() - start
            }
            assertTrue("shutdown waited ${elapsed}ms for native lock", elapsed < 500)
        } finally { release.countDown(); worker.join(2_000) }
    }

    @Test fun revokedPhonePlaybackNeverRequestsOutput() = runBlocking<Unit> {
        try {
            PhoneTestPlayer(context).play(ShortArray(1600), keepPlaying = { false })
            fail("Revoked audio must not play")
        } catch (_: CancellationException) { }
    }
}
