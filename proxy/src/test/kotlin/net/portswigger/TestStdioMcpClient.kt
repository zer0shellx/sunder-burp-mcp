package net.portswigger

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream

class TestStdioMcpClient {
    private val logger = LoggerFactory.getLogger(TestStdioMcpClient::class.java)
    private val mcp: Client = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))

    private lateinit var tools: List<Tool>
    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    suspend fun connectToServer(input: InputStream = System.`in`, output: OutputStream = System.out) {
        try {
            this.input = input
            this.output = output
            
            val transport = StdioClientTransport(
                input = input.asSource().buffered(),
                output = output.asSink().buffered()
            )

            mcp.connect(transport)

            val toolsResult = mcp.listTools()
            tools = toolsResult?.tools ?: emptyList()
            println("Connected to server with tools: ${tools.joinToString(", ") { it.name }}")
        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    suspend fun ping(): EmptyRequestResult {
        try {
            val pingRequest = mcp.ping()
            logger.info("Ping sent: $pingRequest")
            return pingRequest
        } catch (e: Exception) {
            logger.error("Failed to send ping: $e")
            throw e
        }
    }

    suspend fun listPrompts(): List<Prompt> {
        try {
            val promptsResult = mcp.listPrompts()
            val prompts = promptsResult?.prompts ?: emptyList()
            logger.info("Prompts listed: ${prompts.joinToString(", ")}")
            return prompts
        } catch (e: Exception) {
            logger.error("Failed to list prompts: $e")
            throw e
        }
    }

    suspend fun listResources(): List<Resource> {
        try {
            val resourcesResult = mcp.listResources()
            val resources = resourcesResult?.resources ?: emptyList()
            logger.info("Resources listed: ${resources.joinToString(", ")}")
            return resources
        } catch (e: Exception) {
            logger.error("Failed to list resources: $e")
            throw e
        }
    }

    suspend fun listTools(): List<Tool> {
        try {
            val toolsResult = mcp.listTools()
            tools = toolsResult?.tools ?: emptyList()
            logger.info("Tools listed: ${tools.joinToString(", ") { it.name }}")
            return tools
        } catch (e: Exception) {
            logger.error("Failed to list tools: $e")
            throw e
        }
    }

    suspend fun testTool(): CallToolResultBase? {
        try {
            val tool = tools.firstOrNull() ?: throw IllegalStateException("No tools available")
            val result = mcp.callTool(tool.name, emptyMap())
            logger.info("Tool called: ${tool.name}, result: $result")
            return result
        } catch (e: Exception) {
            logger.error("Failed to call tool: $e")
            throw e
        }
    }

    suspend fun close() {
        try {
            mcp.close()
            logger.info("MCP client closed successfully.")
        } catch (e: Exception) {
            logger.error("Failed to close MCP client: $e")
        }
    }
}
