package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.ide.IjMcpNavigationToolHandlers
import ai.plyxal.ijmcp.ide.IjMcpSearchToolHandlers
import ai.plyxal.ijmcp.mcp.IjMcpHttpServer
import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.mcp.IjMcpRequestRouter
import ai.plyxal.ijmcp.mcp.IjMcpServerConfig
import ai.plyxal.ijmcp.mcp.IjMcpToolRegistry
import ai.plyxal.ijmcp.model.IjMcpServerStatus
import ai.plyxal.ijmcp.settings.IjMcpSecretStore
import ai.plyxal.ijmcp.settings.IjMcpSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Application-level entry point for the MCP transport stack.
 *
 * Later tickets will wire persisted settings and token management into this service. The current
 * implementation keeps the transport lifecycle explicit so the routing layer can be exercised and
 * tested without depending on IntelliJ UI state.
 */
@Service(Service.Level.APP)
class IjMcpAppService : Disposable {
    private val logger = thisLogger()
    private val server = IjMcpHttpServer(
        IjMcpRequestRouter(
            IjMcpToolRegistry(
                handlers = IjMcpNavigationToolHandlers().all() + IjMcpSearchToolHandlers().all(),
            ),
        ),
    )
    private var activeConfiguration: IjMcpServerConfig? = null

    @Volatile
    private var status = stoppedStatus(IjMcpProtocol.defaultPort)

    internal fun start(config: IjMcpServerConfig = IjMcpServerConfig()): IjMcpServerStatus {
        return try {
            if (server.isRunning) {
                server.stop()
            }

            val boundPort = server.start(config)
            val nextStatus = IjMcpServerStatus(
                running = true,
                port = boundPort,
                endpointUrl = endpointUrl(boundPort),
            )

            activeConfiguration = config
            status = nextStatus
            logger.info("IJ-MCP transport started on ${nextStatus.endpointUrl}")
            nextStatus
        } catch (exception: Exception) {
            val nextStatus = stoppedStatus(config.port, exception.message ?: "Failed to start MCP transport.")
            activeConfiguration = null
            status = nextStatus
            logger.warn("Failed to start IJ-MCP transport", exception)
            nextStatus
        }
    }

    internal fun applyConfiguredState(): IjMcpServerStatus {
        val settings = service<IjMcpSettingsService>().snapshot()

        if (!settings.enabled) {
            stop(port = settings.port)
            return status
        }

        val token = service<IjMcpSecretStore>().loadToken()
        if (token.isNullOrBlank()) {
            stop(
                port = settings.port,
                lastError = "No bearer token is configured.",
            )
            return status
        }

        val desiredConfiguration = IjMcpServerConfig(
            port = settings.port,
            bearerToken = token,
        )

        if (desiredConfiguration == activeConfiguration && server.isRunning) {
            return status
        }

        return start(desiredConfiguration)
    }

    internal fun stop() {
        stop(port = status.port)
    }

    private fun stop(
        port: Int,
        lastError: String? = null,
    ) {
        server.stop()
        activeConfiguration = null
        status = stoppedStatus(port, lastError)
        logger.info("IJ-MCP transport stopped")
    }

    internal fun status(): IjMcpServerStatus = status

    override fun dispose() {
        server.close()
        activeConfiguration = null
        status = stoppedStatus(status.port)
    }

    private fun stoppedStatus(
        port: Int,
        lastError: String? = null,
    ) = IjMcpServerStatus(
        running = false,
        port = port,
        endpointUrl = endpointUrl(port),
        lastError = lastError,
    )

    private fun endpointUrl(port: Int): String = "http://127.0.0.1:$port${IjMcpProtocol.endpointPath}"
}
