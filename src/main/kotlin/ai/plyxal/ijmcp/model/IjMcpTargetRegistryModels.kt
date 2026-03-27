package ai.plyxal.ijmcp.model

import kotlinx.serialization.Serializable

@Serializable
data class IjMcpTargetRegistrySnapshot(
    val version: Int = 1,
    val targets: List<IjMcpTargetRegistration> = emptyList(),
)

@Serializable
data class IjMcpTargetRegistration(
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
