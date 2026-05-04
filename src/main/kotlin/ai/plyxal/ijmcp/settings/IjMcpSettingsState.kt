package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.mcp.IjMcpProtocol

data class IjMcpSettingsState(
    var enabled: Boolean = true,
    var port: Int = IjMcpProtocol.defaultPort,
    var autoTrustLocalClients: Boolean = true,
    var manageCodexConfig: Boolean = true,
)
