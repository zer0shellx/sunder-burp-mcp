package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.traffic.LiveTrafficRecorder
import net.portswigger.mcp.traffic.TrafficExchange
import net.portswigger.mcp.traffic.TrafficStore
import java.nio.charset.StandardCharsets
import java.util.Base64

fun Server.registerLiveTrafficTools(
    api: MontoyaApi,
    config: McpConfig,
    store: TrafficStore,
    recorder: LiveTrafficRecorder,
) {
    mcpTool<TrafficGetLatest>(
        "Returns compact metadata for the newest session-captured HTTP exchanges. " +
            "Bodies are omitted; call traffic_get_exchange for one full exchange.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        Json.encodeToString(
            store.list(
                afterSequence = 0,
                limit = limit.normalizedLimit(),
                tool = tool,
                scopeOnly = scopeOnly,
                newestFirst = true,
            )
        )
    }

    mcpTool<TrafficGetSince>(
        "Returns compact metadata for locally captured HTTP exchanges after a sequence number, oldest first. " +
            "Use this to poll incrementally without rereading Burp Proxy history.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        Json.encodeToString(
            store.list(
                afterSequence = afterSequence.coerceAtLeast(0),
                limit = limit.normalizedLimit(),
                tool = tool,
                scopeOnly = scopeOnly,
                newestFirst = false,
            )
        )
    }

    mcpTool<TrafficSearch>(
        "Searches URL, method, host, raw request, and raw response across the complete in-memory session feed. " +
            "Returns compact metadata only.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        require(query.isNotBlank()) { "query must not be blank" }
        require(query.length <= 1_000) { "query must be 1,000 characters or fewer" }
        Json.encodeToString(
            store.search(
                query = query,
                afterSequence = afterSequence.coerceAtLeast(0),
                limit = limit.normalizedLimit(),
                tool = tool,
                scopeOnly = scopeOnly,
            )
        )
    }

    mcpTool<TrafficGetExchange>(
        "Returns one full request/response exchange from the current session feed. " +
            "Sensitive authentication headers are redacted unless includeSensitive is explicitly true.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        val exchange = store.getExchange(id) ?: return@mcpTool "Traffic exchange $id was not found"
        exchange.toJson(
            includeSensitive = includeSensitive,
            maxMessageBytes = maxMessageBytes.coerceIn(1_024, 2_000_000),
        )
    }

    mcpTool<TrafficListEndpoints>(
        "Lists unique HTTP method/URL endpoints across the current session with observation, " +
            "status-code, and response-variant counts.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        Json.encodeToString(
            store.listEndpoints(
                query = query,
                afterSequence = 0,
                limit = limit.normalizedLimit(),
                scopeOnly = scopeOnly,
                changedOnly = false,
            )
        )
    }

    mcpTool<TrafficGetChangedEndpoints>(
        "Lists endpoints observed after a sequence number that produced multiple status/response-size variants.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        Json.encodeToString(
            store.listEndpoints(
                query = null,
                afterSequence = afterSequence.coerceAtLeast(0),
                limit = limit.normalizedLimit(),
                scopeOnly = scopeOnly,
                changedOnly = true,
            )
        )
    }

    mcpTool<TrafficCreateRepeaterTab>(
        "Creates a Burp Repeater tab from a stored request without sending it. " +
            "An optional replacement request can be supplied for AI edits and manual approval.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        val exchange = store.getExchange(id) ?: return@mcpTool "Traffic exchange $id was not found"
        val summary = exchange.summary
        val service = HttpService.httpService(summary.host, summary.port, summary.secure)
        val raw = replacementRequest?.let(::normalizeHttpContent)
            ?: String(exchange.request, StandardCharsets.ISO_8859_1)
        val request = HttpRequest.httpRequest(service, raw)
        api.repeater().sendToRepeater(request, tabName ?: "AI Review - $id")
        "Created Repeater tab for traffic exchange $id; the request has not been sent"
    }

    mcpTool<WebSocketGetLatest>(
        "Returns compact metadata for locally captured WebSocket messages from all Burp tools.",
    ) {
        if (!allowWebSocketHistory(config, api)) return@mcpTool "WebSocket traffic access denied by Burp Suite"
        Json.encodeToString(
            store.listWebSocket(
                afterSequence = afterSequence.coerceAtLeast(0),
                limit = limit.normalizedLimit(),
                tool = tool,
                scopeOnly = scopeOnly,
                newestFirst = afterSequence == 0L,
            )
        )
    }

    mcpTool<WebSocketSearch>(
        "Searches URLs and text payloads across the current in-memory WebSocket session feed.",
    ) {
        if (!allowWebSocketHistory(config, api)) return@mcpTool "WebSocket traffic access denied by Burp Suite"
        require(query.isNotBlank()) { "query must not be blank" }
        require(query.length <= 1_000) { "query must be 1,000 characters or fewer" }
        Json.encodeToString(
            store.searchWebSocket(
                query = query,
                afterSequence = afterSequence.coerceAtLeast(0),
                limit = limit.normalizedLimit(),
                scopeOnly = scopeOnly,
            )
        )
    }

    mcpTool<WebSocketGetMessage>(
        "Returns one complete locally captured WebSocket message. Binary payloads are returned as Base64.",
    ) {
        if (!allowWebSocketHistory(config, api)) return@mcpTool "WebSocket traffic access denied by Burp Suite"
        val record = store.getWebSocketMessage(id)
            ?: return@mcpTool "WebSocket message $id was not found"
        val selected = if (record.payload.size <= maxBytes.coerceIn(1_024, 2_000_000)) {
            record.payload
        } else {
            record.payload.copyOf(maxBytes.coerceIn(1_024, 2_000_000))
        }
        buildJsonObject {
            put("metadata", Json.encodeToJsonElement(record.summary))
            if (record.summary.messageType == "TEXT") {
                put("payload", String(selected, StandardCharsets.UTF_8))
                put("encoding", "utf-8")
            } else {
                put("payload", Base64.getEncoder().encodeToString(selected))
                put("encoding", "base64")
            }
            put("truncated", record.payload.size > selected.size)
        }.toString()
    }

    mcpTool(
        "traffic_statistics",
        "Returns session traffic counts, memory usage, latest sequence, configured event limit, and dropped events.",
    ) {
        if (!allowLiveHistory(config, api)) return@mcpTool "Live traffic access denied by Burp Suite"
        Json.encodeToString(store.statistics(recorder.droppedEventCount()))
    }

    mcpTool<AgentGetEvents>(
        "Returns new HTTP exchanges and WebSocket messages together for an autonomous agent loop. " +
            "Call repeatedly with the returned next sequence values.",
    ) {
        if (!allowLiveHistory(config, api) || !allowWebSocketHistory(config, api)) {
            return@mcpTool "Live session traffic access denied by Burp Suite"
        }
        val eventLimit = limit.normalizedLimit()
        val http = store.list(
            afterSequence = afterHttpSequence.coerceAtLeast(0),
            limit = eventLimit,
            tool = null,
            scopeOnly = scopeOnly,
            newestFirst = false,
        )
        val webSockets = store.listWebSocket(
            afterSequence = afterWebSocketSequence.coerceAtLeast(0),
            limit = eventLimit,
            tool = null,
            scopeOnly = scopeOnly,
            newestFirst = false,
        )
        buildJsonObject {
            put("http", Json.encodeToJsonElement(http))
            put("websockets", Json.encodeToJsonElement(webSockets))
            put("next_http_sequence", http.lastOrNull()?.sequence ?: afterHttpSequence)
            put("next_websocket_sequence", webSockets.lastOrNull()?.sequence ?: afterWebSocketSequence)
            put("has_more_http", http.size == eventLimit)
            put("has_more_websockets", webSockets.size == eventLimit)
        }.toString()
    }
}

