package ai.plyxal.ijmcp.app

import java.util.Properties

internal object IjMcpPluginBuildInfo {
    private val properties = Properties().apply {
        IjMcpPluginBuildInfo::class.java.getResourceAsStream("/ijmcp-plugin.properties")?.use(::load)
    }

    val pluginVersion: String = properties.getProperty("version", "development")
    val sinceBuild: String = properties.getProperty("sinceBuild", "")
    val untilBuild: String = properties.getProperty("untilBuild", "")
}
