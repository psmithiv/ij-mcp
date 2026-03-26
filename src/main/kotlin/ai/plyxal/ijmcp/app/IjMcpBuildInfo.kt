package ai.plyxal.ijmcp.app

internal object IjMcpBuildInfo {
    val pluginVersion: String = IjMcpBuildInfo::class.java.`package`?.implementationVersion ?: "development"
}
