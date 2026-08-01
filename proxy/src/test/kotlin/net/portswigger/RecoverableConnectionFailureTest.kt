package net.portswigger

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoverableConnectionFailureTest {
    @Test
    fun `uninitialized SSE transport is recoverable`() {
        val failure = IllegalStateException("SseClientTransport is not initialized!")

        assertTrue(failure.isRecoverableConnectionFailure())
    }

    @Test
    fun `nested connection failures are recoverable`() {
        val failure = IllegalStateException(
            "Tool execution failed",
            IOException("Connection reset by peer"),
        )

        assertTrue(failure.isRecoverableConnectionFailure())
    }

    @Test
    fun `ordinary tool errors do not trigger reconnection`() {
        val failure = IllegalArgumentException("count must be greater than zero")

        assertFalse(failure.isRecoverableConnectionFailure())
    }
}
