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
        val state = stateStore.load()
        val selectedTargetId = state.selectedTargetId
        if (selectedTargetId.isNullOrBlank()) {
            throw IllegalStateException(
                "No sticky target is selected. Run `targets list` and `targets select <targetId>`.",
            )
        }

        val registration = registryReader.readTargets().firstOrNull { it.targetId == selectedTargetId }
            ?: throw IllegalStateException(
                "Selected target $selectedTargetId is unavailable. Run `targets list` and `targets select <targetId>`.",
            )

        val bearerToken = state.credentialsByTargetId[selectedTargetId]
        if (bearerToken.isNullOrBlank()) {
            throw IllegalStateException(
                "No stored credential exists for target $selectedTargetId. Issue a pairing code in the plugin UI and run `targets pair --code <pairingCode> $selectedTargetId`.",
            )
        }

        val health = httpClient.health(registration).getOrElse { exception ->
            throw IllegalStateException(
                "Selected target $selectedTargetId is unreachable: ${exception.message}. Reopen the IDE window or run `targets list` and `targets select <targetId>`.",
            )
        }

        if (!health.running) {
            throw IllegalStateException(
                "Selected target $selectedTargetId is registered but not running. Reopen the IDE window or refresh plugin settings.",
            )
        }

        val resolvedTarget = IjMcpResolvedTarget(
            registration = registration,
            health = health,
            bearerToken = bearerToken,
        )

        httpClient.initialize(resolvedTarget).getOrElse { exception ->
            throw IllegalStateException(
                "Initialization against target $selectedTargetId failed: ${exception.message}. Re-pair the target if credentials were reset.",
            )
        }

        resolvedTarget
    }
}
