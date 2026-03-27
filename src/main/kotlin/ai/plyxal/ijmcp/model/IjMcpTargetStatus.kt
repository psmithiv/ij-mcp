package ai.plyxal.ijmcp.model

data class IjMcpTargetDescriptor(
    val targetId: String,
    val ideInstanceId: String,
    val pid: Long,
    val productCode: String,
    val productName: String,
    val projectName: String,
    val projectPath: String,
)

data class IjMcpTargetStatus(
    val descriptor: IjMcpTargetDescriptor,
    val running: Boolean,
    val port: Int,
    val endpointUrl: String,
    val lastError: String? = null,
)
