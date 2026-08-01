package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.system.exitProcess

private val loggerMain = LoggerFactory.getLogger("net.portswigger.Main")

data class SseToStdioArgs(
    val sseUrl: String
)

enum class ConnectionState {
    CONNECTED, CONNECTING, DISCONNECTED
}

fun sseToStdio(args: SseToStdioArgs) = runBlocking {
    val sseUrl = args.sseUrl
    val sseClientManager = SseClient(
        sseUrl = sseUrl, clientInfo = Implementation(
            name = "burp-proxy", version = "1.0.0"
        )
    )

    val connected = sseClientManager.connect()
    if (!connected) {
        loggerMain.warn("Please check if the server is running and the URL is correct")
        loggerMain.info("Will attempt to reconnect automatically when needed")
    }

    val sseClient = sseClientManager.getClient()
    val stdioServer = Server(
        serverInfo = sseClient.serverVersion ?: Implementation("burp-suite", "1.0"), options = ServerOptions(
            capabilities = sseClient.serverCapabilities ?: disconnectedServerCapabilities()
        )
    )

    setupRequestHandlers(sseClient, stdioServer, sseClientManager)
    setupNotificationHandlers(sseClient, stdioServer, sseClientManager)

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(), outputStream = System.out.asSink().buffered()
    )

    runBlocking {
        try {
            stdioServer.connect(transport)
            val done = Job()
            stdioServer.onClose {
                done.complete()
            }
            done.join()
        } catch (e: Exception) {
            loggerMain.error("Error in stdio server: {}", e.message, e)
        } finally {
            sseClientManager.close()
        }
    }
}

private class NotificationRegistrar(
    private val client: Client, private val server: Server, private val connectionManager: SseClient
) {
    private val logger = LoggerFactory.getLogger(NotificationRegistrar::class.java)

    inline fun <reified T : Notification> register(method: Method) {
        client.setNotificationHandler<T>(method) { notification ->
            try {
                runBlocking {
                    connectionManager.withConnection { _ ->
                        server.notification(notification)
                    }
                }
                CompletableDeferred(Unit)
            } catch (e: Exception) {
                val message = "Connection error for ${method.value} notification: ${e.message}"
                logger.error(message)

                CompletableDeferred(Unit)
            }
        }
    }
}

private class RequestHandlerRegistrar(
    private val server: Server,
    private val connectionManager: SseClient? = null
) {
    private val logger = LoggerFactory.getLogger(RequestHandlerRegistrar::class.java)

    inline fun <reified T : Request> register(
        method: Method, crossinline handler: suspend (T) -> RequestResult?
    ) {
        server.setRequestHandler<T>(method) { params, _ ->
            try {
                handler(params)
            } catch (failure: Throwable) {
                if (connectionManager != null && failure.isRecoverableConnectionFailure()) {
                    logger.warn(
                        "Detected connection failure during {}: {}. Triggering reconnection.",
                        method.value,
                        failure.message,
                    )
                    connectionManager.connectionStateMutableStateFlow.value = ConnectionState.DISCONNECTED

                    connectionManager.withConnection {
                        logger.info("Reconnection successful, retrying method {}", method.value)
                        handler(params)
                    } ?: throw IOException(
                        "Unable to reconnect to the Burp SSE server while handling ${method.value}",
                        failure,
                    )
                } else {
                    throw failure
                }
            }
        }
    }
}

internal fun Throwable.isRecoverableConnectionFailure(): Boolean {
    val connectionMessages = listOf(
        "session not found",
        "not connected",
        "transport is not initialized",
        "connection reset",
        "connection refused",
        "broken pipe",
        "channel was closed",
        "socket closed",
    )

    return generateSequence(this) { it.cause }.any { cause ->
        cause is IOException || connectionMessages.any { message ->
            cause.message?.contains(message, ignoreCase = true) == true
        }
    }
}

internal fun disconnectedServerCapabilities() = ServerCapabilities(
    tools = ServerCapabilities.Tools(listChanged = true),
)

private fun Client.withServer(
    server: Server, connectionManager: SseClient, block: NotificationRegistrar.() -> Unit
) {
    NotificationRegistrar(this, server, connectionManager).apply(block)
}

private fun Server.withClient(
    connectionManager: SseClient? = null,
    block: RequestHandlerRegistrar.() -> Unit
) {
    RequestHandlerRegistrar(this, connectionManager).apply(block)
}

