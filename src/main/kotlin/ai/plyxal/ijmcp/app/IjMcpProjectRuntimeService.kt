package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.ide.IjMcpNavigationToolHandlers
import ai.plyxal.ijmcp.ide.IjMcpSearchToolHandlers
import ai.plyxal.ijmcp.ide.IjMcpToolWindowToolHandlers
import ai.plyxal.ijmcp.mcp.IjMcpHttpServer
import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.mcp.IjMcpRequestRouter
import ai.plyxal.ijmcp.mcp.IjMcpServerConfig
import ai.plyxal.ijmcp.mcp.IjMcpToolRegistry
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.intellij.openapi.application.ApplicationInfo
import ai.plyxal.ijmcp.settings.IjMcpSecretStore
import ai.plyxal.ijmcp.settings.IjMcpSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.net.BindException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class IjMcpProjectRuntimeService(
    private val project: Project,
) : Disposable {
    private val logger = thisLogger()
    private val appService = service<IjMcpAppService>()
    private val settingsService = service<IjMcpSettingsService>()
    private val secretStore = service<IjMcpSecretStore>()
    private val targetId = UUID.randomUUID().toString()
    private val authManager = IjMcpTargetAuthManager(
        targetId = targetId,
        credentialStore = secretStore,
    )
    private val heartbeatExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val server = IjMcpHttpServer(
        IjMcpRequestRouter(
            IjMcpToolRegistry(
                handlers = IjMcpNavigationToolHandlers(project).all() +
                    IjMcpToolWindowToolHandlers(project).all() +
                    IjMcpSearchToolHandlers(project).all(),
            ),
        ),
        security = authManager,
        statusProvider = ::currentStatus,
        onAuthenticationStateChanged = ::refreshRegistrationState,
    )
    private val descriptor = IjMcpTargetDescriptor(
        targetId = targetId,
        ideInstanceId = appService.ideInstanceId(),
        pid = ProcessHandle.current().pid(),
        productCode = ApplicationInfo.getInstance().build.productCode,
        productName = ApplicationInfo.getInstance().fullApplicationName,
        projectName = project.name,
        projectPath = project.basePath?.let { Path.of(it).normalize().toString() }.orEmpty(),
    )
    private var activeConfiguration: IjMcpServerConfig? = null
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var status = stoppedStatus(IjMcpProtocol.defaultPort, "Target not started.")

    init {
        appService.registerRuntime(this)
    }

    internal fun descriptor(): IjMcpTargetDescriptor = descriptor

    internal fun currentStatus(): IjMcpTargetStatus = status

    internal fun applyConfiguredState(): IjMcpTargetStatus {
        val settings = settingsService.snapshot()

        if (!settings.enabled) {
            stop(port = settings.port)
            return status
        }

        if (descriptor.projectPath.isBlank()) {
            stop(
                port = settings.port,
                lastError = "The project window does not expose a resolvable base path.",
            )
            return status
        }

        secretStore.loadLegacyToken()?.let { legacyToken ->
            authManager.bootstrapLegacyToken(legacyToken)
            secretStore.storeLegacyToken(null)
        }

        val desiredConfiguration = IjMcpServerConfig(
            port = settings.port,
        )

        if (desiredConfiguration == activeConfiguration && server.isRunning) {
            return status
        }

        return start(desiredConfiguration)
    }

    override fun dispose() {
        try {
            stop(port = status.port)
        } finally {
            appService.unregisterRuntime(descriptor.targetId)
            server.close()
            heartbeatExecutor.shutdownNow()
        }
    }

    private fun start(config: IjMcpServerConfig): IjMcpTargetStatus {
        return try {
            if (server.isRunning) {
                server.stop()
            }

            val boundPort = bindServer(config)
            val nextStatus = IjMcpTargetStatus(
                descriptor = descriptor,
                running = true,
                port = boundPort,
                endpointUrl = endpointUrl(boundPort),
                requiresPairing = authManager.requiresPairing(),
            )

            activeConfiguration = config.copy(port = boundPort)
            status = nextStatus
            publishRegistration()
            scheduleHeartbeat()
            logger.info("IJ-MCP target ${descriptor.targetId} started on ${nextStatus.endpointUrl}")
            nextStatus
        } catch (exception: Exception) {
            val nextStatus = stoppedStatus(config.port, exception.message ?: "Failed to start MCP transport.")
            activeConfiguration = null
            status = nextStatus
            appService.targetRegistryStore().remove(descriptor.targetId)
            logger.warn("Failed to start IJ-MCP target ${descriptor.targetId}", exception)
            nextStatus
        }
    }

    private fun bindServer(config: IjMcpServerConfig): Int {
        return try {
            server.start(config)
        } catch (exception: BindException) {
            if (config.port == 0) {
                throw exception
            }

            logger.info(
                "Preferred port ${config.port} is unavailable for target ${descriptor.targetId}; falling back to an ephemeral port.",
            )
            server.start(config.copy(port = 0))
        }
    }

    private fun stop(
        port: Int,
        lastError: String? = null,
    ) {
        server.stop()
        heartbeat?.cancel(false)
        heartbeat = null
        activeConfiguration = null
        status = stoppedStatus(port, lastError)
        appService.targetRegistryStore().remove(descriptor.targetId)
        logger.info("IJ-MCP target ${descriptor.targetId} stopped")
    }

    private fun stoppedStatus(
        port: Int,
        lastError: String? = null,
    ) = IjMcpTargetStatus(
        descriptor = descriptor,
        running = false,
        port = port,
        endpointUrl = endpointUrl(port),
        requiresPairing = authManager.requiresPairing(),
        lastError = lastError,
    )

    private fun endpointUrl(port: Int): String = "http://127.0.0.1:$port${IjMcpProtocol.endpointPath}"

    private fun scheduleHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = heartbeatExecutor.scheduleAtFixedRate(
            { publishRegistration() },
            15,
            15,
            TimeUnit.SECONDS,
        )
    }

    private fun publishRegistration() {
        if (!status.running) {
            return
        }

        runCatching {
            appService.targetRegistryStore().upsert(status)
        }.onFailure { exception ->
            logger.warn("Failed to publish IJ-MCP target ${descriptor.targetId} to the local registry", exception)
        }
    }

    internal fun issuePairingCode(): IssuedPairingCode {
        val issuedCode = authManager.issuePairingCode()
        refreshRegistrationState()
        return issuedCode
    }

    internal fun resetAuthentication() {
        secretStore.storeLegacyToken(null)
        authManager.reset()
        refreshRegistrationState()
    }

    private fun refreshRegistrationState() {
        status = status.copy(requiresPairing = authManager.requiresPairing())
        publishRegistration()
    }
}
