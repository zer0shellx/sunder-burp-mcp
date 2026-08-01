package io.github.muhamed97.sunder.mcp

import io.modelcontextprotocol.kotlin.sdk.CallToolResultBase
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

/**
 * Gated smoke test for a real, user-started Burp instance.
 *
 * Run only against the local test endpoint:
 *   SUNDER_LIVE_E2E=true ./gradlew test --tests "*.LiveBurpWorkflowE2ETest"
 */
@EnabledIfEnvironmentVariable(named = "SUNDER_LIVE_E2E", matches = "true")
class LiveBurpWorkflowE2ETest {
    private val client = TestSseMcpClient()
    private val mcpUrl = System.getenv("SUNDER_MCP_URL")
        ?: System.getenv("BURP_LIVE_MCP_URL")
        ?: "http://127.0.0.1:9987"
    private val targetHost = "127.0.0.1"
    private val targetPort = 18080

    @Test
    fun `real Burp event to action workflow`() = runBlocking {
        val marker = UUID.randomUUID().toString()
        val repeaterName = "MCP-E2E-${marker.take(8)}"

        try {
            client.connectToServer(mcpUrl)
            val tools = client.listTools().map { it.name }.toSet()
            val requiredTools = setOf(
                "send_http1_request",
                "agent_get_events",
                "traffic_get_exchange",
                "request_modify_and_send",
                "traffic_create_repeater_tab",
                "request_send_batch",
                "scope_check",
            )
            assertTrue(tools.containsAll(requiredTools), "Missing tools: ${requiredTools - tools}")

            val localScope = text(
                client.callTool(
                    "scope_check",
                    mapOf("url" to "http://$targetHost:$targetPort/"),
                )
            )
            assertTrue(
                localScope.contains("\"in_scope\":true"),
                "Add http://$targetHost:$targetPort to Burp suite scope before running this test: $localScope",
            )

            val initialRequest = rawRequest(
                path = "/initial?marker=$marker",
                marker = "initial-$marker",
            )
            val sendStartedAt = System.nanoTime()
            val initialResponse = text(
                client.callTool(
                    "send_http1_request",
                    mapOf(
                        "content" to initialRequest,
                        "targetHostname" to targetHost,
                        "targetPort" to targetPort,
                        "usesHttps" to false,
                    ),
                )
            )
            assertTrue(initialResponse.contains("200"), "Initial request did not receive HTTP 200: $initialResponse")

            val initialEvent = awaitHttpEvent(marker, afterSequence = 0)
            val captureLatencyMs = (System.nanoTime() - sendStartedAt) / 1_000_000
            assertTrue(captureLatencyMs < 5_000, "Live event capture took ${captureLatencyMs}ms")
            val initialId = initialEvent["id"]!!.jsonPrimitive.long
            val initialSequence = initialEvent["sequence"]!!.jsonPrimitive.long

            val fullExchange = text(
                client.callTool(
                    "traffic_get_exchange",
                    mapOf(
                        "id" to initialId,
                        "includeSensitive" to true,
                        "maxMessageBytes" to 100_000,
                    ),
                )
            )
            assertTrue(fullExchange.contains("initial-$marker"), "Pending event request was not retrievable")

            val modifiedRequest = rawRequest(
                path = "/modified?marker=$marker",
                marker = "modified-$marker",
            )
            val modifiedResponse = text(
                client.callTool(
                    "request_modify_and_send",
                    mapOf(
                        "id" to initialId,
                        "replacementRequest" to modifiedRequest,
                        "maxResponseBytes" to 100_000,
                    ),
                )
            )
            assertTrue(modifiedResponse.contains("\"status_code\":200"), "Modified request failed: $modifiedResponse")

            val modifiedEvent = awaitHttpEvent("modified?marker=$marker", initialSequence)
            val modifiedId = modifiedEvent["id"]!!.jsonPrimitive.long
            val modifiedExchange = text(
                client.callTool(
                    "traffic_get_exchange",
                    mapOf(
                        "id" to modifiedId,
                        "includeSensitive" to true,
                        "maxMessageBytes" to 100_000,
                    ),
                )
            )
            assertTrue(modifiedExchange.contains("modified-$marker"), "Modified request was not captured")

            val repeaterResult = text(
                client.callTool(
                    "traffic_create_repeater_tab",
                    mapOf(
                        "id" to initialId,
                        "tabName" to repeaterName,
                    ),
                )
            )
            assertTrue(repeaterResult.contains("Created Repeater tab"), "Repeater creation failed: $repeaterResult")

            val denied = text(
                client.callTool(
                    "send_http1_request",
                    mapOf(
                        "content" to "GET / HTTP/1.1\r\nHost: mcp-out-of-scope.invalid\r\nConnection: close\r\n\r\n",
                        "targetHostname" to "mcp-out-of-scope.invalid",
                        "targetPort" to 80,
                        "usesHttps" to false,
                    ),
                )
            )
            assertTrue(denied.contains("denied", ignoreCase = true), "Out-of-scope request was not denied: $denied")

            val oversizedBatch = client.callTool(
                "request_send_batch",
                mapOf(
                    "ids" to List(26) { initialId },
                    "maxResponseBytes" to 10_000,
                ),
            )
            assertNotNull(oversizedBatch)
            assertTrue(
                oversizedBatch!!.isError == true || text(oversizedBatch).contains("at most 25"),
                "The 25-request batch limit was not enforced: ${text(oversizedBatch)}",
            )

            println(
                "LIVE_BURP_E2E_OK marker=$marker captureLatencyMs=$captureLatencyMs " +
                    "initialExchangeId=$initialId modifiedExchangeId=$modifiedId repeaterTab=$repeaterName"
            )
        } finally {
            client.close()
        }
    }

    private suspend fun awaitHttpEvent(needle: String, afterSequence: Long): JsonObject {
        repeat(25) {
            val events = text(
                client.callTool(
                    "agent_get_events",
                    mapOf(
                        "afterHttpSequence" to afterSequence,
                        "afterWebSocketSequence" to 0,
                        "limit" to 200,
                        "scopeOnly" to false,
                    ),
                )
            )
            val event = Json.parseToJsonElement(events)
                .jsonObject["http"]!!
                .jsonArray
                .map { item -> item.jsonObject }
                .firstOrNull { item -> item["url"]?.jsonPrimitive?.content?.contains(needle) == true }
            if (event != null) return event
            delay(100)
        }
        error("No captured HTTP event contained: $needle")
    }

    private fun rawRequest(path: String, marker: String) =
        "GET $path HTTP/1.1\r\n" +
            "Host: $targetHost:$targetPort\r\n" +
            "X-MCP-E2E: $marker\r\n" +
            "Connection: close\r\n\r\n"

    private fun text(result: CallToolResultBase?): String =
        result?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("\n") { it.text.orEmpty() }
            .orEmpty()
}
