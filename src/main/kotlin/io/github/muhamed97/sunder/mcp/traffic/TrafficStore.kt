package io.github.muhamed97.sunder.mcp.traffic

interface TrafficStore : AutoCloseable {
    fun saveRequest(request: CapturedRequest)
    fun saveResponse(response: CapturedResponse)
    fun saveExchange(request: CapturedRequest, response: CapturedResponse?)
    fun saveWebSocketMessage(message: CapturedWebSocketMessage)

    fun list(
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
        newestFirst: Boolean,
    ): List<TrafficSummary>

    fun search(
        query: String,
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
    ): List<TrafficSummary>

    fun getExchange(id: Long): TrafficExchange?
    fun listEndpoints(
        query: String?,
        afterSequence: Long,
        limit: Int,
        scopeOnly: Boolean,
        changedOnly: Boolean,
    ): List<EndpointSummary>
    fun listWebSocket(
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
        newestFirst: Boolean,
    ): List<WebSocketSummary>

    fun searchWebSocket(
        query: String,
        afterSequence: Long,
        limit: Int,
        scopeOnly: Boolean,
    ): List<WebSocketSummary>

    fun getWebSocketMessage(id: Long): WebSocketRecord?
    fun statistics(droppedCaptureEvents: Long): TrafficStatistics
    fun clearAll()
}
