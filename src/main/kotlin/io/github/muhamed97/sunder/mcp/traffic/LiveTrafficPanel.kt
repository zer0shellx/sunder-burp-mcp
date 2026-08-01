package io.github.muhamed97.sunder.mcp.traffic

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import io.github.muhamed97.sunder.mcp.config.McpConfig
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.event.ListSelectionEvent
import javax.swing.table.DefaultTableModel

class LiveTrafficPanel(
    private val api: MontoyaApi,
    private val config: McpConfig,
    private val store: TrafficStore,
    private val recorder: LiveTrafficRecorder,
) : JPanel(BorderLayout()), AutoCloseable {
    private val refreshing = AtomicBoolean()
    private val searchField = JTextField(28)
    private val statusLabel = JLabel("Loading session traffic...")
    private val httpModel = readOnlyModel(
        "Sequence", "Time", "Tool", "Method", "Status", "Scope", "Size", "URL"
    )
    private val websocketModel = readOnlyModel(
        "Sequence", "Time", "Tool", "Direction", "Type", "Scope", "Size", "URL"
    )
    private val httpTable = JTable(httpModel)
    private val websocketTable = JTable(websocketModel)
    private val requestArea = messageArea()
    private val responseArea = messageArea()
    private val websocketPayloadArea = messageArea()
    private var httpRows = emptyList<TrafficSummary>()
    private var websocketRows = emptyList<WebSocketSummary>()
    private val refreshTimer = Timer(2_000) { refresh() }

    init {
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        add(buildToolbar(), BorderLayout.NORTH)
        add(buildTrafficTabs(), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
        refreshTimer.start()
        refresh()
    }

    private fun buildToolbar(): JComponent {
        val captureHttp = JCheckBox("Capture HTTP", config.captureHttp).apply {
            addActionListener { config.captureHttp = isSelected }
        }
        val captureWebSockets = JCheckBox("Capture WebSockets", config.captureWebSockets).apply {
            addActionListener { config.captureWebSockets = isSelected }
        }
        val scopeOnly = JCheckBox("Capture Burp scope only", config.scopeOnlyCapture).apply {
            addActionListener { config.scopeOnlyCapture = isSelected }
        }
        val maxSessionEvents = JSpinner(
            SpinnerNumberModel(config.maxSessionEvents, 0, 10_000_000, 10_000)
        ).apply {
            toolTipText = "0 keeps all traffic until Burp closes; a limit protects memory"
            addChangeListener { config.maxSessionEvents = value as Int }
        }
        val settings = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(captureHttp)
            add(captureWebSockets)
            add(scopeOnly)
            add(JLabel("Maximum session events:"))
            add(maxSessionEvents)
            add(JLabel("(0 = unlimited, RAM only)"))
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
            add(JLabel("Search:"))
            add(searchField)
            add(JButton("Search").apply { addActionListener { refresh() } })
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("Clear session buffer").apply {
                addActionListener { confirmAndClear() }
            })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(settings)
            add(actions)
        }
    }

    private fun buildTrafficTabs(): JComponent = JTabbedPane().apply {
        addTab("HTTP exchanges", buildHttpPanel())
        addTab("WebSocket messages", buildWebSocketPanel())
    }

    private fun buildHttpPanel(): JComponent {
        configureTable(httpTable)
        httpTable.selectionModel.addListSelectionListener(::httpSelectionChanged)
        val details = JTabbedPane().apply {
            addTab("Request", JScrollPane(requestArea))
            addTab("Response", JScrollPane(responseArea))
        }
        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(httpTable),
            details,
        ).apply {
            resizeWeight = 0.55
        }
        val repeaterButton = JButton("Open selected request in Repeater").apply {
            addActionListener { openSelectedInRepeater() }
        }
        return JPanel(BorderLayout()).apply {
            add(split, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(repeaterButton) }, BorderLayout.SOUTH)
        }
    }

    private fun buildWebSocketPanel(): JComponent {
        configureTable(websocketTable)
        websocketTable.selectionModel.addListSelectionListener(::websocketSelectionChanged)
        return JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            JScrollPane(websocketTable),
            JScrollPane(websocketPayloadArea),
        ).apply {
            resizeWeight = 0.6
        }
    }

    private fun configureTable(table: JTable) {
        table.autoCreateRowSorter = true
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
    }

    private fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        val query = searchField.text.trim()
        object : SwingWorker<Pair<List<TrafficSummary>, List<WebSocketSummary>>, Unit>() {
            override fun doInBackground(): Pair<List<TrafficSummary>, List<WebSocketSummary>> {
                val http = if (query.isBlank()) {
                    store.list(0, 200, null, scopeOnly = false, newestFirst = true)
                } else {
                    store.search(query, 0, 200, null, scopeOnly = false)
                }
                val websocket = if (query.isBlank()) {
                    store.listWebSocket(0, 200, null, scopeOnly = false, newestFirst = true)
                } else {
                    store.searchWebSocket(query, 0, 200, scopeOnly = false)
                }
                return http to websocket
            }

            override fun done() {
                try {
                    val (http, websocket) = get()
                    httpRows = http
                    websocketRows = websocket
                    updateHttpTable()
                    updateWebSocketTable()
                    val stats = store.statistics(recorder.droppedEventCount())
                    statusLabel.text =
                        "${stats.exchanges} HTTP exchanges | ${stats.webSocketMessages} WebSocket messages | " +
                            "${formatBytes(stats.memoryBytes)} in memory | no traffic database" +
                            if (stats.droppedCaptureEvents > 0) {
                                " | WARNING: ${stats.droppedCaptureEvents} capture events dropped"
                            } else {
                                ""
                            }
                } catch (e: Exception) {
                    statusLabel.text = "Unable to refresh traffic: ${e.cause?.message ?: e.message}"
                } finally {
                    refreshing.set(false)
                }
            }
        }.execute()
    }

    private fun updateHttpTable() {
        httpModel.rowCount = 0
        httpRows.forEach { row ->
            httpModel.addRow(
                arrayOf<Any>(
                    row.sequence,
                    formatTime(row.timestamp),
                    row.tool,
                    row.method,
                    row.statusCode ?: "",
                    if (row.inScope) "In" else "Out",
                    formatBytes(row.requestSize.toLong() + (row.responseSize ?: 0)),
                    row.url,
                )
            )
        }
    }

    private fun updateWebSocketTable() {
        websocketModel.rowCount = 0
        websocketRows.forEach { row ->
            websocketModel.addRow(
                arrayOf<Any>(
                    row.sequence,
                    formatTime(row.timestamp),
                    row.tool,
                    row.direction,
                    row.messageType,
                    if (row.inScope) "In" else "Out",
                    formatBytes(row.payloadSize.toLong()),
                    row.url,
                )
            )
        }
    }

    private fun httpSelectionChanged(event: ListSelectionEvent) {
        if (event.valueIsAdjusting || httpTable.selectedRow < 0) return
        val modelIndex = httpTable.convertRowIndexToModel(httpTable.selectedRow)
        val summary = httpRows.getOrNull(modelIndex) ?: return
        runBackground {
            val exchange = store.getExchange(summary.id) ?: return@runBackground
            SwingUtilities.invokeLater {
                requestArea.text = String(exchange.request, StandardCharsets.ISO_8859_1)
                responseArea.text = exchange.response?.let {
                    String(it, StandardCharsets.ISO_8859_1)
                } ?: "<response not received>"
                requestArea.caretPosition = 0
                responseArea.caretPosition = 0
            }
        }
    }

    private fun websocketSelectionChanged(event: ListSelectionEvent) {
        if (event.valueIsAdjusting || websocketTable.selectedRow < 0) return
        val modelIndex = websocketTable.convertRowIndexToModel(websocketTable.selectedRow)
        val summary = websocketRows.getOrNull(modelIndex) ?: return
        runBackground {
            val record = store.getWebSocketMessage(summary.id) ?: return@runBackground
            val rendered = if (record.summary.messageType == "TEXT") {
                String(record.payload, StandardCharsets.UTF_8)
            } else {
                "Base64:\n${Base64.getEncoder().encodeToString(record.payload)}"
            }
            SwingUtilities.invokeLater {
                websocketPayloadArea.text = rendered
                websocketPayloadArea.caretPosition = 0
            }
        }
    }

    private fun openSelectedInRepeater() {
        if (httpTable.selectedRow < 0) return
        val modelIndex = httpTable.convertRowIndexToModel(httpTable.selectedRow)
        val summary = httpRows.getOrNull(modelIndex) ?: return
        runBackground {
            val exchange = store.getExchange(summary.id) ?: return@runBackground
            val service = HttpService.httpService(summary.host, summary.port, summary.secure)
            val request = HttpRequest.httpRequest(
                service,
                String(exchange.request, StandardCharsets.ISO_8859_1),
            )
            api.repeater().sendToRepeater(request, "SUNDER - ${summary.id}")
        }
    }

    private fun confirmAndClear() {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Clear all HTTP and WebSocket traffic from this extension's session memory?\n" +
                "Burp's own project and history are not changed.",
            "Clear session buffer",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (result == JOptionPane.OK_OPTION) {
            runBackground {
                store.clearAll()
                SwingUtilities.invokeLater {
                    requestArea.text = ""
                    responseArea.text = ""
                    websocketPayloadArea.text = ""
                    refresh()
                }
            }
        }
    }

    private fun runBackground(action: () -> Unit) {
        Thread(action, "sunder-ui-action").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        refreshTimer.stop()
    }

    companion object {
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

        private fun formatTime(epochMillis: Long): String =
            timeFormatter.format(Instant.ofEpochMilli(epochMillis))

        private fun formatBytes(bytes: Long): String = when {
            bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.1f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }

        private fun messageArea() = JTextArea().apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = false
        }

        private fun readOnlyModel(vararg columns: String) =
            object : DefaultTableModel(columns, 0) {
                override fun isCellEditable(row: Int, column: Int): Boolean = false
            }
    }
}
