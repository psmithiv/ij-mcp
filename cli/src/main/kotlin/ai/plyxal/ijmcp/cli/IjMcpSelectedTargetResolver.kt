package ai.plyxal.ijmcp.cli

internal class IjMcpSelectedTargetResolver(
    private val stateStore: IjMcpCliStateStore,
    private val registryReader: IjMcpTargetRegistryReader,
    private val httpClient: IjMcpCliHttpClient,
) {
    fun describeStickyRoute(): IjMcpSelectedTargetRouteSummary {
        val state = stateStore.load()
        val selectedTargetId = state.selectedTargetId
        if (selectedTargetId.isNullOrBlank()) {
            return IjMcpSelectedTargetRouteSummary(routeStatus = "unselected")
        }

        val registration = registryReader.readTargets().firstOrNull { it.targetId == selectedTargetId }
            ?: return IjMcpSelectedTargetRouteSummary(
                routeStatus = "stale_selection",
                selectedTargetId = selectedTargetId,
            )

        return IjMcpSelectedTargetRouteSummary(
            routeStatus = "selected",
            selectedTargetId = selectedTargetId,
            projectName = registration.projectName,
            endpointUrl = registration.endpointUrl,
        )
    }

    fun resolveSelectedConnectedTarget(): Result<IjMcpResolvedTarget> = runCatching {
        val target = resolveSelectedHealthyTarget().getOrElse { exception ->
            throw exception
        }
        initializeTarget(target).getOrElse { exception ->
            throw exception
        }
        target
    }

    fun resolveSelectedHealthyTarget(): Result<IjMcpResolvedTarget> = runCatching {
        val state = stateStore.load()
        val selectedTargetId = state.selectedTargetId
        if (selectedTargetId.isNullOrBlank()) {
            throw IjMcpTargetRouteFailure(
                recoveryCode = "no_selection",
                message = "No sticky target is selected.",
                recoveryAction = "Run `targets list` and `targets select <targetId>`.",
            )
        }

        val registration = registryReader.readTargets().firstOrNull { it.targetId == selectedTargetId }
            ?: throw IjMcpTargetRouteFailure(
                recoveryCode = "stale_target",
                message = "Selected target $selectedTargetId is unavailable.",
                recoveryAction = "Run `targets list` and `targets select <targetId>`.",
                selectedTargetId = selectedTargetId,
            )

        val bearerToken = state.credentialsByTargetId[selectedTargetId]
        if (bearerToken.isNullOrBlank()) {
            throw IjMcpTargetRouteFailure(
                recoveryCode = "pairing_required",
                message = "No stored credential exists for target $selectedTargetId.",
                recoveryAction = "Issue a pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`.",
                selectedTargetId = selectedTargetId,
            )
        }

        val health = httpClient.health(registration).getOrElse { exception ->
            throw IjMcpTargetRouteFailure(
                recoveryCode = "target_unreachable",
                message = "Selected target $selectedTargetId is unreachable: ${exception.message}",
                recoveryAction = "Reopen the IDE window or run `targets list` and `targets select <targetId>`.",
                selectedTargetId = selectedTargetId,
            )
        }

        if (health.requiresPairing) {
            throw IjMcpTargetRouteFailure(
                recoveryCode = "pairing_required",
                message = "Target $selectedTargetId currently requires pairing.",
                recoveryAction = "Issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`.",
                selectedTargetId = selectedTargetId,
            )
        }

        if (!health.running) {
            throw IjMcpTargetRouteFailure(
                recoveryCode = "target_not_running",
                message = "Selected target $selectedTargetId is registered but not running.",
                recoveryAction = "Reopen the IDE window or refresh plugin settings.",
                selectedTargetId = selectedTargetId,
            )
        }

        IjMcpResolvedTarget(
            registration = registration,
            health = health,
            bearerToken = bearerToken,
        )
    }

    fun initializeTarget(target: IjMcpResolvedTarget): Result<Unit> = runCatching {
        httpClient.initialize(target).getOrElse { exception ->
            val message = exception.message ?: "Initialization failed."
            if ("401" in message || "Unauthorized" in message) {
                throw IjMcpTargetRouteFailure(
                    recoveryCode = "repair_required",
                    message = "Initialization against target ${target.registration.targetId} failed: $message",
                    recoveryAction = "Issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> ${target.registration.targetId}`.",
                    selectedTargetId = target.registration.targetId,
                )
            }

            throw IjMcpTargetRouteFailure(
                recoveryCode = "initialize_failed",
                message = "Initialization against target ${target.registration.targetId} failed: $message",
                recoveryAction = "Retry the request. If the target was reset, issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> ${target.registration.targetId}`.",
                selectedTargetId = target.registration.targetId,
            )
        }
    }
}
