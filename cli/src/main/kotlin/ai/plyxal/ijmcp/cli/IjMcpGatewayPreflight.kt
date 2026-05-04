package ai.plyxal.ijmcp.cli

internal class IjMcpGatewayPreflight(
    private val selectedTargetResolver: IjMcpSelectedTargetResolver,
    private val routeCacheTtlMillis: Long = 5_000,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var initializedTargetId: String? = null

    @Volatile
    private var cachedTarget: CachedTarget? = null

    @Synchronized
    fun prepareFor(requestMethod: String?): Result<IjMcpResolvedTarget> = runCatching {
        val requiresInitialization = requestMethod != "initialize" && requestMethod != "notifications/initialized"
        val now = currentTimeMillis()
        val selectedTargetId = selectedTargetResolver.selectedTargetId().getOrElse { exception ->
            throw exception
        }
        val cachedTarget = cachedTarget

        if (
            requiresInitialization &&
            selectedTargetId != null &&
            initializedTargetId == selectedTargetId &&
            cachedTarget != null &&
            cachedTarget.target.registration.targetId == selectedTargetId &&
            now < cachedTarget.expiresAtMillis
        ) {
            return@runCatching cachedTarget.target
        }

        val target = selectedTargetResolver.resolveSelectedHealthyTarget().getOrElse { exception ->
            throw exception
        }
        this.cachedTarget = CachedTarget(
            target = target,
            expiresAtMillis = now + routeCacheTtlMillis.coerceAtLeast(0),
        )

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
        cachedTarget = null
    }

    @Synchronized
    fun markInitialized(targetId: String) {
        initializedTargetId = targetId
    }

    fun initializedTargetId(): String? = initializedTargetId

    private data class CachedTarget(
        val target: IjMcpResolvedTarget,
        val expiresAtMillis: Long,
    )
}
