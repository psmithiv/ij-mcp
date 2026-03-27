package ai.plyxal.ijmcp.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

internal const val IJ_MCP_PROTOCOL_VERSION = "2025-11-25"

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
    val version: Int = 1,
    val selectedTargetId: String? = null,
    val credentialsByTargetId: Map<String, String> = emptyMap(),
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
