package net.portswigger.mcp.traffic

import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class InMemoryTrafficStore(
    private val maxSessionEvents: () -> Int,
) : TrafficStore {
    private data class StoredHttp(
        val id: Long,
        val request: CapturedRequest,
        var response: CapturedResponse?,
    )

    private data class StoredWebSocket(
        val id: Long,
        val message: CapturedWebSocketMessage,
    )

    private val lock = ReentrantReadWriteLock()
    private val httpSequence = AtomicLong()
    private val webSocketSequence = AtomicLong()
    private val http = LinkedHashMap<Long, StoredHttp>()
    private val webSockets = LinkedHashMap<Long, StoredWebSocket>()
    private val pendingByMessageId = mutableMapOf<Int, ArrayDeque<Long>>()

    override fun saveRequest(request: CapturedRequest) = lock.write {
        val id = httpSequence.incrementAndGet()
        http[id] = StoredHttp(id, request, null)
        pendingByMessageId.getOrPut(request.messageId, ::ArrayDeque).addLast(id)
        trimIfNeeded()
    }

    override fun saveResponse(response: CapturedResponse) = lock.write {
        val pending = pendingByMessageId[response.messageId]
        var stored: StoredHttp? = null
        while (pending != null && pending.isNotEmpty() && stored == null) {
            stored = http[pending.removeLast()]
        }
        if (pending?.isEmpty() == true) pendingByMessageId.remove(response.messageId)
        if (stored != null) {
            stored.response = response
        } else {
            val id = httpSequence.incrementAndGet()
            http[id] = StoredHttp(id, response.initiatingRequest, response)
        }
        trimIfNeeded()
    }

    override fun saveExchange(request: CapturedRequest, response: CapturedResponse?) = lock.write {
        val id = httpSequence.incrementAndGet()
        http[id] = StoredHttp(id, request, response)
        if (response == null) {
            pendingByMessageId.getOrPut(request.messageId, ::ArrayDeque).addLast(id)
        }
        trimIfNeeded()
    }

    override fun saveWebSocketMessage(message: CapturedWebSocketMessage) = lock.write {
        val id = webSocketSequence.incrementAndGet()
        webSockets[id] = StoredWebSocket(id, message)
        trimIfNeeded()
    }

    override fun list(
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
        newestFirst: Boolean,
    ): List<TrafficSummary> = lock.read {
        filteredHttp(afterSequence, tool, scopeOnly)
            .let { if (newestFirst) it.asReversed() else it }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .map(::toSummary)
    }

    override fun search(
        query: String,
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
    ): List<TrafficSummary> = lock.read {
        val needle = query.lowercase()
        filteredHttp(afterSequence, tool, scopeOnly)
            .asReversed()
            .asSequence()
            .filter { stored ->
                stored.request.url.lowercase().contains(needle) ||
                    stored.request.method.lowercase().contains(needle) ||
                    stored.request.host.lowercase().contains(needle) ||
                    String(stored.request.rawRequest, StandardCharsets.ISO_8859_1)
                        .lowercase().contains(needle) ||
                    stored.response?.rawResponse?.let {
                        String(it, StandardCharsets.ISO_8859_1).lowercase().contains(needle)
                    } == true
            }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .map(::toSummary)
            .toList()
    }

    override fun getExchange(id: Long): TrafficExchange? = lock.read {
        http[id]?.let { stored ->
            TrafficExchange(
                summary = toSummary(stored),
                request = stored.request.rawRequest.copyOf(),
                response = stored.response?.rawResponse?.copyOf(),
            )
        }
    }

    override fun listEndpoints(
        query: String?,
        afterSequence: Long,
        limit: Int,
        scopeOnly: Boolean,
        changedOnly: Boolean,
    ): List<EndpointSummary> = lock.read {
        val needle = query?.lowercase()
        http.values.asSequence()
            .filter { it.id > afterSequence }
            .filter { !scopeOnly || it.request.inScope }
            .filter {
                needle.isNullOrBlank() ||
                    it.request.url.lowercase().contains(needle) ||
                    it.request.host.lowercase().contains(needle)
            }
            .groupBy { Triple(it.request.method, it.request.url, it.request.host) }
            .map { (key, records) ->
                val variants = records.map {
                    (it.response?.statusCode ?: -1) to (it.response?.rawResponse?.size ?: -1)
                }.toSet()
                EndpointSummary(
                    method = key.first,
                    url = key.second,
                    host = key.third,
                    firstSequence = records.minOf { it.id },
                    latestSequence = records.maxOf { it.id },
                    observations = records.size.toLong(),
                    responseVariants = variants.size.toLong(),
                    statusCodes = records.mapNotNull { it.response?.statusCode }
                        .distinct().sorted().joinToString(","),
                )
            }
            .filter { !changedOnly || it.responseVariants > 1 }
            .sortedByDescending { it.latestSequence }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .toList()
    }

    override fun listWebSocket(
        afterSequence: Long,
        limit: Int,
        tool: String?,
        scopeOnly: Boolean,
        newestFirst: Boolean,
    ): List<WebSocketSummary> = lock.read {
        webSockets.values.asSequence()
            .filter { it.id > afterSequence }
            .filter { tool.isNullOrBlank() || it.message.tool.equals(tool, ignoreCase = true) }
            .filter { !scopeOnly || it.message.inScope }
            .toList()
            .let { if (newestFirst) it.asReversed() else it }
            .take(limit.coerceIn(1, MAX_RESULTS))
            .map(::toWebSocketSummary)
    }

    override fun searchWebSocket(
        query: String,
        afterSequence: Long,
        limit: Int,
        scopeOnly: Boolean,
    ): List<WebSocketSummary> = lock.read {
        val needle = query.lowercase()
        webSockets.values.asSequence()
            .filter { it.id > afterSequence }
            .filter { !scopeOnly || it.message.inScope }
            .filter {
                it.message.url.lowercase().contains(needle) ||
                    String(it.message.payload, StandardCharsets.UTF_8).lowercase().contains(needle)
            }
            .toList()
            .asReversed()
            .take(limit.coerceIn(1, MAX_RESULTS))
            .map(::toWebSocketSummary)
    }

    override fun getWebSocketMessage(id: Long): WebSocketRecord? = lock.read {
        webSockets[id]?.let {
            WebSocketRecord(toWebSocketSummary(it), it.message.payload.copyOf())
        }
    }

    override fun statistics(droppedCaptureEvents: Long): TrafficStatistics = lock.read {
        val memoryBytes = http.values.sumOf {
            it.request.rawRequest.size.toLong() + (it.response?.rawResponse?.size ?: 0)
        } + webSockets.values.sumOf { it.message.payload.size.toLong() }
        TrafficStatistics(
            exchanges = http.size.toLong(),
            completed = http.values.count { it.response != null }.toLong(),
            inScope = http.values.count { it.request.inScope }.toLong(),
            webSocketMessages = webSockets.size.toLong(),
            firstSequence = http.keys.firstOrNull(),
            latestSequence = http.keys.lastOrNull(),
            memoryBytes = memoryBytes,
            maxSessionEvents = maxSessionEvents().coerceAtLeast(0),
            droppedCaptureEvents = droppedCaptureEvents,
        )
    }

    override fun clearAll() = lock.write {
        http.clear()
        webSockets.clear()
        pendingByMessageId.clear()
    }

    private fun filteredHttp(
        afterSequence: Long,
        tool: String?,
        scopeOnly: Boolean,
    ) = http.values.asSequence()
        .filter { it.id > afterSequence }
        .filter { tool.isNullOrBlank() || it.request.tool.equals(tool, ignoreCase = true) }
        .filter { !scopeOnly || it.request.inScope }
        .toList()

    private fun toSummary(stored: StoredHttp) = TrafficSummary(
        id = stored.id,
        sequence = stored.id,
        timestamp = stored.request.capturedAt,
        tool = stored.request.tool,
        inScope = stored.request.inScope,
        method = stored.request.method,
        url = stored.request.url,
        host = stored.request.host,
        port = stored.request.port,
        secure = stored.request.secure,
        httpVersion = stored.request.httpVersion,
        statusCode = stored.response?.statusCode,
        mimeType = stored.response?.mimeType,
        requestSize = stored.request.rawRequest.size,
        responseSize = stored.response?.rawResponse?.size,
    )

    private fun toWebSocketSummary(stored: StoredWebSocket) = WebSocketSummary(
        id = stored.id,
        sequence = stored.id,
        connectionId = stored.message.connectionId,
        timestamp = stored.message.capturedAt,
        tool = stored.message.tool,
        inScope = stored.message.inScope,
        url = stored.message.url,
        host = stored.message.host,
        direction = stored.message.direction,
        messageType = stored.message.messageType,
        payloadSize = stored.message.payload.size,
    )

    private fun trimIfNeeded() {
        val maximum = maxSessionEvents().coerceAtLeast(0)
        if (maximum == 0) return
        while (http.size + webSockets.size > maximum) {
            val oldestHttp = http.entries.firstOrNull()
            val oldestWebSocket = webSockets.entries.firstOrNull()
            if (oldestWebSocket == null ||
                (oldestHttp != null &&
                    oldestHttp.value.request.capturedAt <= oldestWebSocket.value.message.capturedAt)
            ) {
                oldestHttp?.let { http.remove(it.key) } ?: break
            } else {
                webSockets.remove(oldestWebSocket.key)
            }
        }
    }

    override fun close() = clearAll()

    companion object {
        private const val MAX_RESULTS = 500
    }
}
