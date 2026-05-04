package ai.plyxal.ijmcp.settings

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IjMcpCodexConfigManagerTest {
    @Test
    fun ensureManagedHttpServerAppendsIjMcpSection() {
        val tempRoot = Files.createTempDirectory("ijmcp-codex-config")
        val configFile = tempRoot.resolve("config.toml")
        Files.writeString(
            configFile,
            """
            model = "gpt-5.5"

            [mcp_servers.jira]
            url = "https://mcp.atlassian.com/v1/mcp"
            """.trimIndent(),
        )

        val status = IjMcpCodexConfigManager(configFile).ensureManagedHttpServer(
            endpointUrl = "http://127.0.0.1:8765/mcp",
            bearerToken = "target-token",
        )

        val config = Files.readString(configFile)
        assertTrue(status.configured)
        assertEquals(configFile, status.configFile)
        assertTrue(config.contains("[mcp_servers.jira]"))
        assertTrue(config.contains("[mcp_servers.ij-mcp]"))
        assertTrue(config.contains("url = \"http://127.0.0.1:8765/mcp\""))
        assertTrue(config.contains("http_headers = { \"Authorization\" = \"Bearer target-token\" }"))
    }

    @Test
    fun ensureManagedHttpServerReplacesExistingIjMcpSectionOnly() {
        val tempRoot = Files.createTempDirectory("ijmcp-codex-config")
        val configFile = tempRoot.resolve("config.toml")
        Files.writeString(
            configFile,
            """
            model = "gpt-5.5"

            [mcp_servers.ij-mcp]
            url = "http://127.0.0.1:3765/mcp"
            bearer_token_env_var = "IJ_MCP_GATEWAY_TOKEN"

            [mcp_servers.figma]
            url = "https://mcp.figma.com/mcp"
            """.trimIndent(),
        )

        IjMcpCodexConfigManager(configFile).ensureManagedHttpServer(
            endpointUrl = "http://127.0.0.1:8765/mcp",
            bearerToken = "new-token",
        )

        val config = Files.readString(configFile)
        assertTrue(config.contains("model = \"gpt-5.5\""))
        assertTrue(config.contains("[mcp_servers.figma]"))
        assertTrue(config.contains("url = \"https://mcp.figma.com/mcp\""))
        assertTrue(config.contains("url = \"http://127.0.0.1:8765/mcp\""))
        assertTrue(config.contains("Bearer new-token"))
        assertTrue(!config.contains("bearer_token_env_var"))
    }
}
