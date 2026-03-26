package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IjMcpAppService
import ai.plyxal.ijmcp.model.IjMcpServerStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.UUID
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class IjMcpSettingsConfigurable : SearchableConfigurable {
    private val settingsService = service<IjMcpSettingsService>()
    private val secretStore = service<IjMcpSecretStore>()
    private val appService = service<IjMcpAppService>()

    private val enabledCheckBox = JCheckBox("Enable local MCP server")
    private val portSpinner = JSpinner(SpinnerNumberModel(8765, 1, 65535, 1))
    private val tokenField = JTextField()
    private val tokenStatusLabel = JBLabel("Stored token: unknown")
    private val serverStatusLabel = JBLabel("Server status: stopped")
    private val generateTokenButton = JButton("Generate Token")
    private val clearTokenButton = JButton("Clear Stored Token")

    private var component: JPanel? = null
    private var clearStoredTokenRequested = false

    override fun getId(): String = "ai.plyxal.ijmcp.settings"

    override fun getDisplayName(): String = "IJ-MCP"

    override fun createComponent(): JComponent {
        if (component == null) {
            generateTokenButton.addActionListener {
                tokenField.text = UUID.randomUUID().toString().replace("-", "")
                clearStoredTokenRequested = false
                tokenStatusLabel.text = "Stored token: new token staged. Apply to save."
            }

            clearTokenButton.addActionListener {
                tokenField.text = ""
                clearStoredTokenRequested = true
                tokenStatusLabel.text = "Stored token: will be cleared on Apply."
            }

            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(generateTokenButton)
                add(clearTokenButton)
            }

            component = JPanel(BorderLayout()).apply {
                add(
                    FormBuilder.createFormBuilder()
                        .addComponent(enabledCheckBox)
                        .addLabeledComponent("Port", portSpinner)
                        .addLabeledComponent("New Token", tokenField)
                        .addComponent(buttonPanel)
                        .addLabeledComponent("Stored Token", tokenStatusLabel)
                        .addLabeledComponent("Server Status", serverStatusLabel)
                        .panel,
                    BorderLayout.NORTH,
                )
            }
        }

        return component!!
    }

    override fun isModified(): Boolean {
        val state = settingsService.snapshot()

        return enabledCheckBox.isSelected != state.enabled ||
            (portSpinner.value as Number).toInt() != state.port ||
            tokenField.text.isNotBlank() ||
            clearStoredTokenRequested
    }

    override fun apply() {
        val enabled = enabledCheckBox.isSelected
        val port = (portSpinner.value as Number).toInt()
        val stagedToken = tokenField.text.trim().takeIf(String::isNotBlank)

        settingsService.update(
            enabled = enabled,
            port = port,
        )

        serverStatusLabel.text = "Applying settings..."

        ApplicationManager.getApplication().executeOnPooledThread {
            if (stagedToken != null) {
                secretStore.storeToken(stagedToken)
            } else if (clearStoredTokenRequested) {
                secretStore.storeToken(null)
            }

            val status = appService.applyConfiguredState()

            ApplicationManager.getApplication().invokeLater {
                clearStoredTokenRequested = false
                tokenField.text = ""
                renderStatus(status)
                refreshStoredTokenStatus()
            }
        }
    }

    override fun reset() {
        val state = settingsService.snapshot()

        enabledCheckBox.isSelected = state.enabled
        portSpinner.value = state.port
        tokenField.text = ""
        clearStoredTokenRequested = false
        renderStatus(appService.status())
        refreshStoredTokenStatus()
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun refreshStoredTokenStatus() {
        tokenStatusLabel.text = "Stored token: checking..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val text = if (secretStore.hasStoredToken()) {
                "Stored token: configured"
            } else {
                "Stored token: not configured"
            }

            ApplicationManager.getApplication().invokeLater {
                tokenStatusLabel.text = text
            }
        }
    }

    private fun renderStatus(status: IjMcpServerStatus) {
        serverStatusLabel.text = if (status.running) {
            "Running at ${status.endpointUrl}"
        } else {
            status.lastError?.let { "Stopped: $it" } ?: "Stopped"
        }
    }
}
