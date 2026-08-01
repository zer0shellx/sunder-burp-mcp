package io.github.muhamed97.sunder.mcp.config.components

import io.github.muhamed97.sunder.mcp.config.Design
import io.github.muhamed97.sunder.mcp.config.McpConfig
import io.github.muhamed97.sunder.mcp.config.ToggleSwitch
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut

class ServerConfigurationPanel(
    private val config: McpConfig,
    private val enabledToggle: ToggleSwitch,
    private val validationErrorLabel: WarningLabel
) : JPanel() {

    private lateinit var alwaysAllowHttpHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowWebSocketHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowOrganizerCheckBox: JCheckBox

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        updateColors()
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
        )
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Server Configuration"))
        add(createVerticalStrut(Design.Spacing.MD))

        val enabledPanel = createEnabledPanel()
        add(enabledPanel)
        add(createVerticalStrut(Design.Spacing.MD))

        add(createAuthenticationPanel())
        add(createVerticalStrut(Design.Spacing.MD))

        val autonomousModeCheckBox = createCheckBoxWithSubtitle(
            "Autonomous agent mode",
            "No approval dialogs. Requests are restricted to Burp scope and explicit target allowlists.",
            config.autonomousMode,
        ) { config.autonomousMode = it }
        add(autonomousModeCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val configEditingToolingCheckBox = createCheckBoxWithSubtitle(
            "Enable tools that can edit your config",
            "WARNING: Can execute code",
            config.configEditingTooling
        ) { config.configEditingTooling = it }
        add(configEditingToolingCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val httpRequestApprovalCheckBox = createStandardCheckBox(
            "Require approval for HTTP requests", config.requireHttpRequestApproval
        ) { config.requireHttpRequestApproval = it }
        add(httpRequestApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val dataAccessApprovalCheckBox = createDataAccessApprovalCheckBox()
        add(dataAccessApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowHttpHistoryCheckBox = createIndentedCheckBox(
            "Always allow HTTP history access", config.alwaysAllowHttpHistory, config.requireDataAccessApproval
        ) { config.alwaysAllowHttpHistory = it }
        add(alwaysAllowHttpHistoryCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowWebSocketHistoryCheckBox = createIndentedCheckBox(
            "Always allow WebSocket history access",
            config.alwaysAllowWebSocketHistory,
            config.requireDataAccessApproval
        ) { config.alwaysAllowWebSocketHistory = it }
        add(alwaysAllowWebSocketHistoryCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowOrganizerCheckBox = createIndentedCheckBox(
            "Always allow Organizer access",
            config.alwaysAllowOrganizer,
            config.requireDataAccessApproval
        ) { config.alwaysAllowOrganizer = it }
        add(alwaysAllowOrganizerCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val filterConfigCredentialsCheckBox = createCheckBoxWithSubtitle(
            "Filter config credentials",
            "Hides sensitive data in config files (Platform Authentication, socks proxy, etc.)",
            config.filterConfigCredentials
        ) { config.filterConfigCredentials = it }
        add(filterConfigCredentialsCheckBox)

        add(validationErrorLabel)
    }

    private fun createEnabledPanel(): JPanel {
        val enabledPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        enabledPanel.add(JLabel("Enabled").apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
        })
        enabledPanel.add(createHorizontalStrut(Design.Spacing.MD))
        enabledPanel.add(enabledToggle)
        return enabledPanel
    }

    private fun createAuthenticationPanel(): JPanel {
        val tokenField = JPasswordField(config.authToken, 34).apply {
            isEditable = false
            echoChar = '\u2022'
            maximumSize = preferredSize
        }
        val showButton = JButton("Show").apply {
            addActionListener {
                val showing = tokenField.echoChar.code == 0
                tokenField.echoChar = if (showing) '\u2022' else 0.toChar()
                text = if (showing) "Show" else "Hide"
            }
        }
        val copyButton = JButton("Copy").apply {
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    StringSelection(config.authToken),
                    null,
                )
            }
        }
        val rotateButton = JButton("Rotate").apply {
            addActionListener {
                val answer = JOptionPane.showConfirmDialog(
                    this@ServerConfigurationPanel,
                    "Rotate the MCP authentication token? Installed AI clients must be reinstalled.",
                    "Rotate authentication token",
                    JOptionPane.OK_CANCEL_OPTION,
                )
                if (answer == JOptionPane.OK_OPTION) {
                    tokenField.text = config.rotateAuthToken()
                }
            }
        }
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(tokenField)
            add(showButton)
            add(copyButton)
            add(rotateButton)
        }
        val enabled = createStandardCheckBox(
            "Require authentication for MCP connections",
            config.requireAuthentication,
        ) { selected ->
            config.requireAuthentication = selected
            tokenField.isEnabled = selected
            showButton.isEnabled = selected
            copyButton.isEnabled = selected
            rotateButton.isEnabled = selected
        }
        tokenField.isEnabled = config.requireAuthentication
        showButton.isEnabled = config.requireAuthentication
        copyButton.isEnabled = config.requireAuthentication
        rotateButton.isEnabled = config.requireAuthentication

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(enabled)
            add(createVerticalStrut(Design.Spacing.SM))
            add(controls)
            add(JLabel("Use this token as MCP_AUTH_TOKEN. Reinstall clients after rotating it.").apply {
                font = Design.Typography.labelMedium
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = LEFT_ALIGNMENT
            })
        }
    }

    private fun createDataAccessApprovalCheckBox(): JCheckBox {
        return createStandardCheckBox(
            "Require approval for project data access", config.requireDataAccessApproval
        ) { enabled ->
            config.requireDataAccessApproval = enabled
            if (!enabled) {
                config.alwaysAllowHttpHistory = false
                config.alwaysAllowWebSocketHistory = false
                config.alwaysAllowOrganizer = false
                alwaysAllowHttpHistoryCheckBox.isSelected = false
                alwaysAllowWebSocketHistoryCheckBox.isSelected = false
                alwaysAllowOrganizerCheckBox.isSelected = false
            }
            alwaysAllowHttpHistoryCheckBox.isEnabled = enabled
            alwaysAllowWebSocketHistoryCheckBox.isEnabled = enabled
            alwaysAllowOrganizerCheckBox.isEnabled = enabled
        }
    }

    fun updateDataAccessCheckboxes() {
        SwingUtilities.invokeLater {
            alwaysAllowHttpHistoryCheckBox.isSelected = config.alwaysAllowHttpHistory
            alwaysAllowWebSocketHistoryCheckBox.isSelected = config.alwaysAllowWebSocketHistory
            alwaysAllowOrganizerCheckBox.isSelected = config.alwaysAllowOrganizer
        }
    }

    private fun createStandardCheckBox(
        text: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createIndentedCheckBox(
        text: String, initialValue: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            isEnabled = enabled
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, Design.Spacing.LG, 0, 0)
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createCheckBoxWithSubtitle(
        mainText: String, subtitleText: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JPanel {
        val checkBox = JCheckBox(mainText).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }

        val subtitleLabel = JLabel(subtitleText).apply {
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
        }

        val subtitlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createHorizontalStrut(20))
            add(subtitleLabel)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(checkBox)
            add(subtitlePanel)
        }
    }

}
