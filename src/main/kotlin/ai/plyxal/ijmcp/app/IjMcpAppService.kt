package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpHttpServer
import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.mcp.IjMcpRequestRouter
import ai.plyxal.ijmcp.mcp.IjMcpServerConfig
import ai.plyxal.ijmcp.mcp.IjMcpToolRegistry
import ai.plyxal.ijmcp.model.IjMcpServerStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
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
    private val server = IjMcpHttpServer(IjMcpRequestRouter(IjMcpToolRegistry()))

    @Volatile
    private var status = stoppedStatus(IjMcpProtocol.defaultPort)

    internal fun start(config: IjMcpServerConfig = IjMcpServerConfig()): IjMcpServerStatus {
        return try {
            val boundPort = server.start(config)
            val nextStatus = IjMcpServerStatus(
                running = true,
                port = boundPort,
                endpointUrl = endpointUrl(boundPort),
            )

            status = nextStatus
            logger.info("IJ-MCP transport started on ${nextStatus.endpointUrl}")
            nextStatus
        } catch (exception: Exception) {
            val nextStatus = stoppedStatus(config.port, exception.message ?: "Failed to start MCP transport.")
            status = nextStatus
            logger.warn("Failed to start IJ-MCP transport", exception)
            nextStatus
        }
    }

    internal fun stop() {
        server.stop()
        status = stoppedStatus(status.port)
        logger.info("IJ-MCP transport stopped")
    }

    internal fun status(): IjMcpServerStatus = status

    override fun dispose() {
        server.close()
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
