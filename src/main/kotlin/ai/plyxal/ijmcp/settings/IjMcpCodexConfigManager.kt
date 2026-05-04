package ai.plyxal.ijmcp.settings

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

private const val IJ_MCP_CODEX_SERVER_NAME = "ij-mcp"

internal data class IjMcpCodexConfigStatus(
    val configured: Boolean,
    val configFile: Path,
    val message: String,
)

internal class IjMcpCodexConfigManager(
    private val configFile: Path = defaultConfigFile(),
) {
    fun ensureManagedHttpServer(
        endpointUrl: String,
        bearerToken: String,
    ): IjMcpCodexConfigStatus {
        if (endpointUrl.isBlank() || bearerToken.isBlank()) {
            return IjMcpCodexConfigStatus(
                configured = false,
                configFile = configFile,
                message = "Codex config was not changed because the endpoint or token is missing.",
            )
        }

        val existingConfig = if (Files.exists(configFile)) Files.readString(configFile) else ""
        val nextConfig = replaceManagedServerSection(
            content = existingConfig,
            managedSection = managedHttpServerSection(endpointUrl, bearerToken),
        )

        if (nextConfig != existingConfig) {
            writeAtomically(nextConfig)
        }

        return IjMcpCodexConfigStatus(
            configured = true,
            configFile = configFile,
            message = "Codex is configured for IJ-MCP at $endpointUrl.",
        )
    }

    fun configFile(): Path = configFile

    private fun replaceManagedServerSection(
        content: String,
        managedSection: String,
    ): String {
        val normalized = content.replace("\r\n", "\n").trimEnd()
        val lines = if (normalized.isBlank()) emptyList() else normalized.split("\n")
        val sectionHeader = "[mcp_servers.$IJ_MCP_CODEX_SERVER_NAME]"
        val sectionStart = lines.indexOfFirst { it.trim() == sectionHeader }

        if (sectionStart == -1) {
            return if (normalized.isBlank()) {
                "$managedSection\n"
            } else {
                "$normalized\n\n$managedSection\n"
            }
        }

        var removeStart = sectionStart
        while (removeStart > 0 && lines[removeStart - 1].contains("Managed by IJ-MCP")) {
            removeStart--
        }

        var removeEnd = sectionStart + 1
        while (removeEnd < lines.size && !lines[removeEnd].trimStart().startsWith("[")) {
            removeEnd++
        }

        val nextLines = buildList {
            addAll(lines.take(removeStart))
            if (isNotEmpty() && last().isNotBlank()) {
                add("")
            }
            addAll(managedSection.split("\n"))
            val trailingLines = lines.drop(removeEnd).dropWhile { it.isBlank() }
            if (trailingLines.isNotEmpty()) {
                add("")
                addAll(trailingLines)
            }
        }

        return nextLines.joinToString("\n").trimEnd() + "\n"
    }

    private fun managedHttpServerSection(
        endpointUrl: String,
        bearerToken: String,
    ): String = buildString {
        appendLine("# Managed by IJ-MCP. The token is scoped to this local IntelliJ project window.")
        appendLine("[mcp_servers.$IJ_MCP_CODEX_SERVER_NAME]")
        appendLine("url = \"${tomlString(endpointUrl)}\"")
        append("http_headers = { \"Authorization\" = \"Bearer ${tomlString(bearerToken)}\" }")
    }

    private fun writeAtomically(content: String) {
        Files.createDirectories(configFile.parent)
        val temporaryFile = Files.createTempFile(configFile.parent, "config", ".toml.tmp")
        Files.writeString(
            temporaryFile,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        try {
            Files.move(
                temporaryFile,
                configFile,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporaryFile,
                configFile,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun tomlString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private companion object {
        fun defaultConfigFile(): Path = Path.of(System.getProperty("user.home"), ".codex", "config.toml")
    }
}
