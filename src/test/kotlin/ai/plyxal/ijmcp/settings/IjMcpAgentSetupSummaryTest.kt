package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
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
    fun trustTargetSelectsAndStoresCredentialForLocalCliCompatibility() {
        val tempRoot = Files.createTempDirectory("ijmcp-agent-config")
        val stateFile = tempRoot.resolve("client-state.json")
        val store = IjMcpAgentGatewayStateStore(stateFile = stateFile)

        store.trustTarget(
            targetId = "target-a",
            bearerToken = "target-token",
        )

        val state = Files.readString(stateFile)
        assertTrue(state.contains("\"selectedTargetId\": \"target-a\""))
        assertTrue(state.contains("\"target-a\": \"target-token\""))
    }

    @Test
    fun settingsDefaultToZeroSetupCodexConnection() {
        val state = IjMcpSettingsState()

        assertTrue(state.enabled)
        assertTrue(state.autoTrustLocalClients)
        assertTrue(state.manageCodexConfig)
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
    }

    @Test
    fun buildUsesSimpleReadyGuidanceWhenCodexIsManaged() {
        val summary = IjMcpAgentSetupSummaryBuilder.build(
            gatewayConfig = IjMcpAgentGatewayConfig(
                port = 3765,
                bearerToken = "gateway-token",
                endpointUrl = "http://127.0.0.1:3765/mcp",
                healthUrl = "http://127.0.0.1:3765/health",
            ),
            targetStatus = targetStatus(requiresPairing = false),
            pairingCode = null,
            autoTrustLocalClients = true,
            manageCodexConfig = true,
        )

        assertEquals(
            "Ready. Launch `codex` from this project terminal and ask it to open a file.",
            summary.guidance,
        )
    }

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
