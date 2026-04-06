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
    private val preflight: IjMcpGatewayPreflight,
    private val routeSummaryProvider: () -> IjMcpSelectedTargetRouteSummary,
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
            val requestMethod = parseRequestMethod(requestBody)
            val target = preflight.prepareFor(requestMethod).getOrElse { exception ->
                writeResponse(
                    exchange,
                    jsonRpcErrorResponse(
                        requestBody = requestBody,
                        failure = exception,
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
                        failure = exception,
                    ),
                )
                return
            }

            if (upstreamResponse.statusCode == 401) {
                preflight.clearInitialization()
                writeResponse(
                    exchange,
                    jsonRpcErrorResponse(
                        requestBody = requestBody,
                        failure = IjMcpTargetRouteFailure(
                            recoveryCode = "repair_required",
                            message = "Stored credential for target ${target.registration.targetId} was rejected.",
                            recoveryAction = "Issue a new pairing code in the plugin UI and run `targets pair --code <pairingCode> ${target.registration.targetId}`.",
                            selectedTargetId = target.registration.targetId,
                        ),
                    ),
                )
                return
            }

            if (requestMethod == "initialize" && upstreamResponse.statusCode == 200 && !containsJsonRpcError(upstreamResponse.body)) {
                preflight.markInitialized(target.registration.targetId)
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

            val routeSummary = routeSummaryProvider()
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
                            put("routingMode", "sticky-selected-target")
                            put("routeStatus", routeSummary.routeStatus)
                            put("endpointUrl", "http://127.0.0.1:$port/mcp")
                            put("healthUrl", "http://127.0.0.1:$port/health")
                            routeSummary.selectedTargetId?.let { put("selectedTargetId", it) }
                            routeSummary.projectName?.let { put("selectedProjectName", it) }
                            routeSummary.endpointUrl?.let { put("selectedTargetEndpointUrl", it) }
                            preflight.initializedTargetId()?.let { put("initializedTargetId", it) }
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
        failure: Throwable,
    ): IjMcpHttpExchangeResult {
        val requestId = parseRequestId(requestBody)
        val routeFailure = failure as? IjMcpTargetRouteFailure
        val message = routeFailure?.message ?: failure.message ?: "Gateway request failed."
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
                            routeFailure?.let {
                                put(
                                    "data",
                                    buildJsonObject {
                                        put("recoveryCode", it.recoveryCode)
                                        put("recoveryAction", it.recoveryAction)
                                        it.selectedTargetId?.let { selectedTargetId ->
                                            put("selectedTargetId", selectedTargetId)
                                        }
                                    },
                                )
                            }
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

    private fun parseRequestMethod(requestBody: String): String? = runCatching {
        json.parseToJsonElement(requestBody).jsonObject["method"]?.toString()?.trim('"')
    }.getOrNull()

    private fun containsJsonRpcError(responseBody: String?): Boolean = runCatching {
        responseBody != null && json.parseToJsonElement(responseBody).jsonObject.containsKey("error")
    }.getOrDefault(false)

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
