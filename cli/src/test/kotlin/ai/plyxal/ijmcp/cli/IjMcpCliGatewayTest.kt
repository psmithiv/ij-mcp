package ai.plyxal.ijmcp.cli

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IjMcpCliGatewayTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun gatewayConfigCreatesStableLocalEndpointAndToken() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-config")
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        val cli = IjMcpCli(
            stateStore = IjMcpCliStateStore(directory.resolve("client-state.json")),
            registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
            stdout = PrintStream(stdoutBuffer),
            stderr = PrintStream(stderrBuffer),
        )

        val exitCode = cli.run(listOf("gateway", "config"))

        val state = IjMcpCliStateStore(directory.resolve("client-state.json")).load()
        val stdoutText = String(stdoutBuffer.toByteArray(), StandardCharsets.UTF_8)

        assertEquals(0, exitCode)
        assertEquals(IJ_MCP_GATEWAY_DEFAULT_PORT, state.gatewayPort)
        assertFalse(state.gatewayBearerToken.isNullOrBlank())
        assertContains(stdoutText, "endpointUrl=http://127.0.0.1:$IJ_MCP_GATEWAY_DEFAULT_PORT/mcp")
        assertContains(stdoutText, "healthUrl=http://127.0.0.1:$IJ_MCP_GATEWAY_DEFAULT_PORT/health")
        assertContains(stdoutText, "gatewayBearerToken=")
    }

    @Test
    fun gatewayProxiesInitializeAndToolsListThroughStableLoopbackEndpoint() {
        val observedMethods = Collections.synchronizedList(mutableListOf<String>())

        withFakeTargetServer(observedMethods) { targetPort ->
            IjMcpCliGatewayServer(
                config = IjMcpGatewayConfig(port = 0, bearerToken = "gateway-token"),
                targetResolver = {
                    Result.success(
                        IjMcpResolvedTarget(
                            registration = IjMcpTargetRegistration(
                                targetId = "target-a",
                                ideInstanceId = "ide-a",
                                pid = 1234,
                                productCode = "IC",
                                productName = "IntelliJ IDEA Community Edition",
                                projectName = "ij-mcp",
                                projectPath = "/tmp/ij-mcp",
                                endpointUrl = "http://127.0.0.1:$targetPort/mcp",
                                port = targetPort,
                                protocolVersion = IJ_MCP_PROTOCOL_VERSION,
                                requiresPairing = false,
                                lastSeenAt = "2026-04-06T12:00:00Z",
                            ),
                            health = IjMcpHealthResponse(
                                protocolVersion = IJ_MCP_PROTOCOL_VERSION,
                                requiresPairing = false,
                                running = true,
                                targetId = "target-a",
                                projectName = "ij-mcp",
                                projectPath = "/tmp/ij-mcp",
                                endpointUrl = "http://127.0.0.1:$targetPort/mcp",
                                port = targetPort,
                            ),
                            bearerToken = "target-token",
                        ),
                    )
                },
                stateProvider = {
                    IjMcpClientState(
                        selectedTargetId = "target-a",
                        gatewayPort = 0,
                        gatewayBearerToken = "gateway-token",
                    )
                },
            ).use { gatewayServer ->
                val gatewayPort = gatewayServer.start(IjMcpGatewayServerConfig(port = 0))

                val initializeResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                        .header("Authorization", "Bearer gateway-token")
                        .header("Content-Type", "application/json")
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                val toolsListResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                        .header("Authorization", "Bearer gateway-token")
                        .header("Content-Type", "application/json")
                        .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                val healthResponse = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/health"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, initializeResponse.statusCode())
                assertContains(initializeResponse.body(), "\"protocolVersion\":\"$IJ_MCP_PROTOCOL_VERSION\"")
                assertEquals(200, toolsListResponse.statusCode())
                assertContains(toolsListResponse.body(), "\"name\":\"open_file\"")
                assertEquals(200, healthResponse.statusCode())
                assertContains(healthResponse.body(), "\"selectedTargetId\":\"target-a\"")
                assertContains(healthResponse.body(), "\"endpointUrl\":\"http://127.0.0.1:$gatewayPort/mcp\"")
            }
        }

        assertEquals(listOf("initialize", "tools/list"), observedMethods.toList())
    }

    private fun withFakeTargetServer(
        observedMethods: MutableList<String>,
        block: (port: Int) -> Unit,
    ) {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer target-token") {
                    exchange.sendResponseHeaders(401, -1)
                    return@createContext
                }

                val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val method = when {
                    "\"method\":\"initialize\"" in requestBody -> "initialize"
                    "\"method\":\"tools/list\"" in requestBody -> "tools/list"
                    else -> "unknown"
                }

                observedMethods.add(method)

                val responseBody = when (method) {
                    "initialize" -> {
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","capabilities":{"tools":{}},"serverInfo":{"name":"ij-mcp","version":"test-version"}}}"""
                    }

                    "tools/list" -> {
                        """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"open_file","title":"Open file"}]}}"""
                    }

                    else -> {
                        """{"jsonrpc":"2.0","id":0,"error":{"code":-32601,"message":"Method not found"}}"""
                    }
                }

                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, responseBytes.size.toLong())
                exchange.responseBody.use { output ->
                    output.write(responseBytes)
                }
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
}
