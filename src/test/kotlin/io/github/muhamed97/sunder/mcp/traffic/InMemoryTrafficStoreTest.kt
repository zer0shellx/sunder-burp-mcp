package io.github.muhamed97.sunder.mcp.traffic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryTrafficStoreTest {
    private lateinit var store: InMemoryTrafficStore

    @BeforeEach
    fun setUp() {
        store = InMemoryTrafficStore { 0 }
    }

    @Test
    fun `stores and completes an exchange`() {
        val request = request(42, "https://example.test/api/users")
        store.saveRequest(request)
        store.saveResponse(response(request, 200, "{}"))

        val summaries = store.list(0, 10, null, true, true)
        assertEquals(1, summaries.size)
        assertEquals(200, summaries.single().statusCode)
        val exchange = store.getExchange(summaries.single().id)
        assertNotNull(exchange)
        assertTrue(String(exchange!!.request).contains("/api/users"))
    }

    @Test
    fun `searches complete raw session traffic and supports cursors`() {
        store.saveRequest(request(1, "https://example.test/first", "alpha"))
        store.saveRequest(request(2, "https://example.test/second", "needle"))

        val all = store.list(0, 10, null, true, false)
        val matches = store.search("needle", 0, 10, null, true)
        val since = store.list(all.first().sequence, 10, null, true, false)

        assertEquals(1, matches.size)
        assertTrue(matches.single().url.endsWith("/second"))
        assertEquals(1, since.size)
    }

    @Test
    fun `stores websocket messages only in memory`() {
        store.saveWebSocketMessage(
            CapturedWebSocketMessage(
                7, 5_000, "PROXY", true,
                "wss://example.test/socket", "example.test",
                "SERVER_TO_CLIENT", "TEXT",
                """{"event":"updated"}""".toByteArray(),
            )
        )

        val matches = store.searchWebSocket("updated", 0, 10, true)
        assertEquals(1, matches.size)
        assertTrue(String(store.getWebSocketMessage(matches.single().id)!!.payload).contains("updated"))
    }

    @Test
    fun `aggregates endpoints and response variants`() {
        val first = request(10, "https://example.test/api/items")
        val second = request(11, "https://example.test/api/items")
        store.saveExchange(first, response(first, 200, "[]"))
        store.saveExchange(second, response(second, 403, "{}"))

        val endpoints = store.listEndpoints("/api/items", 0, 10, true, false)
        val changed = store.listEndpoints(null, 0, 10, true, true)

        assertEquals(1, endpoints.size)
        assertEquals(2, endpoints.single().observations)
        assertEquals(2, endpoints.single().responseVariants)
        assertEquals(1, changed.size)
    }

    @Test
    fun `optional event limit evicts the oldest session data`() {
        var limit = 2
        val limited = InMemoryTrafficStore { limit }
        limited.saveRequest(request(1, "https://example.test/one"))
        limited.saveRequest(request(2, "https://example.test/two"))
        limited.saveRequest(request(3, "https://example.test/three"))

        val rows = limited.list(0, 10, null, false, false)
        assertEquals(2, rows.size)
        assertTrue(rows.first().url.endsWith("/two"))

        limit = 1
        limited.saveRequest(request(4, "https://example.test/four"))
        assertEquals(1, limited.statistics(0).exchanges)
    }

    @Test
    fun `clear removes extension session data`() {
        store.saveRequest(request(1, "https://example.test/one"))
        store.clearAll()
        assertTrue(store.list(0, 10, null, false, true).isEmpty())
    }

    private fun request(messageId: Int, url: String, body: String = ""): CapturedRequest {
        val uri = java.net.URI(url)
        val raw = "POST ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n$body".toByteArray()
        return CapturedRequest(
            messageId, 1_000L + messageId, "PROXY", true, "POST", url,
            uri.host, 443, true, "HTTP/1.1", raw,
        )
    }

    private fun response(request: CapturedRequest, status: Int, body: String) = CapturedResponse(
        request.messageId,
        2_000,
        status,
        "JSON",
        "HTTP/1.1 $status Result\r\n\r\n$body".toByteArray(),
        request,
    )
}
