package ai.plyxal.ijmcp.cli

internal class IjMcpGatewayPreflight(
    private val selectedTargetResolver: IjMcpSelectedTargetResolver,
) {
    @Volatile
    private var initializedTargetId: String? = null

    @Synchronized
    fun prepareFor(requestMethod: String?): Result<IjMcpResolvedTarget> = runCatching {
        val target = selectedTargetResolver.resolveSelectedHealthyTarget().getOrElse { exception ->
            throw exception
        }

        val requiresInitialization = requestMethod != "initialize" && requestMethod != "notifications/initialized"
        if (requiresInitialization && initializedTargetId != target.registration.targetId) {
            selectedTargetResolver.initializeTarget(target).getOrElse { exception ->
                throw exception
            }
            initializedTargetId = target.registration.targetId
        }

        if (!requiresInitialization) {
            initializedTargetId = null
        }

        target
    }

    @Synchronized
    fun clearInitialization() {
        initializedTargetId = null
    }

    @Synchronized
    fun markInitialized(targetId: String) {
        initializedTargetId = targetId
    }

    fun initializedTargetId(): String? = initializedTargetId
}
