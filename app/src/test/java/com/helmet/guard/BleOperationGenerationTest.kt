package com.helmet.guard

import com.helmet.guard.core.ble.OperationGeneration
import org.junit.Assert.*
import org.junit.Test

class BleOperationGenerationTest {
    @Test fun lateTimeoutCannotConsumeNextOperationOrNewConnection() {
        val queue = OperationGeneration()
        val first = queue.begin()
        assertTrue(queue.finish(first))
        val next = queue.begin()
        assertFalse(queue.finish(first))
        assertTrue(queue.isCurrent(next))
        queue.clear() // disconnect
        val reconnect = queue.begin()
        assertFalse(queue.finish(next))
        assertTrue(queue.finish(reconnect))
    }
    @Test fun timeoutAndCompletionHaveExactlyOneWinner() {
        val queue = OperationGeneration()
        val token = queue.begin()
        assertTrue(queue.finish(token))
        assertFalse(queue.finish(token))
    }
}
