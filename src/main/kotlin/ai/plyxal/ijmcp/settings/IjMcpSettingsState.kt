package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.mcp.IjMcpProtocol

data class IjMcpSettingsState(
    var enabled: Boolean = false,
    var port: Int = IjMcpProtocol.defaultPort,
)
