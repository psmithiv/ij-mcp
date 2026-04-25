package ai.plyxal.ijmcp.mcp

internal object IjMcpProtocol {
    const val endpointPath = "/mcp"
    const val healthPath = "/health"
    const val pairingPath = "/pair"
    const val internalPairingCodePath = "/internal/pairing-code"
    const val jsonRpcVersion = "2.0"
    const val protocolVersion = "2025-11-25"
    const val defaultPort = 8765
    const val defaultBearerToken = "ij-mcp-dev-token"

    const val serverName = "ij-mcp"
    const val serverTitle = "IJ-MCP"
    const val serverDescription = "IntelliJ IDEA MCP bridge for safe IDE navigation and search."
    const val websiteUrl = "https://plyxal.atlassian.net/wiki/spaces/IM/overview"
    const val instructions =
        "Use these tools for local IntelliJ navigation and search only. " +
            "This server does not expose resources, prompts, file editing, arbitrary IDE actions, or run/debug control."
}

internal data class IjMcpServerConfig(
    val port: Int = IjMcpProtocol.defaultPort,
)
