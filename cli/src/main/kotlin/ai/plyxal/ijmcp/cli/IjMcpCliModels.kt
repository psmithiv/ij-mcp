package ai.plyxal.ijmcp.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val IJ_MCP_PROTOCOL_VERSION = "2025-11-25"
internal const val IJ_MCP_GATEWAY_DEFAULT_PORT = 3765

@Serializable
internal data class IjMcpTargetRegistrySnapshot(
    val version: Int = 1,
    val targets: List<IjMcpTargetRegistration> = emptyList(),
)

@Serializable
internal data class IjMcpTargetRegistration(
    val targetId: String,
    val ideInstanceId: String,
    val pid: Long,
    val productCode: String,
    val productName: String,
    val projectName: String,
    val projectPath: String,
    val endpointUrl: String,
    val port: Int,
    val protocolVersion: String,
    val requiresPairing: Boolean,
    val lastSeenAt: String,
)

@Serializable
internal data class IjMcpClientState(
    val version: Int = 2,
    val selectedTargetId: String? = null,
    val credentialsByTargetId: Map<String, String> = emptyMap(),
    val gatewayPort: Int = IJ_MCP_GATEWAY_DEFAULT_PORT,
    val gatewayBearerToken: String? = null,
)

@Serializable
internal data class IjMcpHealthResponse(
    val protocolVersion: String,
    val requiresPairing: Boolean,
    val running: Boolean,
    val targetId: String? = null,
    val projectName: String? = null,
    val projectPath: String? = null,
    val endpointUrl: String? = null,
    val port: Int? = null,
)

@Serializable
internal data class IjMcpPairingResponse(
    val status: String,
    val bearerToken: String? = null,
    val protocolVersion: String? = null,
    val requiresPairing: Boolean? = null,
    val targetId: String? = null,
    val endpointUrl: String? = null,
    val projectName: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

internal data class IjMcpResolvedTarget(
    val registration: IjMcpTargetRegistration,
    val health: IjMcpHealthResponse,
    val bearerToken: String,
)

internal data class IjMcpJsonRpcResult(
    val json: JsonObject,
)

internal data class IjMcpHttpExchangeResult(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String? = null,
)

internal data class IjMcpGatewayConfig(
    val port: Int,
    val bearerToken: String,
)

internal data class IjMcpSelectedTargetRouteSummary(
    val routeStatus: String,
    val selectedTargetId: String? = null,
    val projectName: String? = null,
    val endpointUrl: String? = null,
)
