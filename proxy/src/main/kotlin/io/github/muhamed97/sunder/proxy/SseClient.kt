package io.github.muhamed97.sunder.proxy

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.plugins.sse.*
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ResourceListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private val loggerClient = LoggerFactory.getLogger("io.github.muhamed97.sunder.proxy.SseClient")

class SseClient(
    private val sseUrl: String,
    clientInfo: Implementation,
    environment: (String) -> String? = System::getenv,
) : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO

    private val client = Client(clientInfo = clientInfo)
    private val authenticationHeaders = proxyAuthenticationHeaders(environment)
    private val httpClient = HttpClient {
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 30000
            connectTimeoutMillis = 15000
        }
        defaultRequest {
            authenticationHeaders.forEach { (name, value) ->
                header(name, value)
            }
        }
    }

    val connectionStateMutableStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = connectionStateMutableStateFlow.asStateFlow()

    private val reconnectAttempted = AtomicBoolean(false)
    private val maxReconnectAttempts = 3
    private var monitorJob: Job? = null
    private val operationMutex = Mutex()

    private fun createTransport(): SseClientTransport {
        return SseClientTransport(httpClient, sseUrl)
    }

    init {
        when {
            HttpHeaders.Authorization in authenticationHeaders ->
                loggerClient.info("Using Bearer token authentication")
            "X-API-Key" in authenticationHeaders ->
                loggerClient.info("Using API key authentication")
        }
    }

    private fun startMonitoring() {
        if (monitorJob == null || monitorJob?.isActive != true) {
            monitorJob = launch {
                monitorServerAvailability()
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun monitorServerAvailability() {
        loggerClient.info("Starting to monitor SSE server availability...")
        while (isActive && connectionStateMutableStateFlow.value != ConnectionState.CONNECTED) {
            delay(3000)

            if (connectionStateMutableStateFlow.value == ConnectionState.DISCONNECTED) {
                val wasConnected = tryConnect()
                if (wasConnected) {
                    val notificationsSent = sendServerAvailableNotifications()
                    if (notificationsSent) {
                        loggerClient.info("SSE server is now available at {}", sseUrl)
                        stopMonitoring()
                        break
                    }
                }
            }
        }
    }

    private suspend fun sendServerAvailableNotifications(): Boolean {
        return supervisorScope {
            try {
                withContext(SupervisorJob()) {
                    client.notification(ToolListChangedNotification())
                    client.notification(ResourceListChangedNotification())
                }
                loggerClient.info("Server available notifications sent")
                true
            } catch (e: CancellationException) {
                loggerClient.warn("Notification sending was cancelled. Treating as connection failure.")
                connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
                false
            } catch (e: Exception) {
                loggerClient.error("Failed to send notifications after server became available: {}", e.message)
                connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
                false
            }
        }
    }

    suspend fun connect(): Boolean {
        if (connectionStateMutableStateFlow.value == ConnectionState.CONNECTING) {
            return false
        }

        val connected = tryConnect()
        if (!connected) {
            startMonitoring()
        }
        return connected
    }

    private suspend fun tryConnect(): Boolean {
        connectionStateMutableStateFlow.value = ConnectionState.CONNECTING
        val transport = createTransport()

        return try {
            client.connect(transport)
            connectionStateMutableStateFlow.value = ConnectionState.CONNECTED
            reconnectAttempted.set(false)
            loggerClient.info("Successfully connected to SSE server at {}", sseUrl)
            stopMonitoring()
            true
        } catch (e: Exception) {
            connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
            loggerClient.error("Failed to connect to SSE server at {}: {}", sseUrl, e.message)
            false
        }
    }

    private suspend fun reconnectWithRetries(maxAttempts: Int): Boolean {
        if (connectionStateMutableStateFlow.value == ConnectionState.CONNECTING) {
            return false
        }

        connectionStateMutableStateFlow.value = ConnectionState.CONNECTING
        loggerClient.info("Attempting to reconnect to SSE server at {}...", sseUrl)

        for (attempt in 1..maxAttempts) {
            try {
                val transport = createTransport()
                client.connect(transport)

                connectionStateMutableStateFlow.value = ConnectionState.CONNECTED
                reconnectAttempted.set(false)
                loggerClient.info("Successfully reconnected to SSE server (attempt {}/{})", attempt, maxAttempts)

                val notificationsSuccess = supervisorScope {
                    try {
                        withContext(SupervisorJob()) {
                            client.notification(ToolListChangedNotification())
                            client.notification(ResourceListChangedNotification())
                        }
                        loggerClient.info("Successfully sent notifications after reconnection")
                        true
                    } catch (e: CancellationException) {
                        loggerClient.warn("Notification sending was cancelled after reconnection. Treating as connection failure.")
                        connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
                        false
                    } catch (e: Exception) {
                        loggerClient.error("Failed to send notifications after reconnection: {}", e.message)
                        connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
                        false
                    }
                }

                return notificationsSuccess
            } catch (e: Exception) {
                if (attempt < maxAttempts) {
                    loggerClient.warn(
                        "Reconnection attempt {}/{} failed: {}. Retrying...", attempt, maxAttempts, e.message
                    )
                    delay(1000L * attempt)
                } else {
                    loggerClient.error("Failed to reconnect after {} attempts: {}", maxAttempts, e.message)
                    connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
                }
            }
        }

        return false
    }

    suspend fun <T> withConnection(block: suspend (Client) -> T): T? = operationMutex.withLock {
        try {
            if (connectionStateMutableStateFlow.value != ConnectionState.CONNECTED || !isConnectionValid()) {
                if (!reconnectWithRetries(maxReconnectAttempts)) {
                    startMonitoring()
                    throw IOException("Not connected to SSE server")
                }
            }

            return runCatching {
                block(client)
            }.getOrElse { e ->
                run {
                    loggerClient.warn("Connection error detected: {}", e.message)
                    connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED

                    if (reconnectWithRetries(maxReconnectAttempts)) {
                        loggerClient.info("Reconnected successfully, retrying operation")
                        try {
                            block(client)
                        } catch (retryException: Exception) {
                            loggerClient.error("Operation failed after reconnection: {}", retryException.message)
                            throw retryException
                        }
                    } else {
                        startMonitoring()
                        throw IOException("Not connected to SSE server after reconnection attempts", e)
                    }
                }
            }
        } catch (e: Exception) {
            connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
            startMonitoring()
            throw IOException("Connection error during operation", e)
        }
    }

    private suspend fun isConnectionValid(): Boolean {
        return try {
            client.ping()
            true
        } catch (e: Exception) {
            loggerClient.warn("Connection validation failed: {}", e.message)
            connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED
            false
        }
    }

    fun close() {
        stopMonitoring()
        coroutineContext.cancelChildren()
        httpClient.close()
    }

    fun getClient(): Client = client
}

internal fun proxyAuthenticationHeaders(
    environment: (String) -> String?,
): Map<String, String> {
    val bearerToken = environment("MCP_AUTH_TOKEN")?.trim().orEmpty()
    if (bearerToken.isNotEmpty()) {
        return mapOf(HttpHeaders.Authorization to "Bearer $bearerToken")
    }

    val apiKey = environment("MCP_API_KEY")?.trim().orEmpty()
    if (apiKey.isNotEmpty()) {
        return mapOf("X-API-Key" to apiKey)
    }

    return emptyMap()
}
