package ai.plyxal.ijmcp.cli

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json

internal class IjMcpCliStateStore(
    private val stateFile: Path = defaultStateFile(),
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(): IjMcpClientState {
        if (!Files.exists(stateFile)) {
            return IjMcpClientState()
        }

        val rawState = Files.readString(stateFile)
        if (rawState.isBlank()) {
            return IjMcpClientState()
        }

        return runCatching {
            json.decodeFromString<IjMcpClientState>(rawState)
        }.getOrDefault(IjMcpClientState())
    }

    fun save(state: IjMcpClientState) {
        Files.createDirectories(stateFile.parent)
        val temporaryFile = Files.createTempFile(stateFile.parent, "client-state", ".json.tmp")
        Files.writeString(
            temporaryFile,
            json.encodeToString(IjMcpClientState.serializer(), state),
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        try {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                stateFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    fun stateFile(): Path = stateFile

    private companion object {
        fun defaultStateFile(): Path = Path.of(System.getProperty("user.home"), ".ij-mcp", "client-state.json")
    }
}

internal class IjMcpTargetRegistryReader(
    private val registryFile: Path = defaultRegistryFile(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun readTargets(): List<IjMcpTargetRegistration> {
        if (!Files.exists(registryFile)) {
            return emptyList()
        }

        val rawSnapshot = Files.readString(registryFile)
        if (rawSnapshot.isBlank()) {
            return emptyList()
        }

        return runCatching {
            json.decodeFromString<IjMcpTargetRegistrySnapshot>(rawSnapshot)
        }.getOrDefault(IjMcpTargetRegistrySnapshot())
            .targets
            .filter { it.protocolVersion == IJ_MCP_PROTOCOL_VERSION }
            .sortedWith(compareBy({ it.projectName.lowercase() }, { it.targetId }))
    }

    fun registryFile(): Path = registryFile

    private companion object {
        fun defaultRegistryFile(): Path = Path.of(System.getProperty("user.home"), ".ij-mcp", "targets.json")
    }
}
