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

class IjMcpCliPairingTest {
    private val json = Json { prettyPrint = true }

    @Test
    fun pairOverwritesAnExistingStoredCredentialWithTheNewToken() {
        val directory = Files.createTempDirectory("ijmcp-cli-pairing")
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()

        withPairServer { port ->
            Files.writeString(
                directory.resolve("targets.json"),
                json.encodeToString(
                    IjMcpTargetRegistrySnapshot.serializer(),
                    IjMcpTargetRegistrySnapshot(
                        targets = listOf(
                            IjMcpTargetRegistration(
                                targetId = "target-a",
                                ideInstanceId = "ide-a",
                                pid = 1234,
                                productCode = "IC",
                                productName = "IntelliJ IDEA Community Edition",
                                projectName = "ij-mcp",
                                projectPath = "/tmp/ij-mcp",
                                endpointUrl = "http://127.0.0.1:$port/mcp",
                                port = port,
                                protocolVersion = IJ_MCP_PROTOCOL_VERSION,
                                requiresPairing = true,
                                lastSeenAt = "2026-04-06T18:00:00Z",
                            ),
                        ),
                    ),
                ),
            )

            val stateStore = IjMcpCliStateStore(directory.resolve("client-state.json"))
            stateStore.save(
                IjMcpClientState(
                    selectedTargetId = "target-a",
                    credentialsByTargetId = mapOf("target-a" to "old-token"),
                ),
            )

            val cli = IjMcpCli(
                stateStore = stateStore,
                registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
                stdout = PrintStream(stdoutBuffer),
                stderr = PrintStream(stderrBuffer),
            )

            val exitCode = cli.run(listOf("targets", "pair", "--code", "PAIR1234"))
            val nextState = stateStore.load()
            val stdoutText = stdoutBuffer.toString(StandardCharsets.UTF_8)

            assertEquals(0, exitCode)
            assertEquals("rotated-token", nextState.credentialsByTargetId["target-a"])
            assertContains(stdoutText, "Paired target target-a")
        }
    }

    private fun withPairServer(block: (port: Int) -> Unit) {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )
        server.createContext("/pair") { exchange ->
            try {
                writeJsonResponse(
                    exchange,
                    """{"status":"success","bearerToken":"rotated-token","protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","requiresPairing":false,"targetId":"target-a","endpointUrl":"http://127.0.0.1:${server.address.port}/mcp","projectName":"ij-mcp"}""",
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
