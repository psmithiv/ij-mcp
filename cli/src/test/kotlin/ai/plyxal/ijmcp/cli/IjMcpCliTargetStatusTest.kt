package ai.plyxal.ijmcp.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class IjMcpCliTargetStatusTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun targetsCurrentReportsRecoveryGuidanceWhenNothingIsSelected() {
        val directory = Files.createTempDirectory("ijmcp-cli-current-unselected")
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        val cli = IjMcpCli(
            stateStore = IjMcpCliStateStore(directory.resolve("client-state.json")),
            registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
            stdout = PrintStream(stdoutBuffer),
            stderr = PrintStream(stderrBuffer),
        )

        val exitCode = cli.run(listOf("targets", "current"))
        val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)

        assertEquals(1, exitCode)
        assertContains(stdoutText, "routeStatus=unselected")
        assertContains(stdoutText, "recoveryCode=no_selection")
        assertContains(stdoutText, "recoveryAction=Run `targets list` and `targets select <targetId>`.")
    }

    @Test
    fun targetsCurrentReportsHealthySelectedTargetState() {
        val directory = Files.createTempDirectory("ijmcp-cli-current-selected")
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()

        withHealthServer(running = true, requiresPairing = false) { port ->
            writeRegistry(
                directory.resolve("targets.json"),
                listOf(targetRegistration("target-a", port, requiresPairing = false)),
            )
            IjMcpCliStateStore(directory.resolve("client-state.json")).save(
                IjMcpClientState(
                    selectedTargetId = "target-a",
                    credentialsByTargetId = mapOf("target-a" to "token-a"),
                ),
            )

            val cli = IjMcpCli(
                stateStore = IjMcpCliStateStore(directory.resolve("client-state.json")),
                registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
                stdout = PrintStream(stdoutBuffer),
                stderr = PrintStream(stderrBuffer),
            )

            val exitCode = cli.run(listOf("targets", "current"))
            val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)

            assertEquals(0, exitCode)
            assertContains(stdoutText, "routeStatus=selected")
            assertContains(stdoutText, "selectedTargetId=target-a")
            assertContains(stdoutText, "paired=true")
            assertContains(stdoutText, "running=true")
            assertContains(stdoutText, "requiresPairing=false")
        }
    }

    @Test
    fun directMcpCommandPrintsRecoveryGuidanceForStaleSelection() {
        val directory = Files.createTempDirectory("ijmcp-cli-stale-selection")
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()

        IjMcpCliStateStore(directory.resolve("client-state.json")).save(
            IjMcpClientState(
                selectedTargetId = "target-missing",
            ),
        )
        Files.writeString(
            directory.resolve("targets.json"),
            json.encodeToString(
                IjMcpTargetRegistrySnapshot.serializer(),
                IjMcpTargetRegistrySnapshot(targets = emptyList()),
            ),
        )

        val cli = IjMcpCli(
            stateStore = IjMcpCliStateStore(directory.resolve("client-state.json")),
            registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
            stdout = PrintStream(stdoutBuffer),
            stderr = PrintStream(stderrBuffer),
        )

        val exitCode = cli.run(listOf("mcp", "tools-list"))
        val stderrText = stderrBuffer.toString(StandardCharsets.UTF_8)

        assertEquals(1, exitCode)
        assertContains(stderrText, "Selected target target-missing is unavailable.")
        assertContains(stderrText, "recoveryCode=stale_target")
        assertContains(stderrText, "recoveryAction=Run `targets list` and `targets select <targetId>`.")
    }

    private fun withHealthServer(
        running: Boolean,
        requiresPairing: Boolean,
        block: (port: Int) -> Unit,
    ) {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )
        server.createContext("/health") { exchange ->
            try {
                writeJsonResponse(
                    exchange,
                    """{"protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","requiresPairing":$requiresPairing,"running":$running,"targetId":"target-a","projectName":"ij-mcp","projectPath":"/tmp/ij-mcp","endpointUrl":"http://127.0.0.1:${server.address.port}/mcp","port":${server.address.port}}""",
                )
            } finally {
                exchange.close()
            }
        }
        server.start()

        try {
            block(server.address.port)
        } finally {
            server.stop(0)
        }
    }

    private fun writeRegistry(
        registryFile: java.nio.file.Path,
        registrations: List<IjMcpTargetRegistration>,
    ) {
        Files.writeString(
            registryFile,
            json.encodeToString(IjMcpTargetRegistrySnapshot.serializer(), IjMcpTargetRegistrySnapshot(targets = registrations)),
        )
    }

    private fun targetRegistration(
        targetId: String,
        port: Int,
        requiresPairing: Boolean,
    ): IjMcpTargetRegistration = IjMcpTargetRegistration(
        targetId = targetId,
        ideInstanceId = "ide-$targetId",
        pid = 4321,
        productCode = "IC",
        productName = "IntelliJ IDEA Community Edition",
        projectName = "ij-mcp",
        projectPath = "/tmp/ij-mcp",
        endpointUrl = "http://127.0.0.1:$port/mcp",
        port = port,
        protocolVersion = IJ_MCP_PROTOCOL_VERSION,
        requiresPairing = requiresPairing,
        lastSeenAt = "2026-04-06T18:00:00Z",
    )

    private fun writeJsonResponse(
        exchange: HttpExchange,
        responseBody: String,
    ) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(responseBytes)
        }
    }
}
