package net.portswigger.mcp.traffic

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Registration
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.websocket.BinaryMessage
import burp.api.montoya.websocket.BinaryMessageAction
import burp.api.montoya.websocket.MessageHandler
import burp.api.montoya.websocket.TextMessage
import burp.api.montoya.websocket.TextMessageAction
import burp.api.montoya.websocket.WebSocketCreated
import burp.api.montoya.websocket.WebSocketCreatedHandler
import net.portswigger.mcp.config.McpConfig
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class LiveTrafficRecorder(
    private val api: MontoyaApi,
    private val store: TrafficStore,
    private val config: McpConfig,
) : HttpHandler, WebSocketCreatedHandler, AutoCloseable {
    private val droppedEvents = AtomicLong()
    private val importedMessageId = AtomicInteger(-1)
    private val websocketConnectionId = AtomicLong()
    private val storedEventCount = AtomicLong()
    private val websocketRegistrations = CopyOnWriteArrayList<Registration>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(20_000),
        { runnable -> Thread(runnable, "sunder-traffic-store").apply { isDaemon = true } },
        { _, _ -> droppedEvents.incrementAndGet() },
    )
    private var registration: Registration? = null
    private var websocketCreationRegistration: Registration? = null

    fun start() {
        registration = api.http().registerHttpHandler(this)
        websocketCreationRegistration = api.websockets().registerWebSocketCreatedHandler(this)
        if (config.importProxyHistory) importExistingProxyHistory()
    }

    fun droppedEventCount(): Long = droppedEvents.get()

    override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
        if (!config.captureHttp || (config.scopeOnlyCapture && !request.isInScope)) {
            return RequestToBeSentAction.continueWith(request)
        }
        val captured = request.toCapturedRequest(request.messageId(), request.toolSource().toolType().name)
        submitStorage { store.saveRequest(captured) }
        return RequestToBeSentAction.continueWith(request)
    }

    override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
        val initiating = response.initiatingRequest()
        if (!config.captureHttp || (config.scopeOnlyCapture && !initiating.isInScope)) {
            return ResponseReceivedAction.continueWith(response)
        }
        val initiatingRequest = initiating.toCapturedRequest(
            response.messageId(),
            response.toolSource().toolType().name,
        )
        val captured = CapturedResponse(
            messageId = response.messageId(),
            completedAt = Instant.now().toEpochMilli(),
            statusCode = response.statusCode().toInt(),
            mimeType = response.mimeType().name,
            rawResponse = response.toByteArray().getBytes(),
            initiatingRequest = initiatingRequest,
        )
        submitStorage { store.saveResponse(captured) }
        return ResponseReceivedAction.continueWith(response)
    }

    override fun handleWebSocketCreated(event: WebSocketCreated) {
        if (!config.captureWebSockets) return
        val request = event.upgradeRequest()
        if (config.scopeOnlyCapture && !request.isInScope) return
        val connectionId = websocketConnectionId.incrementAndGet()
        val tool = event.toolSource().toolType().name
        val registrationReference = AtomicReference<Registration>()
        val registration = event.webSocket().registerMessageHandler(object : MessageHandler {
            override fun handleTextMessage(message: TextMessage): TextMessageAction {
                if (config.captureWebSockets) {
                    saveWebSocket(
                        connectionId = connectionId,
                        request = request,
                        tool = tool,
                        direction = message.direction().name,
                        messageType = "TEXT",
                        payload = message.payload().toByteArray(StandardCharsets.UTF_8),
                    )
                }
                return TextMessageAction.continueWith(message)
            }

            override fun handleBinaryMessage(message: BinaryMessage): BinaryMessageAction {
                if (config.captureWebSockets) {
                    saveWebSocket(
                        connectionId = connectionId,
                        request = request,
                        tool = tool,
                        direction = message.direction().name,
                        messageType = "BINARY",
                        payload = message.payload().getBytes(),
                    )
                }
                return BinaryMessageAction.continueWith(message)
            }

            override fun onClose() {
                registrationReference.getAndSet(null)?.let { closedRegistration ->
                    websocketRegistrations.remove(closedRegistration)
                    runCatching { closedRegistration.deregister() }
                }
            }
        })
        registrationReference.set(registration)
        websocketRegistrations += registration
    }

    private fun saveWebSocket(
        connectionId: Long,
        request: HttpRequest,
        tool: String,
        direction: String,
        messageType: String,
        payload: ByteArray,
    ) {
        submitStorage {
            store.saveWebSocketMessage(
                CapturedWebSocketMessage(
                    connectionId = connectionId,
                    capturedAt = Instant.now().toEpochMilli(),
                    tool = tool,
                    inScope = request.isInScope,
                    url = request.url(),
                    host = request.httpService().host(),
                    direction = direction,
                    messageType = messageType,
                    payload = payload,
                )
            )
        }
    }

    private fun importExistingProxyHistory() {
        val history = runCatching { api.proxy().history() }
            .onFailure { api.logging().logToError("Unable to read existing Proxy history: ${it.message}") }
            .getOrElse { return }

        submit {
            var imported = 0
            history.forEach { item ->
                runCatching { importHistoryItem(item) }
                    .onSuccess { imported++ }
                    .onFailure { api.logging().logToError("Unable to import a Proxy history item: ${it.message}") }
            }
            api.logging().logToOutput("SUNDER imported $imported existing Proxy history items")
        }
    }

    private fun importHistoryItem(item: ProxyHttpRequestResponse) {
        val messageId = importedMessageId.getAndDecrement()
        val request = item.request().toCapturedRequest(messageId, "PROXY")
        val response = item.response()?.let {
            CapturedResponse(
                messageId = messageId,
                completedAt = Instant.now().toEpochMilli(),
                statusCode = it.statusCode().toInt(),
                mimeType = it.mimeType().name,
                rawResponse = it.toByteArray().getBytes(),
                initiatingRequest = request,
            )
        }
        store.saveExchange(request, response)
    }

    private fun HttpRequest.toCapturedRequest(messageId: Int, tool: String) = CapturedRequest(
        messageId = messageId,
        capturedAt = Instant.now().toEpochMilli(),
        tool = tool,
        inScope = isInScope,
        method = method(),
        url = url(),
        host = httpService().host(),
        port = httpService().port(),
        secure = httpService().secure(),
        httpVersion = httpVersion(),
        rawRequest = toByteArray().getBytes(),
    )

    private fun submitStorage(action: () -> Unit) {
        submit {
            action()
            val count = storedEventCount.incrementAndGet()
            if (count % 5_000L == 0L) {
                api.logging().logToOutput("SUNDER captured $count session events")
            }
        }
    }

    private fun submit(action: () -> Unit) {
        executor.execute {
            runCatching(action)
                .onFailure { api.logging().logToError("SUNDER traffic storage error: ${it.message}") }
        }
    }

    override fun close() {
        registration?.deregister()
        registration = null
        websocketCreationRegistration?.deregister()
        websocketCreationRegistration = null
        websocketRegistrations.forEach { registration ->
            runCatching { registration.deregister() }
        }
        websocketRegistrations.clear()
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            api.logging().logToError("SUNDER storage queue did not finish before unload")
        }
    }
}
