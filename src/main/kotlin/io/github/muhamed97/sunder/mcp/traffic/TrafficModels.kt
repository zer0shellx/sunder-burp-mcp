package io.github.muhamed97.sunder.mcp.traffic

import kotlinx.serialization.Serializable

data class CapturedRequest(
    val messageId: Int,
    val capturedAt: Long,
    val tool: String,
    val inScope: Boolean,
    val method: String,
    val url: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val httpVersion: String,
    val rawRequest: ByteArray,
)

data class CapturedResponse(
    val messageId: Int,
    val completedAt: Long,
    val statusCode: Int,
    val mimeType: String,
    val rawResponse: ByteArray,
    val initiatingRequest: CapturedRequest,
)

data class CapturedWebSocketMessage(
    val connectionId: Long,
    val capturedAt: Long,
    val tool: String,
    val inScope: Boolean,
    val url: String,
    val host: String,
    val direction: String,
    val messageType: String,
    val payload: ByteArray,
)

@Serializable
data class TrafficSummary(
    val id: Long,
    val sequence: Long,
    val timestamp: Long,
    val tool: String,
    val inScope: Boolean,
    val method: String,
    val url: String,
    val host: String,
    val port: Int,
    val secure: Boolean,
    val httpVersion: String,
    val statusCode: Int?,
    val mimeType: String?,
    val requestSize: Int,
    val responseSize: Int?,
)

data class TrafficExchange(
    val summary: TrafficSummary,
    val request: ByteArray,
    val response: ByteArray?,
)

@Serializable
data class WebSocketSummary(
    val id: Long,
    val sequence: Long,
    val connectionId: Long,
    val timestamp: Long,
    val tool: String,
    val inScope: Boolean,
    val url: String,
    val host: String,
    val direction: String,
    val messageType: String,
    val payloadSize: Int,
)

data class WebSocketRecord(
    val summary: WebSocketSummary,
    val payload: ByteArray,
)

@Serializable
data class EndpointSummary(
    val method: String,
    val url: String,
    val host: String,
    val firstSequence: Long,
    val latestSequence: Long,
    val observations: Long,
    val responseVariants: Long,
    val statusCodes: String,
)

@Serializable
data class TrafficStatistics(
    val exchanges: Long,
    val completed: Long,
    val inScope: Long,
    val webSocketMessages: Long,
    val firstSequence: Long?,
    val latestSequence: Long?,
    val memoryBytes: Long,
    val maxSessionEvents: Int,
    val storageMode: String = "SESSION_MEMORY_ONLY",
    val droppedCaptureEvents: Long,
)
