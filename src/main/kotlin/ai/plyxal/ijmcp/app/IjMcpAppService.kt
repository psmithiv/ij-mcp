package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.model.IjMcpServerStatus
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
class IjMcpAppService : Disposable {
    private val logger = thisLogger()
    private val ideInstanceId = UUID.randomUUID().toString()
    private val runtimesByTargetId = ConcurrentHashMap<String, IjMcpProjectRuntimeService>()
    private val targetRegistryStore = IjMcpTargetRegistryStore()

    internal fun ideInstanceId(): String = ideInstanceId

    internal fun targetRegistryStore(): IjMcpTargetRegistryStore = targetRegistryStore

    internal fun registerRuntime(runtime: IjMcpProjectRuntimeService) {
        runtimesByTargetId[runtime.descriptor().targetId] = runtime
        logger.info("Registered IJ-MCP target ${runtime.descriptor().targetId} for project ${runtime.descriptor().projectName}")
    }

    internal fun unregisterRuntime(targetId: String) {
        runtimesByTargetId.remove(targetId)
        logger.info("Unregistered IJ-MCP target $targetId")
    }

    internal fun applyConfiguredState(): IjMcpServerStatus {
        runtimesByTargetId.values.forEach { it.applyConfiguredState() }
        return status()
    }

    internal fun status(): IjMcpServerStatus {
        val targetStatuses = targetStatuses()
        if (targetStatuses.isEmpty()) {
            return stoppedStatus(
                port = IjMcpProtocol.defaultPort,
                lastError = "No open IntelliJ project window is available.",
            )
        }

        val runningStatuses = targetStatuses.filter { it.running }
        if (runningStatuses.isNotEmpty()) {
            val primary = runningStatuses.first()
            return IjMcpServerStatus(
                running = true,
                port = primary.port,
                endpointUrl = primary.endpointUrl,
            )
        }

        val firstStopped = targetStatuses.first()
        return stoppedStatus(
            port = firstStopped.port,
            lastError = firstStopped.lastError ?: "No IJ-MCP target is running.",
        )
    }

    internal fun targetStatuses(): List<IjMcpTargetStatus> = runtimesByTargetId.values
        .map { it.status() }
        .sortedWith(compareBy({ it.descriptor.projectName.lowercase() }, { it.descriptor.targetId }))

    override fun dispose() {
        runtimesByTargetId.clear()
    }

    private fun stoppedStatus(
        port: Int,
        lastError: String? = null,
    ) = IjMcpServerStatus(
        running = false,
        port = port,
        endpointUrl = "http://127.0.0.1:$port${IjMcpProtocol.endpointPath}",
        lastError = lastError,
    )
}
