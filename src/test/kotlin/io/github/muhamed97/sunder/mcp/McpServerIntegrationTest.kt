package io.github.muhamed97.sunder.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.muhamed97.sunder.mcp.config.McpConfig
import io.github.muhamed97.sunder.mcp.traffic.LiveTrafficRecorder
import io.github.muhamed97.sunder.mcp.traffic.TrafficStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class McpServerIntegrationTest {
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val trafficStore = mockk<TrafficStore>(relaxed = true)
    private val trafficRecorder = mockk<LiveTrafficRecorder>(relaxed = true)
    private val serverManager = KtorServerManager(api, trafficStore, trafficRecorder)
    private val testPort = findAvailablePort()
    private val persistedObject = mockk<PersistedObject>()
    private var serverStarted = false

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getBoolean("requireAuthentication") } returns false
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    @BeforeEach
    fun setup() {
        serverManager.start(config) { state ->
            if (state is ServerState.Running) {
                serverStarted = true
            }
        }
        
        runBlocking {
            var attempts = 0
            while (!serverStarted && attempts < 50) {
                delay(100)
                attempts++
            }
            
            if (!serverStarted) {
                throw IllegalStateException("Server failed to start after timeout")
            }
        }
    }

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            if (client.isConnected()) {
                client.close()
            }
        }
        serverManager.stop {}
    }

    @Test
    fun `server should accept connections and list tools`() = runBlocking {
        try {
            client.connectToServer("http://127.0.0.1:${testPort}")
            assertTrue(client.isConnected(), "Client should be connected to server")
            
            val tools = client.listTools()
            assertFalse(tools.isEmpty(), "Server should have registered tools")
            
            val toolNames = tools.map { it.name }
            assertTrue(toolNames.contains("output_project_options"), "Server should have output_project_options tool")
            assertTrue(toolNames.contains("output_user_options"), "Server should have output_user_options tool")
            assertTrue(toolNames.contains("traffic_get_latest"), "Server should have live HTTP history tools")
            assertTrue(toolNames.contains("traffic_search"), "Server should have complete traffic search")
            assertTrue(toolNames.contains("traffic_list_endpoints"), "Server should have endpoint aggregation")
            assertTrue(toolNames.contains("web_socket_get_latest"), "Server should have live WebSocket tools")
            assertTrue(toolNames.contains("agent_get_events"), "Server should have autonomous event polling")
            assertTrue(toolNames.contains("request_replay"), "Server should have autonomous replay")
            assertTrue(toolNames.contains("request_modify_and_send"), "Server should have autonomous request mutation")
            
            val pingResult = client.ping()
            assertNotNull(pingResult, "Ping should return a result")
        } catch (e: Exception) {
            fail("Connection failed: ${e.message}")
        }
    }
}