private fun allowLiveHistory(config: McpConfig, api: MontoyaApi): Boolean = runBlocking {
    val allowed = DataAccessSecurity.checkDataAccessPermission(DataAccessType.HTTP_HISTORY, config)
    api.logging().logToOutput(
        "MCP live traffic access ${if (allowed) "granted" else "denied"}"
    )
    allowed
}

private fun allowWebSocketHistory(config: McpConfig, api: MontoyaApi): Boolean = runBlocking {
    val allowed = DataAccessSecurity.checkDataAccessPermission(DataAccessType.WEBSOCKET_HISTORY, config)
    api.logging().logToOutput(
        "MCP live WebSocket traffic access ${if (allowed) "granted" else "denied"}"
    )
    allowed
}

private fun Int.normalizedLimit() = coerceIn(1, 200)

private fun TrafficExchange.toJson(
    includeSensitive: Boolean,
    maxMessageBytes: Int,
): String {
    val requestText = request.renderHttpMessage(maxMessageBytes, includeSensitive)
    val responseText = response?.renderHttpMessage(maxMessageBytes, includeSensitive)
    return buildJsonObject {
        put("metadata", Json.encodeToJsonElement(summary))
        put("request", requestText)
        responseText?.let { put("response", it) }
        put("request_truncated", request.size > maxMessageBytes)
        put("response_truncated", response != null && response.size > maxMessageBytes)
    }.toString()
}

