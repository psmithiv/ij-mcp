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

class IjMcpCliGatewayRoutingTest {
    private val client = HttpClient.newHttpClient()
    private val json = Json { prettyPrint = true }

    @Test
    fun gatewayRoutesThroughTheStickySelectedTargetOnly() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-routing")
        val targetARequests = mutableListOf<String>()
        val targetBRequests = mutableListOf<String>()

        withFakeTargetServer("target-a", "token-a", targetARequests) { targetAPort ->
            withFakeTargetServer("target-b", "token-b", targetBRequests) { targetBPort ->
                val stateStore = IjMcpCliStateStore(directory.resolve("client-state.json"))
                stateStore.save(
                    IjMcpClientState(
                        selectedTargetId = "target-b",
                        credentialsByTargetId = mapOf(
                            "target-a" to "token-a",
                            "target-b" to "token-b",
                        ),
                        gatewayBearerToken = "gateway-token",
                    ),
                )
                writeRegistry(
                    directory.resolve("targets.json"),
                    listOf(
                        targetRegistration("target-a", "Project A", targetAPort),
                        targetRegistration("target-b", "Project B", targetBPort),
                    ),
                )

                val resolver = IjMcpSelectedTargetResolver(
                    stateStore = stateStore,
                    registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
                    httpClient = IjMcpCliHttpClient(),
                )

                IjMcpCliGatewayServer(
                    config = IjMcpGatewayConfig(port = 0, bearerToken = "gateway-token"),
                    preflight = IjMcpGatewayPreflight(resolver),
                    routeSummaryProvider = { resolver.describeStickyRoute() },
                ).use { gatewayServer ->
                    val gatewayPort = gatewayServer.start(IjMcpGatewayServerConfig(port = 0))
                    val response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                            .header("Authorization", "Bearer gateway-token")
                            .header("Content-Type", "application/json")
                            .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                            .POST(
                                HttpRequest.BodyPublishers.ofString(
                                    """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""",
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

                    assertEquals(200, response.statusCode())
                    assertContains(response.body(), "\"tools\"")
                    assertEquals(emptyList(), targetARequests.toList())
                    assertEquals(listOf("health", "initialize", "tools/list"), targetBRequests.toList())
                    assertEquals(200, healthResponse.statusCode())
                    assertContains(healthResponse.body(), "\"selectedTargetId\":\"target-b\"")
                    assertContains(healthResponse.body(), "\"selectedProjectName\":\"Project B\"")
                    assertContains(healthResponse.body(), "\"routeStatus\":\"selected\"")
                }
            }
        }
    }

    @Test
    fun gatewayDoesNotFallbackWhenAnotherTargetIsAvailable() {
        val directory = Files.createTempDirectory("ijmcp-cli-gateway-no-fallback")
        val targetBRequests = mutableListOf<String>()

        withFakeTargetServer("target-b", "token-b", targetBRequests) { targetBPort ->
            val stateStore = IjMcpCliStateStore(directory.resolve("client-state.json"))
            stateStore.save(
                IjMcpClientState(
                    selectedTargetId = "target-missing",
                    credentialsByTargetId = mapOf(
                        "target-b" to "token-b",
                    ),
                    gatewayBearerToken = "gateway-token",
                ),
            )
            writeRegistry(
                directory.resolve("targets.json"),
                listOf(
                    targetRegistration("target-b", "Project B", targetBPort),
                ),
            )

            val resolver = IjMcpSelectedTargetResolver(
                stateStore = stateStore,
                registryReader = IjMcpTargetRegistryReader(directory.resolve("targets.json")),
                httpClient = IjMcpCliHttpClient(),
            )

            IjMcpCliGatewayServer(
                config = IjMcpGatewayConfig(port = 0, bearerToken = "gateway-token"),
                preflight = IjMcpGatewayPreflight(resolver),
                routeSummaryProvider = { resolver.describeStickyRoute() },
            ).use { gatewayServer ->
                val gatewayPort = gatewayServer.start(IjMcpGatewayServerConfig(port = 0))
                val response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$gatewayPort/mcp"))
                        .header("Authorization", "Bearer gateway-token")
                        .header("Content-Type", "application/json")
                        .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                        .POST(
                            HttpRequest.BodyPublishers.ofString(
                                """{"jsonrpc":"2.0","id":9,"method":"tools/list","params":{}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

                assertEquals(200, response.statusCode())
                assertContains(response.body(), "\"message\":\"Selected target target-missing is unavailable.\"")
                assertContains(response.body(), "\"recoveryCode\":\"stale_target\"")
                assertContains(response.body(), "\"recoveryAction\":\"Run `targets list` and `targets select <targetId>`.\"")
                assertEquals(emptyList(), targetBRequests.toList())
            }
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
        projectName: String,
        port: Int,
    ): IjMcpTargetRegistration = IjMcpTargetRegistration(
        targetId = targetId,
        ideInstanceId = "ide-$targetId",
        pid = 4321,
        productCode = "IC",
        productName = "IntelliJ IDEA Community Edition",
        projectName = projectName,
        projectPath = "/tmp/$targetId",
        endpointUrl = "http://127.0.0.1:$port/mcp",
        port = port,
        protocolVersion = IJ_MCP_PROTOCOL_VERSION,
        requiresPairing = false,
        lastSeenAt = "2026-04-06T12:00:00Z",
    )

    private fun withFakeTargetServer(
        targetId: String,
        expectedBearerToken: String,
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
                    """{"protocolVersion":"$IJ_MCP_PROTOCOL_VERSION","requiresPairing":false,"running":true,"targetId":"$targetId","projectName":"$targetId","projectPath":"/tmp/$targetId","endpointUrl":"http://127.0.0.1:${server.address.port}/mcp","port":${server.address.port}}""",
                )
            } finally {
                exchange.close()
            }
        }
        server.createContext("/mcp") { exchange ->
            try {
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $expectedBearerToken") {
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
                        """{"jsonrpc":"2.0","id":7,"result":{"tools":[{"name":"open_file","title":"Open file"}]}}"""
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
