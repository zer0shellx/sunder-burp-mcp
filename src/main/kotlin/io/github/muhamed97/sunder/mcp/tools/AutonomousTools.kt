package io.github.muhamed97.sunder.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.ScanTask
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import io.github.muhamed97.sunder.mcp.config.McpConfig
import io.github.muhamed97.sunder.mcp.security.HttpRequestSecurity
import io.github.muhamed97.sunder.mcp.traffic.TrafficExchange
import io.github.muhamed97.sunder.mcp.traffic.TrafficStore
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

fun Server.registerAutonomousTools(
    api: MontoyaApi,
    config: McpConfig,
    store: TrafficStore,
) {
    val taskSequence = AtomicLong()
    val scanTasks = ConcurrentHashMap<String, ScanTask>()

    fun loadRequest(id: Long, replacement: String?): Pair<TrafficExchange, HttpRequest> {
        val exchange = store.getExchange(id) ?: error("Traffic exchange $id was not found")
        val summary = exchange.summary
        val service = HttpService.httpService(summary.host, summary.port, summary.secure)
        val raw = replacement?.let(::normalizeHttpContent)
            ?: String(exchange.request, StandardCharsets.ISO_8859_1)
        return exchange to HttpRequest.httpRequest(service, raw)
    }

    fun sendStored(id: Long, replacement: String?, maxResponseBytes: Int): String {
        val (exchange, request) = loadRequest(id, replacement)
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                hostname = exchange.summary.host,
                port = exchange.summary.port,
                config = config,
                requestContent = request.toString(),
                api = api,
                usesHttps = exchange.summary.secure,
            )
        }
        if (!allowed) return "Request denied: target is outside Burp scope and the explicit allowlist"

        api.logging().logToOutput(
            "Autonomous MCP request from exchange $id to ${exchange.summary.host}:${exchange.summary.port}"
        )
        val result = api.http().sendRequest(request)
        val response = result?.response()?.toByteArray()?.getBytes()
            ?: return """{"exchange_id":$id,"response":null}"""
        val limit = maxResponseBytes.coerceIn(1_024, 2_000_000)
        val selected = if (response.size <= limit) response else response.copyOf(limit)
        return buildJsonObject {
            put("source_exchange_id", id)
            put("status_code", result.response()?.statusCode()?.toInt() ?: -1)
            put("response", String(selected, StandardCharsets.ISO_8859_1))
            put("truncated", response.size > selected.size)
        }.toString()
    }

    mcpTool<RequestReplay>(
        "Replays a captured request directly through Burp without manual interaction and returns the response. " +
            "Autonomous mode restricts targets to Burp scope or the explicit allowlist.",
    ) {
        sendStored(id, null, maxResponseBytes)
    }

    mcpTool<RequestModifyAndSend>(
        "Replaces the raw request for a captured exchange, sends it directly through Burp, and returns the response. " +
            "Use this for autonomous parameter, header, body, method, and path mutations.",
    ) {
        sendStored(id, replacementRequest, maxResponseBytes)
    }

    mcpTool<RequestSendBatch>(
        "Replays up to 25 captured requests sequentially through Burp and returns compact status results.",
    ) {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        require(ids.size <= 25) { "A batch can contain at most 25 requests" }
        buildJsonArray {
            ids.forEach { id ->
                val result = runCatching { sendStored(id, null, maxResponseBytes) }
                    .getOrElse { """{"source_exchange_id":$id,"error":${Json.encodeToString(it.message)}}""" }
                add(Json.parseToJsonElement(result))
            }
        }.toString()
    }

    mcpTool<ScopeCheck>(
        "Checks whether a URL is currently included in Burp's target scope.",
    ) {
        buildJsonObject {
            put("url", url)
            put("in_scope", api.scope().isInScope(url))
        }.toString()
    }

    mcpTool<ScopeExclude>(
        "Excludes a URL pattern from Burp scope. This can reduce autonomous agent authority but cannot expand it.",
    ) {
        api.scope().excludeFromScope(url)
        "Excluded $url from Burp scope"
    }

    mcpTool(
        "session_get_cookies",
        "Returns cookies currently held in Burp's session cookie jar.",
    ) {
        buildJsonArray {
            api.http().cookieJar().cookies().forEach { cookie ->
                add(buildJsonObject {
                    put("name", cookie.name())
                    put("value", cookie.value())
                    put("domain", cookie.domain())
                    put("path", cookie.path())
                    put("expiration", cookie.expiration().map(ZonedDateTime::toString).orElse(null))
                })
            }
        }.toString()
    }

    mcpTool<SessionSetCookie>(
        "Sets or updates a cookie in Burp's session cookie jar for an authorized domain.",
    ) {
        val secure = secure ?: true
        val port = if (secure) 443 else 80
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                domain.removePrefix("."),
                port,
                config,
                api = api,
                usesHttps = secure,
            )
        }
        if (!allowed) return@mcpTool "Cookie update denied: domain is outside Burp scope and the allowlist"
        val expirationTime = expiration?.let(ZonedDateTime::parse)
        api.http().cookieJar().setCookie(name, value, domain, path, expirationTime)
        "Cookie $name updated for $domain"
    }

    mcpTool<OrganizerAddExchange>(
        "Adds a captured request/response exchange to Burp Organizer.",
    ) {
        val exchange = store.getExchange(id) ?: return@mcpTool "Traffic exchange $id was not found"
        val summary = exchange.summary
        val service = HttpService.httpService(summary.host, summary.port, summary.secure)
        val request = HttpRequest.httpRequest(
            service,
            String(exchange.request, StandardCharsets.ISO_8859_1),
        )
        api.organizer().sendToOrganizer(request)
        "Added exchange $id request to Organizer"
    }

    if (api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
        mcpTool<ScannerStartAudit>(
            "Starts an active Burp Scanner audit for a captured request without manual interaction.",
        ) {
            val (exchange, request) = loadRequest(id, replacementRequest)
            val allowed = runBlocking {
                HttpRequestSecurity.checkHttpRequestPermission(
                    exchange.summary.host,
                    exchange.summary.port,
                    config,
                    request.toString(),
                    api,
                    exchange.summary.secure,
                )
            }
            if (!allowed) return@mcpTool "Audit denied: target is outside Burp scope and the allowlist"
            val audit = api.scanner().startAudit(
                AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS)
            )
            audit.addRequest(request)
            val taskId = "audit-${taskSequence.incrementAndGet()}"
            scanTasks[taskId] = audit
            buildJsonObject {
                put("task_id", taskId)
                put("status", audit.statusMessage())
            }.toString()
        }

        mcpTool<ScannerStartCrawl>(
            "Starts a Burp crawl for an authorized seed URL without manual interaction.",
        ) {
            val uri = URI(url)
            val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            val allowed = runBlocking {
                HttpRequestSecurity.checkHttpRequestPermission(
                    uri.host,
                    port,
                    config,
                    api = api,
                    usesHttps = uri.scheme.equals("https", true),
                )
            }
            if (!allowed) return@mcpTool "Crawl denied: target is outside Burp scope and the allowlist"
            val crawl = api.scanner().startCrawl(CrawlConfiguration.crawlConfiguration(url))
            val taskId = "crawl-${taskSequence.incrementAndGet()}"
            scanTasks[taskId] = crawl
            buildJsonObject {
                put("task_id", taskId)
                put("status", crawl.statusMessage())
            }.toString()
        }

        mcpTool<ScannerTaskStatus>(
            "Returns the current status, request count, and error count for an autonomous crawl or audit.",
        ) {
            val task = scanTasks[taskId] ?: return@mcpTool "Scanner task $taskId was not found"
            buildJsonObject {
                put("task_id", taskId)
                put("status", task.statusMessage())
                put("request_count", task.requestCount())
                put("error_count", task.errorCount())
            }.toString()
        }

        mcpTool<ScannerTaskStop>(
            "Stops and removes an autonomous crawl or audit task.",
        ) {
            val task = scanTasks.remove(taskId) ?: return@mcpTool "Scanner task $taskId was not found"
            task.delete()
            "Stopped scanner task $taskId"
        }
    }
}

@Serializable
data class RequestReplay(
    val id: Long,
    val maxResponseBytes: Int = 500_000,
)

@Serializable
data class RequestModifyAndSend(
    val id: Long,
    val replacementRequest: String,
    val maxResponseBytes: Int = 500_000,
)

@Serializable
data class RequestSendBatch(
    val ids: List<Long>,
    val maxResponseBytes: Int = 100_000,
)

@Serializable
data class ScopeCheck(val url: String)

@Serializable
data class ScopeExclude(val url: String)

@Serializable
data class SessionSetCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiration: String? = null,
    val secure: Boolean? = null,
)

@Serializable
data class OrganizerAddExchange(val id: Long)

@Serializable
data class ScannerStartAudit(
    val id: Long,
    val replacementRequest: String? = null,
)

@Serializable
data class ScannerStartCrawl(val url: String)

@Serializable
data class ScannerTaskStatus(val taskId: String)

@Serializable
data class ScannerTaskStop(val taskId: String)
