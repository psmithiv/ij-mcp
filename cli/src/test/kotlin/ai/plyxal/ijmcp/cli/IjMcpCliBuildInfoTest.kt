package ai.plyxal.ijmcp.cli

import kotlin.test.Test
import kotlin.test.assertFalse

class IjMcpCliBuildInfoTest {
    @Test
    fun loadsCliVersionFromBundledResource() {
        assertFalse(IjMcpCliBuildInfo.cliVersion.isBlank())
    }
}
