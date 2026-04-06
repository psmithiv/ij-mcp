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

    fun describeSelectedTargetStatus(): IjMcpSelectedTargetStatus {
        val state = stateStore.load()
        val selectedTargetId = state.selectedTargetId
        val registryFile = registryReader.registryFile().toString()

        if (selectedTargetId.isNullOrBlank()) {
            return IjMcpSelectedTargetStatus(
                routeStatus = "unselected",
                registryFile = registryFile,
                recoveryCode = "no_selection",
                recoveryAction = "Run `targets list` and `targets select <targetId>`.",
            )
        }

        val paired = state.credentialsByTargetId.containsKey(selectedTargetId)
        val registration = registryReader.readTargets().firstOrNull { it.targetId == selectedTargetId }
            ?: return IjMcpSelectedTargetStatus(
                routeStatus = "stale_selection",
                registryFile = registryFile,
                selectedTargetId = selectedTargetId,
                paired = paired,
                recoveryCode = "stale_target",
                recoveryAction = "Run `targets list` and `targets select <targetId>`.",
            )

        val health = httpClient.health(registration).getOrNull()
        if (health == null) {
            return IjMcpSelectedTargetStatus(
                routeStatus = "selected_unreachable",
                registryFile = registryFile,
                selectedTargetId = selectedTargetId,
                projectName = registration.projectName,
                projectPath = registration.projectPath,
                endpointUrl = registration.endpointUrl,
                paired = paired,
                requiresPairing = registration.requiresPairing,
                recoveryCode = "target_unreachable",
                recoveryAction = "Reopen the IDE window or run `targets list` and `targets select <targetId>`.",
            )
        }

        val recovery = when {
            !health.running -> {
                "target_not_running" to "Reopen the IDE window or refresh plugin settings."
            }

            health.requiresPairing && paired -> {
                "repair_required" to "Issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`."
            }

            health.requiresPairing -> {
                "pairing_required" to "Issue a pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`."
            }

            !paired -> {
                "pairing_required" to "Issue a pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`."
            }

            else -> null
        }

        return IjMcpSelectedTargetStatus(
            routeStatus = when (recovery?.first) {
                "target_not_running" -> "selected_stopped"
                "repair_required" -> "selected_repair_required"
                "pairing_required" -> "selected_unpaired"
                else -> "selected"
            },
            registryFile = registryFile,
            selectedTargetId = selectedTargetId,
            projectName = registration.projectName,
            projectPath = registration.projectPath,
            endpointUrl = registration.endpointUrl,
            paired = paired,
            running = health.running,
            requiresPairing = health.requiresPairing,
            recoveryCode = recovery?.first,
            recoveryAction = recovery?.second,
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
            val recoveryCode = if (bearerToken.isBlank()) "pairing_required" else "repair_required"
            val recoveryAction = if (bearerToken.isBlank()) {
                "Issue a pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`."
            } else {
                "Issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`."
            }
            throw IjMcpTargetRouteFailure(
                recoveryCode = recoveryCode,
                message = "Target $selectedTargetId currently requires pairing.",
                recoveryAction = recoveryAction,
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
