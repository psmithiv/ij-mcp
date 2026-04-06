package ai.plyxal.ijmcp.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class IjMcpGatewayServerConfig(
    val port: Int = IJ_MCP_GATEWAY_DEFAULT_PORT,
)

internal class IjMcpCliGatewayServer(
    private val config: IjMcpGatewayConfig,
    private val targetResolver: () -> Result<IjMcpResolvedTarget>,
    private val stateProvider: () -> IjMcpClientState,
    private val httpClient: IjMcpCliHttpClient = IjMcpCliHttpClient(),
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : AutoCloseable {
    private val json = Json { prettyPrint = false }
    private var server: HttpServer? = null

    val boundPort: Int?
        get() = server?.address?.port

    @Synchronized
    fun start(serverConfig: IjMcpGatewayServerConfig = IjMcpGatewayServerConfig(config.port)): Int {
        if (server != null) {
            return requireNotNull(boundPort)
        }

        val httpServer = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverConfig.port),
            0,
        )
        httpServer.createContext("/mcp") { exchange ->
            handleMcp(exchange)
        }
        httpServer.createContext("/health") { exchange ->
            handleHealth(exchange)
        }
        httpServer.createContext("/api/health") { exchange ->
            handleHealth(exchange)
        }
        httpServer.executor = executor
        httpServer.start()
        server = httpServer
        return httpServer.address.port
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    override fun close() {
        stop()
        executor.shutdownNow()
    }

    private fun handleMcp(exchange: HttpExchange) {
        try {
            if (!isAuthorized(exchange.requestHeaders.getFirst("Authorization"))) {
                writeResponse(
                    exchange,
                    IjMcpHttpExchangeResult(
                        statusCode = 401,
                        body = "Unauthorized",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (exchange.requestMethod != "POST") {
                writeResponse(
                    exchange,
                    IjMcpHttpExchangeResult(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val target = targetResolver().getOrElse { exception ->
                writeResponse(
                    exchange,
                    jsonRpcErrorResponse(
                        requestBody = requestBody,
                        message = exception.message ?: "Gateway target resolution failed.",
                    ),
                )
                return
            }

            val upstreamResponse = httpClient.forwardJsonRpc(
                target = target,
                requestBody = requestBody,
                protocolVersionHeader = exchange.requestHeaders.getFirst("MCP-Protocol-Version"),
            ).getOrElse { exception ->
                writeResponse(
                    exchange,
                    jsonRpcErrorResponse(
                        requestBody = requestBody,
                        message = exception.message ?: "Gateway proxy request failed.",
                    ),
                )
                return
            }

            writeResponse(exchange, upstreamResponse)
        } finally {
            exchange.close()
        }
    }

    private fun handleHealth(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                writeResponse(
                    exchange,
                    IjMcpHttpExchangeResult(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val currentState = stateProvider()
            val port = requireNotNull(boundPort)
            writeResponse(
                exchange,
                IjMcpHttpExchangeResult(
                    statusCode = 200,
                    body = json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("status", "ok")
                            put("running", true)
                            put("protocolVersion", IJ_MCP_PROTOCOL_VERSION)
                            put("gatewayVersion", IjMcpCliBuildInfo.cliVersion)
                            put("endpointUrl", "http://127.0.0.1:$port/mcp")
                            put("healthUrl", "http://127.0.0.1:$port/health")
                            currentState.selectedTargetId?.let { put("selectedTargetId", it) }
                            put("requiresAuth", true)
                        },
                    ),
                ),
            )
        } finally {
            exchange.close()
        }
    }

    private fun jsonRpcErrorResponse(
        requestBody: String,
        message: String,
    ): IjMcpHttpExchangeResult {
        val requestId = parseRequestId(requestBody)
        return IjMcpHttpExchangeResult(
            statusCode = 200,
            body = json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32000)
                            put("message", message)
                        },
                    )
                },
            ),
            contentType = "application/json; charset=utf-8",
        )
    }

    private fun parseRequestId(requestBody: String): JsonElement = runCatching {
        json.parseToJsonElement(requestBody).jsonObject["id"] ?: JsonNull
    }.getOrDefault(JsonNull)

    private fun isAuthorized(authorizationHeader: String?): Boolean {
        val token = authorizationHeader
            ?.removePrefix("Bearer ")
            ?.takeIf { it != authorizationHeader }
        return token == config.bearerToken
    }

    private fun writeResponse(
        exchange: HttpExchange,
        response: IjMcpHttpExchangeResult,
    ) {
        response.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
        val responseBytes = response.body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        exchange.sendResponseHeaders(response.statusCode, responseBytes.size.toLong())
        exchange.responseBody.use { output ->
            output.write(responseBytes)
        }
    }
}
