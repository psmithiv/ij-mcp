package ai.plyxal.ijmcp.app

import org.junit.Assert.assertFalse
import org.junit.Test

class IjMcpPluginBuildInfoTest {
    @Test
    fun loadsBundledPluginBuildMetadata() {
        assertFalse(IjMcpPluginBuildInfo.pluginVersion.isBlank())
        assertFalse(IjMcpPluginBuildInfo.sinceBuild.isBlank())
        assertFalse(IjMcpPluginBuildInfo.untilBuild.isBlank())
    }
}
