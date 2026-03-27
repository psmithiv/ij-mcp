package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.model.IjMcpTargetRegistration
import ai.plyxal.ijmcp.model.IjMcpTargetRegistrySnapshot
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class IjMcpTargetRegistryStore(
    private val registryRoot: Path = defaultRegistryRoot(),
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofSeconds(45),
) {
    private val logger = thisLogger()
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val registryFile = registryRoot.resolve("targets.json")
    private val lockFile = registryRoot.resolve("targets.lock")

    fun upsert(status: IjMcpTargetStatus, requiresPairing: Boolean) {
        withRegistryLock {
            val now = Instant.now(clock)
            val snapshot = readSnapshotRecovering()
            val nextTargets = cleanup(snapshot.targets, now)
                .filterNot { it.targetId == status.descriptor.targetId }
                .plus(status.toRegistration(requiresPairing, now))
                .sortedBy { it.targetId }

            writeSnapshot(IjMcpTargetRegistrySnapshot(targets = nextTargets))
        }
    }

    fun remove(targetId: String) {
        withRegistryLock {
            val now = Instant.now(clock)
            val snapshot = readSnapshotRecovering()
            val nextTargets = cleanup(snapshot.targets, now)
                .filterNot { it.targetId == targetId }

            writeSnapshot(IjMcpTargetRegistrySnapshot(targets = nextTargets))
        }
    }

    fun readTargets(): List<IjMcpTargetRegistration> = withRegistryLock {
        val now = Instant.now(clock)
        val snapshot = readSnapshotRecovering()
        val cleanedTargets = cleanup(snapshot.targets, now)

        if (cleanedTargets.size != snapshot.targets.size) {
            writeSnapshot(IjMcpTargetRegistrySnapshot(targets = cleanedTargets))
        }

        cleanedTargets
    }

    private fun cleanup(
        targets: List<IjMcpTargetRegistration>,
        now: Instant,
    ): List<IjMcpTargetRegistration> = targets.filter { registration ->
        registration.endpointUrl.isNotBlank() &&
            registration.projectPath.isNotBlank() &&
            registration.protocolVersion == IjMcpProtocol.protocolVersion &&
            parseLastSeen(registration.lastSeenAt)?.plus(staleAfter)?.isAfter(now) == true
    }

    private fun parseLastSeen(value: String): Instant? = runCatching { Instant.parse(value) }.getOrNull()

    private fun readSnapshotRecovering(): IjMcpTargetRegistrySnapshot {
        if (!Files.exists(registryFile)) {
            return IjMcpTargetRegistrySnapshot()
        }

        val rawContent = Files.readString(registryFile)
        if (rawContent.isBlank()) {
            return IjMcpTargetRegistrySnapshot()
        }

        return runCatching {
            json.decodeFromString<IjMcpTargetRegistrySnapshot>(rawContent)
        }.getOrElse { exception ->
            val backupFile = registryRoot.resolve("targets.corrupt-${Instant.now(clock).toEpochMilli()}.json")
            logger.warn("Recovering from a corrupt IJ-MCP target registry at $registryFile", exception)
            Files.move(registryFile, backupFile, StandardCopyOption.REPLACE_EXISTING)
            IjMcpTargetRegistrySnapshot()
        }
    }

    private fun writeSnapshot(snapshot: IjMcpTargetRegistrySnapshot) {
        ensureRegistryRoot()
        val temporaryFile = Files.createTempFile(registryRoot, "targets", ".json.tmp")
        Files.writeString(
            temporaryFile,
            json.encodeToString(IjMcpTargetRegistrySnapshot.serializer(), snapshot),
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        try {
            Files.move(
                temporaryFile,
                registryFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                registryFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun <T> withRegistryLock(action: () -> T): T {
        ensureRegistryRoot()
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                return action()
            }
        }
    }

    private fun ensureRegistryRoot() {
        Files.createDirectories(registryRoot)
    }

    companion object {
        private fun defaultRegistryRoot(): Path = Path.of(System.getProperty("user.home"), ".ij-mcp")
    }
}

private fun IjMcpTargetStatus.toRegistration(
    requiresPairing: Boolean,
    now: Instant,
): IjMcpTargetRegistration = IjMcpTargetRegistration(
    targetId = descriptor.targetId,
    ideInstanceId = descriptor.ideInstanceId,
    pid = descriptor.pid,
    productCode = descriptor.productCode,
    productName = descriptor.productName,
    projectName = descriptor.projectName,
    projectPath = descriptor.projectPath,
    endpointUrl = endpointUrl,
    port = port,
    protocolVersion = IjMcpProtocol.protocolVersion,
    requiresPairing = requiresPairing,
    lastSeenAt = now.toString(),
)