private fun setupRequestHandlers(client: Client, server: Server, connectionManager: SseClient? = null) {
    server.withClient(connectionManager) {
        register<InitializeRequest>(Method.Defined.Initialize) { params ->
            client.request<InitializeResult>(params)
        }
        register<ListToolsRequest>(Method.Defined.ToolsList) { _ ->
            client.listTools()
        }
        register<CallToolRequest>(Method.Defined.ToolsCall) { params ->
            client.callTool(params)
        }
        register<CreateMessageRequest>(Method.Defined.SamplingCreateMessage) { params ->
            client.request<CreateMessageResult>(params)
        }
        register<ListRootsRequest>(Method.Defined.RootsList) { params ->
            client.request<ListRootsResult>(params)
        }
        register<PingRequest>(Method.Defined.Ping) { _ ->
            client.ping()
        }
        register<LoggingMessageNotification.SetLevelRequest>(Method.Defined.LoggingSetLevel) { params ->
            client.setLoggingLevel(params.level)
        }
    }

    if (client.serverCapabilities?.resources?.listChanged == true) {
        server.withClient(connectionManager) {
            register<ListResourcesRequest>(Method.Defined.ResourcesList) { params ->
                client.listResources(params)
            }
        }
    }

    if (client.serverCapabilities?.resources != null) {
        server.withClient(connectionManager) {
            register<ReadResourceRequest>(Method.Defined.ResourcesRead) { params ->
                client.readResource(params)
            }
            register<SubscribeRequest>(Method.Defined.ResourcesSubscribe) { params ->
                client.subscribeResource(params)
            }
            register<UnsubscribeRequest>(Method.Defined.ResourcesUnsubscribe) { params ->
                client.unsubscribeResource(params)
            }
            register<ListResourceTemplatesRequest>(Method.Defined.ResourcesTemplatesList) { params ->
                client.listResourceTemplates(params)
            }
        }
    }

    if (client.serverCapabilities?.prompts?.listChanged == true) {
        server.withClient(connectionManager) {
            register<ListPromptsRequest>(Method.Defined.PromptsList) { params ->
                client.listPrompts(params)
            }
        }
    }

    if (client.serverCapabilities?.prompts != null) {
        server.withClient(connectionManager) {
            register<GetPromptRequest>(Method.Defined.PromptsGet) { params ->
                client.getPrompt(params)
            }
        }
    }
}

private fun setupNotificationHandlers(client: Client, server: Server, connectionManager: SseClient) {
    client.withServer(server, connectionManager) {
        register<ToolListChangedNotification>(Method.Defined.NotificationsToolsListChanged)
        register<ResourceListChangedNotification>(Method.Defined.NotificationsResourcesListChanged)
        register<ResourceUpdatedNotification>(Method.Defined.NotificationsResourcesUpdated)
        register<PromptListChangedNotification>(Method.Defined.NotificationsPromptsListChanged)
        register<RootsListChangedNotification>(Method.Defined.NotificationsRootsListChanged)
        register<LoggingMessageNotification>(Method.Defined.NotificationsMessage)
        register<CancelledNotification>(Method.Defined.NotificationsCancelled)
        register<ProgressNotification>(Method.Defined.NotificationsProgress)
        register<InitializedNotification>(Method.Defined.NotificationsInitialized)
    }
}

/**
 * Parse command line arguments.
 *
 * @param args Command line arguments
 * @return The SSE URL extracted from arguments or the default one
 */
fun parseCommandLineArgs(args: Array<String>): String {
    return if (args.size >= 2 && args[0] == "--sse-url") {
        args[1]
    } else {
        "http://localhost:9876"
    }
}

/**
 * Start the MCP proxy.
 *
 * @param args Command line arguments:
 * - "--sse-url <url>": The URL of the SSE MCP server (default: http://localhost:9876)
 */
fun main(args: Array<String>) {
    try {
        val sseUrl = parseCommandLineArgs(args)
        loggerMain.info("Starting Burp MCP proxy with SSE URL: {}", sseUrl)

        sseToStdio(
            SseToStdioArgs(
                sseUrl = sseUrl
            )
        )
    } catch (e: Exception) {
        loggerMain.error("Failed to start proxy: {}", e.message, e)
        exitProcess(1)
    }
}
