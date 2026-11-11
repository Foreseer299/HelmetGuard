package com.helmet.guard.core.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A stalled engine is recoverable, but user/incident cancellation must never start another engine. */
internal suspend fun <T : Any> synthesizeWithFallback(
    timeoutMs: Long = 6_000,
    primary: suspend () -> T,
    fallback: suspend () -> T
): T {
    val result = try {
        withTimeoutOrNull(timeoutMs) { primary() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    currentCoroutineContext().ensureActive()
    return result ?: fallback()
}
