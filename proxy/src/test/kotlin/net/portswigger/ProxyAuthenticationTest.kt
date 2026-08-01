package net.portswigger

import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProxyAuthenticationTest {
    @Test
    fun `bearer token is sent in authorization header`() {
        val headers = proxyAuthenticationHeaders { name ->
            if (name == "MCP_AUTH_TOKEN") " secret-token " else null
        }

        assertEquals("Bearer secret-token", headers[HttpHeaders.Authorization])
    }

    @Test
    fun `API key is used when bearer token is absent`() {
        val headers = proxyAuthenticationHeaders { name ->
            if (name == "MCP_API_KEY") " api-key " else null
        }

        assertEquals("api-key", headers["X-API-Key"])
    }

    @Test
    fun `bearer token takes precedence over API key`() {
        val headers = proxyAuthenticationHeaders { name ->
            when (name) {
                "MCP_AUTH_TOKEN" -> "bearer"
                "MCP_API_KEY" -> "api-key"
                else -> null
            }
        }

        assertEquals(mapOf(HttpHeaders.Authorization to "Bearer bearer"), headers)
    }

    @Test
    fun `disconnected proxy advertises tool capability`() {
        val capabilities = disconnectedServerCapabilities()

        assertNotNull(capabilities.tools)
        assertTrue(capabilities.tools?.listChanged == true)
    }
}
