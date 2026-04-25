package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import java.time.Instant

private const val IJ_MCP_GATEWAY_SERVER_NAME = "ij-mcp"
private const val IJ_MCP_GATEWAY_SERVE_COMMAND = "./gradlew :cli:run --args='gateway serve'"

internal data class IjMcpAgentSetupSummary(
    val guidance: String,
    val gatewayTokenExportCommand: String,
    val codexCommand: String,
    val exactConfig: String,
)

internal object IjMcpAgentSetupSummaryBuilder {
    fun build(
        gatewayConfig: IjMcpAgentGatewayConfig,
        targetStatus: IjMcpTargetStatus?,
        pairingCode: IssuedPairingCode?,
        nowProvider: () -> Instant = Instant::now,
    ): IjMcpAgentSetupSummary {
        val activeCode = pairingCode?.takeUnless { nowProvider().isAfter(it.expiresAt) }
        val gatewayTokenExportCommand = "export $IJ_MCP_GATEWAY_TOKEN_ENV_VAR='${gatewayConfig.bearerToken}'"
        val codexCommand = "codex mcp add $IJ_MCP_GATEWAY_SERVER_NAME --url ${gatewayConfig.endpointUrl} --bearer-token-env-var $IJ_MCP_GATEWAY_TOKEN_ENV_VAR"
        val exactConfig = buildString {
            appendLine("[mcp_servers.$IJ_MCP_GATEWAY_SERVER_NAME]")
            appendLine("url = \"${gatewayConfig.endpointUrl}\"")
            append("bearer_token_env_var = \"$IJ_MCP_GATEWAY_TOKEN_ENV_VAR\"")
        }

        val guidance = when {
            targetStatus == null ->
                "Open a normal project window, enable IJ-MCP, pair a target from the companion CLI, then run `$IJ_MCP_GATEWAY_SERVE_COMMAND`."

            !targetStatus.running ->
                "Start the selected target, pair it from the companion CLI, then run `$IJ_MCP_GATEWAY_SERVE_COMMAND`."

            activeCode != null ->
                "Pair the selected target with `./gradlew :cli:run --args='targets pair --code ${activeCode.code} ${targetStatus.descriptor.targetId}'`, then run `$IJ_MCP_GATEWAY_SERVE_COMMAND`."

            targetStatus.requiresPairing ->
                "Generate a pairing code for target ${targetStatus.descriptor.targetId}, pair it from the companion CLI, then run `$IJ_MCP_GATEWAY_SERVE_COMMAND`."

            else ->
                "Run `$IJ_MCP_GATEWAY_SERVE_COMMAND`, export the gateway token below, and add the exact MCP entry below to your coding agent."
        }

        return IjMcpAgentSetupSummary(
            guidance = guidance,
            gatewayTokenExportCommand = gatewayTokenExportCommand,
            codexCommand = codexCommand,
            exactConfig = exactConfig,
        )
    }
}
