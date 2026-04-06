package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.model.IjMcpTargetRegistration
import ai.plyxal.ijmcp.model.IjMcpTargetStatus

internal object IjMcpDiagnosticsSummary {
    fun runtimeIdentity(status: IjMcpTargetStatus): String =
        "${status.descriptor.productName} | pid ${status.descriptor.pid} | IDE ${status.descriptor.ideInstanceId} | target ${status.descriptor.targetId}"

    fun registryEntry(
        status: IjMcpTargetStatus,
        registration: IjMcpTargetRegistration?,
    ): String {
        return if (registration == null) {
            "No live registry entry is published for target ${status.descriptor.targetId}."
        } else {
            "Registered at ${registration.lastSeenAt} via ${registration.endpointUrl}"
        }
    }

    fun lastError(status: IjMcpTargetStatus): String = status.lastError
        ?: if (status.running) {
            "No target error reported."
        } else {
            "Target is stopped without an explicit error."
        }
}
