package ai.plyxal.ijmcp.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class IjMcpCliStateStoreTest {
    @Test
    fun saveAndLoadPersistStickyTargetAndCredentials() {
        val stateFile = Files.createTempDirectory("ijmcp-cli-state-test").resolve("client-state.json")
        val store = IjMcpCliStateStore(stateFile)

        store.save(
            IjMcpClientState(
                selectedTargetId = "target-a",
                credentialsByTargetId = mapOf(
                    "target-a" to "token-a",
                    "target-b" to "token-b",
                ),
            ),
        )

        val state = store.load()

        assertEquals("target-a", state.selectedTargetId)
        assertEquals("token-a", state.credentialsByTargetId["target-a"])
        assertEquals("token-b", state.credentialsByTargetId["target-b"])
    }
}
