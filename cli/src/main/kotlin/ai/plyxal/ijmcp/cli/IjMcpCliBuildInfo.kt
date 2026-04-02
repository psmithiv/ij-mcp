package ai.plyxal.ijmcp.cli

internal object IjMcpCliBuildInfo {
    val cliVersion: String = loadCliVersion()

    private fun loadCliVersion(): String = IjMcpCliBuildInfo::class.java
        .getResourceAsStream("/ijmcp-cli.properties")
        ?.bufferedReader()
        ?.useLines { lines ->
            lines.map(String::trim)
                .firstOrNull { it.startsWith("version=") }
                ?.substringAfter('=')
                ?.trim()
        }
        ?.takeIf(String::isNotBlank)
        ?: "development"
}
