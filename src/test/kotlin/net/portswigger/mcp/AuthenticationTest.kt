package net.portswigger.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthenticationTest {
    private val token = "a-secure-test-token-with-more-than-32-characters"

    @Test
    fun `accepts bearer token`() {
        assertTrue(authenticationMatches("Bearer $token", null, token))
        assertTrue(authenticationMatches("bearer $token", null, token))
    }

    @Test
    fun `accepts api key header`() {
        assertTrue(authenticationMatches(null, token, token))
    }

    @Test
    fun `rejects missing malformed and incorrect credentials`() {
        assertFalse(authenticationMatches(null, null, token))
        assertFalse(authenticationMatches("Basic $token", null, token))
        assertFalse(authenticationMatches("Bearer wrong", null, token))
        assertFalse(authenticationMatches("Bearer $token", null, ""))
    }
}
