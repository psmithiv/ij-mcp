package ai.plyxal.ijmcp.model

data class IjMcpServerStatus(
    val running: Boolean,
    val port: Int,
    val endpointUrl: String,
    val lastError: String? = null,
)
