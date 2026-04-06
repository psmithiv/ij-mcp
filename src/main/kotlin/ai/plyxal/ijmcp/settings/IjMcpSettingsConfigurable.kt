package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IjMcpAppService
import ai.plyxal.ijmcp.app.IjMcpPluginBuildInfo
import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpServerStatus
import ai.plyxal.ijmcp.model.IjMcpTargetRegistration
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.util.BuildNumber
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

class IjMcpSettingsConfigurable : SearchableConfigurable {
    private val settingsService = service<IjMcpSettingsService>()
    private val appService = service<IjMcpAppService>()
    private val dateFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())
    private val pairingMessaging = IjMcpPairingMessaging(dateFormatter)

    private val enabledCheckBox = JCheckBox("Enable local MCP server")
    private val portSpinner = JSpinner(SpinnerNumberModel(8765, 0, 65535, 1))
    private val serverStatusLabel = JBLabel("Server status: stopped")
    private val pluginBuildLabel = JBLabel("Plugin build: unknown")
    private val compatibilityLabel = JBLabel("Compatibility: unknown")
    private val operatorGuidanceLabel = JBLabel("Operator guidance: unknown")
    private val targetSelector = JComboBox<TargetOption>()
    private val refreshTargetsButton = JButton("Refresh Targets")
    private val targetIdentityLabel = JBLabel("Target: none detected")
    private val targetProjectLabel = JBLabel("Project: none detected")
    private val endpointStatusLabel = JBLabel("Endpoint: not running")
    private val pairingStatusLabel = JBLabel("Pairing status: unknown")
    private val registryStatusLabel = JBLabel("Registry status: unknown")
    private val pairingCodeField = JTextField().apply {
        isEditable = false
        columns = 18
    }
    private val pairingCodeExpiryLabel = JBLabel("Pairing code: no active code. Generate one when you are ready to pair a CLI or gateway.")
    private val pairingWorkflowLabel = JBLabel("Pairing workflow: select a running target to generate a one-time code.")
    private val resetImpactLabel = JBLabel("Reset impact: reset revokes existing CLI and gateway access for the selected target.")
    private val generatePairingCodeButton = JButton("Generate Pairing Code")
    private val copyPairingCodeButton = JButton("Copy Pairing Code")
    private val resetPairingButton = JButton("Reset CLI Access")
    private val diagnosticsArea = JTextArea(10, 80).apply {
        isEditable = false
        lineWrap = false
        wrapStyleWord = false
    }

    private var component: JPanel? = null
    private var cachedTargetStatuses: List<IjMcpTargetStatus> = emptyList()
    private var cachedRegistrationsByTargetId: Map<String, IjMcpTargetRegistration> = emptyMap()
    private var selectedTargetId: String? = null
    private var pairingCodeTargetId: String? = null
    private var activePairingCode: IssuedPairingCode? = null

    override fun getId(): String = "ai.plyxal.ijmcp.settings"

    override fun getDisplayName(): String = "IJ-MCP"

    override fun createComponent(): JComponent {
        if (component == null) {
            targetSelector.addActionListener {
                selectedTargetId = (targetSelector.selectedItem as? TargetOption)?.targetId
                renderSelectedTarget()
            }

            refreshTargetsButton.addActionListener {
                refreshTargetData()
            }

            generatePairingCodeButton.addActionListener {
                val targetId = selectedTargetId ?: return@addActionListener
                setPairingBusyState("Generating pairing code...")
                ApplicationManager.getApplication().executeOnPooledThread {
                    val issuedCode = appService.issuePairingCode(targetId)
                    ApplicationManager.getApplication().invokeLater {
                        if (issuedCode == null) {
                            activePairingCode = null
                            pairingCodeField.text = ""
                            pairingCodeExpiryLabel.text = "Pairing code: target unavailable. Refresh targets and retry."
                        } else {
                            renderPairingCode(issuedCode)
                        }
                        refreshTargetData()
                    }
                }
            }

            copyPairingCodeButton.addActionListener {
                val code = pairingCodeField.text.trim()
                if (code.isNotEmpty()) {
                    CopyPasteManager.getInstance().setContents(StringSelection(code))
                }
            }

            resetPairingButton.addActionListener {
                val targetId = selectedTargetId ?: return@addActionListener
                setPairingBusyState("Resetting pairing...")
                ApplicationManager.getApplication().executeOnPooledThread {
                    appService.resetAuthentication(targetId)
                    ApplicationManager.getApplication().invokeLater {
                        activePairingCode = null
                        pairingCodeField.text = ""
                        pairingCodeExpiryLabel.text = "Pairing code: no active code. Existing CLI and gateway sessions must pair again."
                        refreshTargetData()
                    }
                }
            }

            val targetActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                add(refreshTargetsButton)
                add(generatePairingCodeButton)
                add(copyPairingCodeButton)
                add(resetPairingButton)
            }

            val targetPanel = JPanel(BorderLayout()).apply {
                add(
                    FormBuilder.createFormBuilder()
                        .addLabeledComponent("Target", targetSelector)
                        .addComponent(targetActionsPanel)
                        .addLabeledComponent("Target Identity", targetIdentityLabel)
                        .addLabeledComponent("Project", targetProjectLabel)
                        .addLabeledComponent("Endpoint", endpointStatusLabel)
                        .addLabeledComponent("Pairing Status", pairingStatusLabel)
                        .addLabeledComponent("Registry Status", registryStatusLabel)
                        .addLabeledComponent("Pairing Workflow", pairingWorkflowLabel)
                        .addLabeledComponent("Pairing Code", pairingCodeField)
                        .addLabeledComponent("Code Expiry", pairingCodeExpiryLabel)
                        .addLabeledComponent("Reset Impact", resetImpactLabel)
                        .addLabeledComponent("Diagnostics", JBScrollPane(diagnosticsArea))
                        .panel,
                    BorderLayout.NORTH,
                )
            }

            component = JPanel(BorderLayout()).apply {
                add(
                    FormBuilder.createFormBuilder()
                        .addComponent(enabledCheckBox)
                        .addLabeledComponent("Preferred Port", portSpinner)
                        .addLabeledComponent("Server Status", serverStatusLabel)
                        .addLabeledComponent("Plugin Build", pluginBuildLabel)
                        .addLabeledComponent("Compatibility", compatibilityLabel)
                        .addLabeledComponent("Operator Guidance", operatorGuidanceLabel)
                        .addSeparator()
                        .addComponentFillVertically(targetPanel, 0)
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
            (portSpinner.value as Number).toInt() != state.port
    }

    override fun apply() {
        settingsService.update(
            enabled = enabledCheckBox.isSelected,
            port = (portSpinner.value as Number).toInt(),
        )

        serverStatusLabel.text = "Applying settings..."

        ApplicationManager.getApplication().executeOnPooledThread {
            val status = appService.applyConfiguredState()
            ApplicationManager.getApplication().invokeLater {
                renderStatus(status)
                refreshTargetData()
            }
        }
    }

    override fun reset() {
        val state = settingsService.snapshot()
        enabledCheckBox.isSelected = state.enabled
        portSpinner.value = state.port
        renderCompatibility()
        renderStatus(appService.status())
        activePairingCode = null
        pairingCodeField.text = ""
        pairingCodeExpiryLabel.text = "Pairing code: no active code. Generate one when you are ready to pair a CLI or gateway."
        refreshTargetData()
    }

    override fun disposeUIResources() {
        component = null
        cachedTargetStatuses = emptyList()
        cachedRegistrationsByTargetId = emptyMap()
        selectedTargetId = null
        pairingCodeTargetId = null
        activePairingCode = null
    }

    private fun refreshTargetData() {
        setTargetLoadingState()
        ApplicationManager.getApplication().executeOnPooledThread {
            val targetStatuses = appService.targetStatuses()
            val registrations = appService.targetRegistryStore()
                .readTargets()
                .associateBy { it.targetId }

            ApplicationManager.getApplication().invokeLater {
                cachedTargetStatuses = targetStatuses
                cachedRegistrationsByTargetId = registrations
                renderTargetSelector()
                renderSelectedTarget()
            }
        }
    }

    private fun renderStatus(status: IjMcpServerStatus) {
        serverStatusLabel.text = if (status.running) {
            "Running at ${status.endpointUrl}"
        } else {
            status.lastError?.let { "Stopped: $it" } ?: "Stopped"
        }

        if (!status.running) {
            operatorGuidanceLabel.text = buildAppGuidance(status)
        }
    }

    private fun renderTargetSelector() {
        val nextSelection = selectedTargetId
            ?.takeIf { targetId -> cachedTargetStatuses.any { it.descriptor.targetId == targetId } }
            ?: cachedTargetStatuses.firstOrNull()?.descriptor?.targetId

        targetSelector.removeAllItems()
        cachedTargetStatuses.forEach { status ->
            targetSelector.addItem(
                TargetOption(
                    targetId = status.descriptor.targetId,
                    label = buildString {
                        append(status.descriptor.projectName)
                        append(" [")
                        append(if (status.running) "running" else "stopped")
                        append("]")
                    },
                ),
            )
        }

        selectedTargetId = nextSelection
        if (nextSelection == null) {
            targetSelector.isEnabled = false
        } else {
            targetSelector.isEnabled = true
            for (index in 0 until targetSelector.itemCount) {
                val option = targetSelector.getItemAt(index)
                if (option.targetId == nextSelection) {
                    targetSelector.selectedIndex = index
                    break
                }
            }
        }
    }

    private fun renderSelectedTarget() {
        val targetId = (targetSelector.selectedItem as? TargetOption)?.targetId ?: selectedTargetId
        val status = cachedTargetStatuses.firstOrNull { it.descriptor.targetId == targetId }

        if (status == null) {
            selectedTargetId = null
            targetIdentityLabel.text = "Target: none detected"
            targetProjectLabel.text = "Project: open a project window to register a target"
            endpointStatusLabel.text = "Endpoint: not running"
            pairingStatusLabel.text = "Pairing status: unknown"
            registryStatusLabel.text = "Registry status: no active registration"
            operatorGuidanceLabel.text = "Operator guidance: open a normal project window, then enable IJ-MCP."
            diagnosticsArea.text = buildNoTargetDiagnostics()
            pairingCodeTargetId = null
            activePairingCode = null
            pairingCodeField.text = ""
            pairingWorkflowLabel.text = "Pairing workflow: select a running target to generate a one-time code."
            pairingCodeExpiryLabel.text = "Pairing code: no active code. Generate one when you are ready to pair a CLI or gateway."
            resetImpactLabel.text = "Reset impact: reset revokes existing CLI and gateway access for the selected target."
            copyPairingCodeButton.isEnabled = pairingCodeField.text.isNotBlank()
            generatePairingCodeButton.isEnabled = false
            resetPairingButton.isEnabled = false
            return
        }

        selectedTargetId = status.descriptor.targetId
        val registration = cachedRegistrationsByTargetId[status.descriptor.targetId]

        if (pairingCodeTargetId != status.descriptor.targetId) {
            activePairingCode = null
            pairingCodeField.text = ""
            pairingCodeExpiryLabel.text = pairingMessaging.codeExpiry(null)
            pairingCodeTargetId = null
        }

        targetIdentityLabel.text = "Target: ${status.descriptor.targetId}"
        targetProjectLabel.text = "Project: ${status.descriptor.projectName} (${status.descriptor.projectPath})"
        endpointStatusLabel.text = if (status.running) {
            "Endpoint: ${status.endpointUrl}"
        } else {
            "Endpoint: stopped"
        }
        pairingStatusLabel.text = if (status.requiresPairing) {
            "Pairing status: pairing required"
        } else {
            "Pairing status: paired"
        }
        registryStatusLabel.text = if (registration == null) {
            "Registry status: no active registration"
        } else {
            "Registry status: registered at ${registration.lastSeenAt}"
        }
        pairingWorkflowLabel.text = "Pairing workflow: ${pairingMessaging.pairingWorkflow(status, activePairingCode)}"
        resetImpactLabel.text = "Reset impact: ${pairingMessaging.resetImpact(status, activePairingCode)}"
        pairingCodeExpiryLabel.text = pairingMessaging.codeExpiry(activePairingCode)
        operatorGuidanceLabel.text = buildTargetGuidance(status)
        diagnosticsArea.text = buildDiagnostics(status, registration)
        generatePairingCodeButton.isEnabled = true
        resetPairingButton.isEnabled = true
        copyPairingCodeButton.isEnabled = pairingCodeField.text.isNotBlank()
    }

    private fun renderPairingCode(issuedCode: IssuedPairingCode) {
        pairingCodeTargetId = selectedTargetId
        activePairingCode = issuedCode
        pairingCodeField.text = issuedCode.code
        pairingCodeExpiryLabel.text = pairingMessaging.codeExpiry(issuedCode)
        copyPairingCodeButton.isEnabled = true
    }

    private fun setTargetLoadingState() {
        targetIdentityLabel.text = "Target: refreshing..."
        targetProjectLabel.text = "Project: refreshing..."
        endpointStatusLabel.text = "Endpoint: refreshing..."
        pairingStatusLabel.text = "Pairing status: refreshing..."
        registryStatusLabel.text = "Registry status: refreshing..."
        operatorGuidanceLabel.text = "Operator guidance: refreshing target state..."
        pairingWorkflowLabel.text = "Pairing workflow: refreshing target state..."
        diagnosticsArea.text = "Refreshing target state..."
        generatePairingCodeButton.isEnabled = false
        resetPairingButton.isEnabled = false
        copyPairingCodeButton.isEnabled = pairingCodeField.text.isNotBlank()
    }

    private fun setPairingBusyState(message: String) {
        pairingCodeTargetId = null
        activePairingCode = null
        pairingCodeField.text = ""
        pairingWorkflowLabel.text = "Pairing workflow: $message"
        pairingCodeExpiryLabel.text = "Pairing code: $message"
        resetImpactLabel.text = "Reset impact: wait for the current operation to finish before changing access."
        copyPairingCodeButton.isEnabled = false
        generatePairingCodeButton.isEnabled = false
        resetPairingButton.isEnabled = false
    }

    private fun renderCompatibility() {
        val applicationInfo = ApplicationInfo.getInstance()
        val currentBuild = applicationInfo.build
        val compatible = isCompatible(currentBuild)
        val supportedRange = "${IjMcpPluginBuildInfo.sinceBuild}..${IjMcpPluginBuildInfo.untilBuild}"
        val currentBuildText = currentBuild.asStringWithoutProductCode()

        pluginBuildLabel.text = "Plugin build: ${IjMcpPluginBuildInfo.pluginVersion}"
        compatibilityLabel.text = if (compatible) {
            "Compatibility: ${applicationInfo.fullApplicationName} build $currentBuildText is within supported range $supportedRange"
        } else {
            "Compatibility: ${applicationInfo.fullApplicationName} build $currentBuildText is outside supported range $supportedRange; use IntelliJ IDEA 2025.2.x"
        }
    }

    private fun isCompatible(currentBuild: BuildNumber): Boolean {
        val sinceBaseline = IjMcpPluginBuildInfo.sinceBuild.substringBefore('.').toIntOrNull() ?: return true
        val untilBaseline = IjMcpPluginBuildInfo.untilBuild.substringBefore('.').toIntOrNull() ?: return true
        val withinLowerBound = currentBuild.baselineVersion >= sinceBaseline
        val withinUpperBound = if (IjMcpPluginBuildInfo.untilBuild.endsWith(".*")) {
            currentBuild.baselineVersion == untilBaseline
        } else {
            currentBuild.baselineVersion <= untilBaseline
        }
        return withinLowerBound && withinUpperBound
    }

    private fun buildAppGuidance(status: IjMcpServerStatus): String {
        val configuredPort = settingsService.snapshot().port
        val detail = status.lastError

        return when {
            detail == "No open IntelliJ project window is available." ->
                "Operator guidance: open a normal project window, search Settings for IJ-MCP, and click Apply."
            detail != null ->
                "Operator guidance: resolve the startup issue shown in Diagnostics, then click Apply again."
            else ->
                "Operator guidance: enable IJ-MCP and click Apply to start the local MCP server."
        }.let { message ->
            if (status.running || configuredPort == status.port || configuredPort == 0) {
                message
            } else {
                "$message Preferred port $configuredPort is not active."
            }
        }
    }

    private fun buildTargetGuidance(status: IjMcpTargetStatus): String {
        val configuredPort = settingsService.snapshot().port

        return when {
            !status.running && status.lastError == "The project window does not expose a resolvable base path." ->
                "Operator guidance: open the project from disk so IJ-MCP can resolve a base path before starting."
            !status.running ->
                "Operator guidance: inspect Diagnostics for the startup failure, then click Refresh Targets or Apply after correcting it."
            configuredPort != 0 && status.port != configuredPort ->
                "Operator guidance: preferred port $configuredPort was unavailable; this target is running on ${status.port}. Re-discover or re-pair clients that expected the preferred port."
            status.requiresPairing ->
                "Operator guidance: the target is live. Generate a pairing code to authorize the companion CLI or coding agent."
            else ->
                "Operator guidance: the target is live and paired."
        }
    }

    private fun buildNoTargetDiagnostics(): String = buildString {
        appendLine("compatibilityState=${if (isCompatible(ApplicationInfo.getInstance().build)) "compatible" else "unsupported"}")
        appendLine("pluginVersion=${IjMcpPluginBuildInfo.pluginVersion}")
        appendLine("supportedBuildRange=${IjMcpPluginBuildInfo.sinceBuild}..${IjMcpPluginBuildInfo.untilBuild}")
        appendLine("currentIde=${ApplicationInfo.getInstance().fullApplicationName}")
        appendLine("currentBuild=${ApplicationInfo.getInstance().build.asStringWithoutProductCode()}")
        appendLine("operatorGuidance=open a normal project window to register an IJ-MCP target")
        append("targetState=none detected")
    }

    private fun buildDiagnostics(
        status: IjMcpTargetStatus,
        registration: IjMcpTargetRegistration?,
    ): String = buildString {
        appendLine("compatibilityState=${if (isCompatible(ApplicationInfo.getInstance().build)) "compatible" else "unsupported"}")
        appendLine("pluginVersion=${IjMcpPluginBuildInfo.pluginVersion}")
        appendLine("supportedBuildRange=${IjMcpPluginBuildInfo.sinceBuild}..${IjMcpPluginBuildInfo.untilBuild}")
        appendLine("currentIde=${ApplicationInfo.getInstance().fullApplicationName}")
        appendLine("currentBuild=${ApplicationInfo.getInstance().build.asStringWithoutProductCode()}")
        appendLine("operatorGuidance=${buildTargetGuidance(status).removePrefix("Operator guidance: ")}")
        appendLine("configuredPort=${settingsService.snapshot().port}")
        appendLine("targetId=${status.descriptor.targetId}")
        appendLine("ideInstanceId=${status.descriptor.ideInstanceId}")
        appendLine("pid=${status.descriptor.pid}")
        appendLine("product=${status.descriptor.productName} (${status.descriptor.productCode})")
        appendLine("projectName=${status.descriptor.projectName}")
        appendLine("projectPath=${status.descriptor.projectPath}")
        appendLine("running=${status.running}")
        appendLine("endpointUrl=${status.endpointUrl}")
        appendLine("port=${status.port}")
        appendLine("requiresPairing=${status.requiresPairing}")
        appendLine("lastError=${status.lastError ?: "<none>"}")
        appendLine("registryFile=${appService.targetRegistryStore().registryFile()}")

        if (registration != null) {
            appendLine("registry.projectName=${registration.projectName}")
            appendLine("registry.projectPath=${registration.projectPath}")
            appendLine("registry.endpointUrl=${registration.endpointUrl}")
            appendLine("registry.lastSeenAt=${registration.lastSeenAt}")
        } else {
            appendLine("registry=<missing>")
        }
    }

    private data class TargetOption(
        val targetId: String,
        val label: String,
    ) {
        override fun toString(): String = label
    }
}
