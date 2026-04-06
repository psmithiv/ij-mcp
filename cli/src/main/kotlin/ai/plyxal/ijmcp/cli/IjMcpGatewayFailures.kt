package ai.plyxal.ijmcp.cli

internal class IjMcpTargetRouteFailure(
    val recoveryCode: String,
    override val message: String,
    val recoveryAction: String,
    val selectedTargetId: String? = null,
) : IllegalStateException(message)
