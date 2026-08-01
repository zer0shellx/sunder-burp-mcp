package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RequestHandlerTest {
    private var serverPort = 0
    private lateinit var client: SseClient
    
    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer()
        client = SseClient(
            sseUrl = "http://localhost:$serverPort/sse",
            clientInfo = Implementation("test-client", "1.0.0")
        )
        
        runBlocking {
            client.connect()
        }
    }
    
    @AfterEach
    fun tearDown() {
        TestMcpServer.stopServer()
        client.close()
    }
    
    @Test
    fun `client should successfully call tool`() = runBlocking {
        val result = client.withConnection { c ->
            c.callTool(CallToolRequest("kotlin-sdk-tool"))
        }
        
        assertNotNull(result, "Tool call result should not be null")
        
        val content = result.content.firstOrNull() as? TextContent
        assertNotNull(content, "Tool call should return content")
        assertEquals("Hello, world!", content.text, "Tool call should return expected message")
    }
    
    @Test
    fun `client should automatically reconnect after server restart when making requests`() = runBlocking {
        val initialResult = client.withConnection { c ->
            c.callTool(CallToolRequest("kotlin-sdk-tool"))
        }
        assertNotNull(initialResult, "Initial tool call should succeed")
        
        TestUtils.setConnectionState(client, ConnectionState.DISCONNECTED)
        
        val resultAfterForceDisconnect = client.withConnection { c ->
            c.callTool(CallToolRequest("kotlin-sdk-tool"))
        }
        
        assertNotNull(resultAfterForceDisconnect, "Tool call after force disconnect should succeed")
        assertEquals(
            "Hello, world!", 
            (resultAfterForceDisconnect.content.first() as TextContent).text,
            "Tool call should return the expected result after reconnection"
        )
        
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
    }
    
    @Test
    fun `server restart should trigger automatic reconnection for tool calls`() = runBlocking {
        val initialResult = client.withConnection { c ->
            c.callTool(CallToolRequest("kotlin-sdk-tool"))
        }
        assertNotNull(initialResult, "Initial tool call should succeed")
        
        TestMcpServer.stopServer()
        serverPort = TestMcpServer.startServer(serverPort)
        
        try {
            client.withConnection { c ->
                c.callTool(CallToolRequest("kotlin-sdk-tool"))
            }
        } catch (e: Exception) {
            client.connect()
        }
        
        val resultAfterRestart = client.withConnection { c ->
            c.callTool(CallToolRequest("kotlin-sdk-tool"))
        }
        
        assertNotNull(resultAfterRestart, "Tool call after server restart should succeed")
        assertEquals(
            "Hello, world!", 
            (resultAfterRestart.content.first() as TextContent).text,
            "Tool call should return the expected result after server restart"
        )
    }
}
