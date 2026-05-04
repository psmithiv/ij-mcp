package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IjMcpAgentSetupSummaryTest {
    @Test
    fun ensureGatewayConfigBootstrapsStableTokenInClientState() {
        val tempRoot = Files.createTempDirectory("ijmcp-agent-config")
        val stateFile = tempRoot.resolve("client-state.json")
        val store = IjMcpAgentGatewayStateStore(
            stateFile = stateFile,
            tokenFactory = { "gateway-token" },
        )

        val config = store.ensureGatewayConfig()

        assertEquals(3765, config.port)
        assertEquals("gateway-token", config.bearerToken)
        assertEquals("http://127.0.0.1:3765/mcp", config.endpointUrl)
        assertEquals("http://127.0.0.1:3765/health", config.healthUrl)
        assertTrue(Files.readString(stateFile).contains("\"gatewayBearerToken\": \"gateway-token\""))
    }

    @Test
    fun selectionReportsSelectedTargetWithoutExposingCredential() {
        val tempRoot = Files.createTempDirectory("ijmcp-agent-config")
        val stateFile = tempRoot.resolve("client-state.json")
        val store = IjMcpAgentGatewayStateStore(stateFile = stateFile)
        Files.writeString(
            stateFile,
            """
            {
              "version": 2,
              "selectedTargetId": "target-a",
              "credentialsByTargetId": {
                "target-a": "secret-target-token"
              },
              "gatewayPort": 3766,
              "gatewayBearerToken": "gateway-token"
            }
            """.trimIndent(),
        )

        val selection = store.selection()

        assertEquals("target-a", selection.selectedTargetId)
        assertTrue(selection.hasCredentialForSelectedTarget)
        assertFalse(selection.toString().contains("secret-target-token"))
    }

    @Test
    fun buildIncludesExactCodexConfigurationAndPairCommand() {
        val summary = IjMcpAgentSetupSummaryBuilder.build(
            gatewayConfig = IjMcpAgentGatewayConfig(
                port = 3765,
                bearerToken = "gateway-token",
                endpointUrl = "http://127.0.0.1:3765/mcp",
                healthUrl = "http://127.0.0.1:3765/health",
            ),
            targetStatus = targetStatus(requiresPairing = true),
            pairingCode = IssuedPairingCode(
                code = "PAIR1234",
                expiresAt = Instant.parse("2026-04-25T18:30:00Z"),
            ),
            nowProvider = { Instant.parse("2026-04-25T18:00:00Z") },
        )

        assertEquals("export IJ_MCP_GATEWAY_TOKEN='gateway-token'", summary.gatewayTokenExportCommand)
        assertEquals("http://127.0.0.1:3765/mcp", summary.gatewayEndpointUrl)
        assertEquals("http://127.0.0.1:3765/health", summary.gatewayHealthUrl)
        assertEquals(
            "codex mcp add ij-mcp --url http://127.0.0.1:3765/mcp --bearer-token-env-var IJ_MCP_GATEWAY_TOKEN",
            summary.codexCommand,
        )
        assertEquals(
            """
            [mcp_servers.ij-mcp]
            url = "http://127.0.0.1:3765/mcp"
            bearer_token_env_var = "IJ_MCP_GATEWAY_TOKEN"
            """.trimIndent(),
            summary.exactConfig,
        )
        assertTrue(summary.guidance.contains("targets pair --code PAIR1234 target-a"))
        assertTrue(summary.readiness.contains("target target-a is waiting for pairing"))
    }

    @Test
    fun buildWarnsWhenCliSelectionPointsAtDifferentTarget() {
        val summary = IjMcpAgentSetupSummaryBuilder.build(
            gatewayConfig = gatewayConfig(),
            targetStatus = targetStatus(requiresPairing = false),
            pairingCode = null,
            gatewaySelection = IjMcpAgentGatewaySelection(
                selectedTargetId = "target-b",
                hasCredentialForSelectedTarget = true,
            ),
        )

        assertTrue(summary.readiness.contains("CLI is selected to target target-b"))
        assertTrue(summary.readiness.contains("not this project target target-a"))
        assertTrue(summary.readiness.contains("targets select target-a"))
        assertTrue(summary.guidance.contains("targets select target-a"))
    }

    @Test
    fun buildWarnsWhenCliHasNoSelectedTargetForCurrentProject() {
        val summary = IjMcpAgentSetupSummaryBuilder.build(
            gatewayConfig = gatewayConfig(),
            targetStatus = targetStatus(requiresPairing = false),
            pairingCode = null,
            gatewaySelection = IjMcpAgentGatewaySelection(
                selectedTargetId = null,
                hasCredentialForSelectedTarget = false,
            ),
        )

        assertTrue(summary.readiness.contains("no CLI target is selected"))
        assertTrue(summary.readiness.contains("targets select target-a"))
        assertTrue(summary.guidance.contains("targets select target-a"))
    }

    @Test
    fun buildWarnsWhenSelectedTargetHasNoStoredCredential() {
        val summary = IjMcpAgentSetupSummaryBuilder.build(
            gatewayConfig = gatewayConfig(),
            targetStatus = targetStatus(requiresPairing = false),
            pairingCode = null,
            gatewaySelection = IjMcpAgentGatewaySelection(
                selectedTargetId = "target-a",
                hasCredentialForSelectedTarget = false,
            ),
        )

        assertTrue(summary.readiness.contains("has no stored credential"))
        assertTrue(summary.guidance.contains("has no stored credential"))
    }

    private fun gatewayConfig(): IjMcpAgentGatewayConfig = IjMcpAgentGatewayConfig(
        port = 3765,
        bearerToken = "gateway-token",
        endpointUrl = "http://127.0.0.1:3765/mcp",
        healthUrl = "http://127.0.0.1:3765/health",
    )

    private fun targetStatus(requiresPairing: Boolean): IjMcpTargetStatus = IjMcpTargetStatus(
        descriptor = IjMcpTargetDescriptor(
            targetId = "target-a",
            ideInstanceId = "ide-a",
            pid = 4321,
            productCode = "IC",
            productName = "IntelliJ IDEA Community Edition",
            projectName = "ij-mcp",
            projectPath = "/tmp/ij-mcp",
        ),
        running = true,
        port = 8765,
        endpointUrl = "http://127.0.0.1:8765/mcp",
        requiresPairing = requiresPairing,
    )
}