private fun ByteArray.renderHttpMessage(maxBytes: Int, includeSensitive: Boolean): String {
    val selected = if (size <= maxBytes) this else copyOf(maxBytes)
    val text = String(selected, StandardCharsets.ISO_8859_1)
    return if (includeSensitive) text else redactSensitiveHeaders(text)
}

private fun redactSensitiveHeaders(message: String): String {
    val sensitiveHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "x-auth-token",
    )
    val headerEnd = sequenceOf(message.indexOf("\r\n\r\n"), message.indexOf("\n\n"))
        .filter { it >= 0 }
        .minOrNull() ?: message.length
    val headers = message.substring(0, headerEnd)
    val body = message.substring(headerEnd)
    val redacted = headers.lineSequence().joinToString("\r\n") { line ->
        val separator = line.indexOf(':')
        if (separator > 0 && line.substring(0, separator).trim().lowercase() in sensitiveHeaders) {
            "${line.substring(0, separator)}: <redacted>"
        } else {
            line.trimEnd('\r')
        }
    }
    return redacted + body
}

@Serializable
data class TrafficGetLatest(
    val limit: Int = 50,
    val tool: String? = null,
    val scopeOnly: Boolean = true,
)

@Serializable
data class TrafficGetSince(
    val afterSequence: Long,
    val limit: Int = 50,
    val tool: String? = null,
    val scopeOnly: Boolean = true,
)

@Serializable
data class TrafficSearch(
    val query: String,
    val afterSequence: Long = 0,
    val limit: Int = 50,
    val tool: String? = null,
    val scopeOnly: Boolean = true,
)

@Serializable
data class TrafficGetExchange(
    val id: Long,
    val includeSensitive: Boolean = false,
    val maxMessageBytes: Int = 500_000,
)

@Serializable
data class TrafficListEndpoints(
    val query: String? = null,
    val limit: Int = 100,
    val scopeOnly: Boolean = true,
)

@Serializable
data class TrafficGetChangedEndpoints(
    val afterSequence: Long,
    val limit: Int = 100,
    val scopeOnly: Boolean = true,
)

@Serializable
data class TrafficCreateRepeaterTab(
    val id: Long,
    val tabName: String? = null,
    val replacementRequest: String? = null,
)

@Serializable
data class WebSocketGetLatest(
    val afterSequence: Long = 0,
    val limit: Int = 50,
    val tool: String? = null,
    val scopeOnly: Boolean = true,
)

@Serializable
data class WebSocketSearch(
    val query: String,
    val afterSequence: Long = 0,
    val limit: Int = 50,
    val scopeOnly: Boolean = true,
)

@Serializable
data class WebSocketGetMessage(
    val id: Long,
    val maxBytes: Int = 500_000,
)

@Serializable
data class AgentGetEvents(
    val afterHttpSequence: Long = 0,
    val afterWebSocketSequence: Long = 0,
    val limit: Int = 100,
    val scopeOnly: Boolean = true,
)
