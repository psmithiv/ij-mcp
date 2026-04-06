package ai.plyxal.ijmcp.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IjMcpGatewayPreflightTest {
    private val client = HttpClient.newHttpClient()
    private val json = Json { prettyPrint = true }

    @Test
    fun gatewayRejectsRequestsWithoutGatewayAuthBeforeToolTraffic() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-auth")
        val observedRequests = mutableListOf<String>()

        withFakeTargetServer(observedRequests) { targetPort ->
            withGatewayServer(
                directory = directory,
                targetPort = targetPort,
                credentialsByTargetId = mapOf("target-a" to "target-token"),
            ) { gatewayPort ->
                val response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                        .header("Content-Type", "application/json")
                        .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(401, response.statusCode())
                assertEquals(emptyList(), observedRequests.toList())
            }
        }
    }

    @Test
    fun gatewayFailsClearlyWhenSelectedTargetCredentialIsMissing() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-missing-credential")
        val observedRequests = mutableListOf<String>()

        withFakeTargetServer(observedRequests) { targetPort ->
            withGatewayServer(
                directory = directory,
                targetPort = targetPort,
                credentialsByTargetId = emptyMap(),
            ) { gatewayPort ->
                val response = client.send(
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

                assertEquals(200, response.statusCode())
                assertContains(response.body(), "\"message\":\"No stored credential exists for target target-a.")
                assertEquals(emptyList(), observedRequests.toList())
            }
        }
    }

    @Test
    fun gatewayFailsClearlyWhenSelectedTargetCredentialIsRejected() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-invalid-credential")
        val observedRequests = mutableListOf<String>()

        withFakeTargetServer(observedRequests) { targetPort ->
            withGatewayServer(
                directory = directory,
                targetPort = targetPort,
                credentialsByTargetId = mapOf("target-a" to "wrong-token"),
            ) { gatewayPort ->
                val response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                        .header("Authorization", "Bearer gateway-token")
                        .header("Content-Type", "application/json")
                        .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"jsonrpc":"2.0","id":5,"method":"tools/list","params":{}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertContains(response.body(), "\"message\":\"Initialization against target target-a failed: MCP request failed with HTTP 401.")
                assertEquals(listOf("health"), observedRequests.toList())
            }
        }
    }

    @Test
    fun gatewayInitializesOnceAcrossRepeatedToolRequests() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-init-once")
        val observedRequests = mutableListOf<String>()

        withFakeTargetServer(observedRequests) { targetPort ->
            withGatewayServer(
                directory = directory,
                targetPort = targetPort,
                credentialsByTargetId = mapOf("target-a" to "target-token"),
            ) { gatewayPort ->
                repeat(2) {
                    val response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                            .header("Authorization", "Bearer gateway-token")
                            .header("Content-Type", "application/json")
                            .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    """{"jsonrpc":"2.0","id":${it + 3},"method":"tools/list","params":{}}""",
                                ),
                            )
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )

                    assertEquals(200, response.statusCode())
                    assertContains(response.body(), "\"tools\"")
                }

                assertEquals(listOf("health", "initialize", "tools/list", "health", "tools/list"), observedRequests.toList())
            }
        }
    }

    private fun withGatewayServer(
        directory: java.nio.file.Path,
        targetPort: Int,
        credentialsByTargetId: Map<String, String>,
        block: (gatewayPort: Int) -> Unit,
    ) {
        val stateStore = IjMcpCliStateStore(directory.resolve("client-state.json"))
        stateStore.save(
            IjMcpClientState(
                selectedTargetId = "target-a",
                credentialsByTargetId = credentialsByTargetId,
                gatewayBearerToken = "gateway-token",
            ),
        )
        Files.writeString(
            directory.resolve("targets.json"),
            json.encodeToString(
                IjMcpTargetRegistrySnapshot.serializer(),
                IjMcpTargetRegistrySnapshot(
                    targets = listOf(
                        IjMcpTargetRegistration(
                            targetId = "target-a",
                            ideInstanceId = "ide-a",
                            pid = 1001,
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
                    ),
                ),
            ),
        )

        val resolver = IjMcpSelectedTargetResolver(
            stateStore = stateStore,
            registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
            httpClient = IjMcpCliHttpClient(),
        )

        val server = IjMcpCliGatewayServer(
            config = IjMcpGatewayConfig(port = 0, bearerToken = "gateway-token"),
            preflight = IjMcpGatewayPreflight(resolver),
            routeSummaryProvider = { resolver.describeStickyRoute() },
        )

        server.use { gatewayServer ->
            val gatewayPort = gatewayServer.start(IjMcpGatewayServerConfig(port = 0))
            block(gatewayPort)
        }
    }

    private fun withFakeTargetServer(
        observedRequests: MutableList<String>,
        block: (port: Int) -> Unit,
    ) {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )
        server.createContext("/health") { exchange ->
            try {
                observedRequests.add("health")
                writeJsonResponse(
                    exchange,
                    """{"protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","requiresPairing":false,"running":true,"targetId":"target-a","projectName":"ij-mcp","projectPath":"/tmp/ij-mcp","endpointUrl":"http://127.0.0.1:${server.address.port}/mcp","port":${server.address.port}}""",
                )
            } finally {
                exchange.close()
            }
        }
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer target-token") {
                    exchange.sendResponseHeaders(401, -1)
                    return@createContext
                }

                val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val method = runCatching {
                    json.parseToJsonElement(requestBody).jsonObject["method"]?.jsonPrimitive?.content
                }.getOrNull() ?: "unknown"
                observedRequests.add(method)

                val responseBody = when (method) {
                    "initialize" -> {
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","capabilities":{"tools":{}},"serverInfo":{"name":"ij-mcp","version":"test-version"}}}"""
                    }

                    "tools/list" -> {
                        """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"open_file","title":"Open file"}]}}"""
                    }

                    else -> {
                        """{"jsonrpc":"2.0","id":0,"error":{"code":-32601,"message":"Method not found"}}"""
                    }
                }

                writeJsonResponse(exchange, responseBody)
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
