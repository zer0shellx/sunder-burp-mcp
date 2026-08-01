package net.portswigger

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommandLineTest {
    private var serverPort = 0
    
    @BeforeEach
    fun setup() {
        serverPort = TestMcpServer.startServer()
    }
    
    @AfterEach
    fun tearDown() {
        TestMcpServer.stopServer()
    }
    
    @Test
    fun `should use default SSE URL when no arguments provided`() {
        val args = emptyArray<String>()
        val url = parseCommandLineArgs(args)
        assertEquals("http://localhost:9876", url)
    }
    
    @Test
    fun `should use custom SSE URL when provided`() {
        val args = arrayOf("--sse-url", "https://example.com:8080")
        val url = parseCommandLineArgs(args)
        assertEquals("https://example.com:8080", url)
    }
    
    @Test
    fun `should be able to use test server URL`() {
        val testServerUrl = "http://localhost:${serverPort}"
        val args = arrayOf("--sse-url", testServerUrl)
        val url = parseCommandLineArgs(args)
        assertEquals(testServerUrl, url)
    }
}