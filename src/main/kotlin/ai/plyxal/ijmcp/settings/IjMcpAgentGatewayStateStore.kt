package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val IJ_MCP_GATEWAY_TOKEN_ENV_VAR = "IJ_MCP_GATEWAY_TOKEN"
private const val IJ_MCP_GATEWAY_DEFAULT_PORT = 3765

internal data class IjMcpAgentGatewayConfig(
    val port: Int,
    val bearerToken: String,
    val endpointUrl: String,
    val healthUrl: String,
)

internal data class IjMcpAgentGatewaySelection(
    val selectedTargetId: String?,
    val hasCredentialForSelectedTarget: Boolean,
)

internal class IjMcpAgentGatewayStateStore(
    private val stateFile: Path = defaultStateFile(),
    private val tokenFactory: () -> String = { "ijmcp-gateway-${UUID.randomUUID()}" },
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun ensureGatewayConfig(): IjMcpAgentGatewayConfig {
        val state = load()
        val normalizedPort = state.gatewayPort.takeIf { it in 1..65535 } ?: IJ_MCP_GATEWAY_DEFAULT_PORT
        val normalizedToken = state.gatewayBearerToken
            ?.takeIf { it.isNotBlank() }
            ?: tokenFactory()

        val normalizedState = state.copy(
            gatewayPort = normalizedPort,
            gatewayBearerToken = normalizedToken,
        )
        if (normalizedState != state) {
            save(normalizedState)
        }

        return IjMcpAgentGatewayConfig(
            port = normalizedPort,
            bearerToken = normalizedToken,
            endpointUrl = "http://127.0.0.1:$normalizedPort${IjMcpProtocol.endpointPath}",
            healthUrl = "http://127.0.0.1:$normalizedPort${IjMcpProtocol.healthPath}",
        )
    }

    fun selection(): IjMcpAgentGatewaySelection {
        val state = load()
        val selectedTargetId = state.selectedTargetId?.takeIf { it.isNotBlank() }

        return IjMcpAgentGatewaySelection(
            selectedTargetId = selectedTargetId,
            hasCredentialForSelectedTarget = selectedTargetId != null &&
                state.credentialsByTargetId[selectedTargetId]?.isNotBlank() == true,
        )
    }

    fun trustTarget(
        targetId: String,
        bearerToken: String,
    ) {
        if (targetId.isBlank() || bearerToken.isBlank()) {
            return
        }

        val state = load()
        save(
            state.copy(
                selectedTargetId = targetId,
                credentialsByTargetId = state.credentialsByTargetId + (targetId to bearerToken),
            ),
        )
    }

    fun stateFile(): Path = stateFile

    private fun load(): IjMcpAgentClientState {
        if (!Files.exists(stateFile)) {
            return IjMcpAgentClientState()
        }

        val rawState = Files.readString(stateFile)
        if (rawState.isBlank()) {
            return IjMcpAgentClientState()
        }

        return runCatching {
            json.decodeFromString<IjMcpAgentClientState>(rawState)
        }.getOrDefault(IjMcpAgentClientState())
    }

    private fun save(state: IjMcpAgentClientState) {
        Files.createDirectories(stateFile.parent)
        val temporaryFile = Files.createTempFile(stateFile.parent, "client-state", ".json.tmp")
        Files.writeString(
            temporaryFile,
            json.encodeToString(IjMcpAgentClientState.serializer(), state),
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        try {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private fun defaultStateFile(): Path = Path.of(System.getProperty("user.home"), ".ij-mcp", "client-state.json")
    }
}

@Serializable
private data class IjMcpAgentClientState(
    val version: Int = 2,
    val selectedTargetId: String? = null,
    val credentialsByTargetId: Map<String, String> = emptyMap(),
    val gatewayPort: Int = IJ_MCP_GATEWAY_DEFAULT_PORT,
    val gatewayBearerToken: String? = null,
)
