package io.github.muhamed97.sunder.mcp.config.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import io.github.muhamed97.sunder.mcp.Swing
import io.github.muhamed97.sunder.mcp.config.Anchor
import io.github.muhamed97.sunder.mcp.config.Design
import io.github.muhamed97.sunder.mcp.config.Dialogs
import io.github.muhamed97.sunder.mcp.config.McpConfig
import io.github.muhamed97.sunder.mcp.providers.Provider
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.JOptionPane.*
import kotlin.concurrent.thread

class InstallationPanel(
    private val config: McpConfig,
    private val providers: List<Provider>,
    private val reinstallNotice: WarningLabel,
    private val parentComponent: JComponent
) : JPanel() {

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
        add(Design.createSectionLabel("Installation"))
        add(createVerticalStrut(Design.Spacing.SM))

        val installOptions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        val buttonRow = createButtonRow()
        installOptions.add(buttonRow)
        add(installOptions)
        add(createVerticalStrut(Design.Spacing.SM))

        val manualInstallPanel = createManualInstallPanel()
        add(manualInstallPanel)
    }

    private fun createButtonRow(): JPanel {
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, Design.Spacing.SM, Design.Spacing.SM)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        providers.forEach { provider ->
            val button = createProviderButton(provider)
            buttonRow.add(button)
        }

        return buttonRow
    }

    private fun createProviderButton(provider: Provider): JButton {
        return Design.createFilledButton(provider.installButtonText).apply {
            addActionListener {
                handleProviderInstall(provider)
            }
        }
    }

    private fun handleProviderInstall(provider: Provider) {
        val confirmationText = provider.confirmationText

        if (confirmationText != null) {
            val result = Dialogs.showConfirmDialog(
                parentComponent, confirmationText, YES_NO_OPTION
            )

            if (result != YES_OPTION) {
                return
            }
        }

        thread {
            try {
                val result = provider.install(config)
                CoroutineScope(Dispatchers.Swing).launch {
                    reinstallNotice.isVisible = false

                    if (result != null) {
                        Dialogs.showMessageDialog(
                            parentComponent, result, INFORMATION_MESSAGE
                        )
                    }
                }
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Swing).launch {
                    Dialogs.showMessageDialog(
                        parentComponent,
                        "Failed to install for ${provider.name}: ${e.message ?: e.javaClass.simpleName}",
                        ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun createManualInstallPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(
                Anchor(
                    text = "Manual install steps",
                    url = "https://github.com/muhamed97/sunder-burp-mcp#build-and-install"
                )
            )
        }
    }

}
