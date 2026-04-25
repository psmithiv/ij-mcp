package ai.plyxal.ijmcp.mcp

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IjMcpHttpServer(
    private val router: IjMcpRequestRouter,
    private val security: IjMcpServerSecurity = IjMcpStaticTokenSecurity(IjMcpProtocol.defaultBearerToken),
    private val statusProvider: () -> IjMcpTargetStatus? = { null },
    private val onAuthenticationStateChanged: (() -> Unit)? = null,
    private val internalPairingCodeIssuer: (() -> IssuedPairingCode?)? = null,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : AutoCloseable {
    private val json = Json { prettyPrint = false }
    private var server: HttpServer? = null

    val boundPort: Int?
        get() = server?.address?.port

    val isRunning: Boolean
        get() = server != null

    @Synchronized
    fun start(config: IjMcpServerConfig): Int {
        if (server != null) {
            return requireNotNull(boundPort)
        }

        val httpServer = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port),
            0,
        )

        httpServer.createContext(IjMcpProtocol.endpointPath) { exchange ->
            handleMcpExchange(exchange)
        }
        httpServer.createContext(IjMcpProtocol.healthPath) { exchange ->
            handleHealthExchange(exchange)
        }
        httpServer.createContext(IjMcpProtocol.pairingPath) { exchange ->
            handlePairingExchange(exchange)
        }
        httpServer.createContext(IjMcpProtocol.internalPairingCodePath) { exchange ->
            handleInternalPairingCodeExchange(exchange)
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

    private fun handleMcpExchange(exchange: HttpExchange) {
        try {
            if (!security.isAuthorized(exchange.requestHeaders.getFirst("Authorization"))) {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 401,
                        body = "Unauthorized",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (!isAllowedOrigin(exchange.requestHeaders.getFirst("Origin"))) {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 403,
                        body = "Forbidden",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (exchange.requestMethod != "POST") {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val response = router.handlePost(
                requestBody = requestBody,
                protocolVersionHeader = exchange.requestHeaders.getFirst("MCP-Protocol-Version"),
            )

            writeResponse(exchange, response)
        } finally {
            exchange.close()
        }
    }

    private fun handleHealthExchange(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET") {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val status = statusProvider()
            writeResponse(
                exchange,
                IjMcpHttpResponse(
                    statusCode = 200,
                    body = json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("protocolVersion", IjMcpProtocol.protocolVersion)
                            put("requiresPairing", security.requiresPairing())
                            put("running", status?.running ?: false)
                            status?.let {
                                put("targetId", it.descriptor.targetId)
                                put("projectName", it.descriptor.projectName)
                                put("projectPath", it.descriptor.projectPath)
                                put("endpointUrl", it.endpointUrl)
                                put("port", it.port)
                            }
                        },
                    ),
                ),
            )
        } finally {
            exchange.close()
        }
    }

    private fun handlePairingExchange(exchange: HttpExchange) {
        try {
            if (!isAllowedOrigin(exchange.requestHeaders.getFirst("Origin"))) {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 403,
                        body = "Forbidden",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (exchange.requestMethod != "POST") {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val requestBody = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val pairingCode = parsePairingCode(requestBody)
                ?: run {
                    writeResponse(
                        exchange,
                        IjMcpHttpResponse(
                            statusCode = 400,
                            body = json.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("status", "error")
                                    put("errorCode", "invalid_pairing_request")
                                    put("message", "A non-empty pairingCode string is required.")
                                },
                            ),
                        ),
                    )
                    return
                }

            when (val result = security.exchangePairingCode(pairingCode)) {
                is IjMcpPairingExchangeResult.Success -> {
                    onAuthenticationStateChanged?.invoke()
                    val status = statusProvider()
                    writeResponse(
                        exchange,
                        IjMcpHttpResponse(
                            statusCode = 200,
                            body = json.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("status", "success")
                                    put("bearerToken", result.bearerToken)
                                    put("protocolVersion", IjMcpProtocol.protocolVersion)
                                    put("requiresPairing", security.requiresPairing())
                                    status?.let {
                                        put("targetId", it.descriptor.targetId)
                                        put("endpointUrl", it.endpointUrl)
                                        put("projectName", it.descriptor.projectName)
                                    }
                                },
                            ),
                        ),
                    )
                }

                is IjMcpPairingExchangeResult.Failure -> {
                    writeResponse(
                        exchange,
                        IjMcpHttpResponse(
                            statusCode = result.statusCode,
                            body = json.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("status", "error")
                                    put("errorCode", result.errorCode)
                                    put("message", result.message)
                                },
                            ),
                        ),
                    )
                }
            }
        } finally {
            exchange.close()
        }
    }

    private fun handleInternalPairingCodeExchange(exchange: HttpExchange) {
        try {
            val pairingCodeIssuer = internalPairingCodeIssuer
            if (pairingCodeIssuer == null) {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 404,
                        body = "Not Found",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (!isAllowedOrigin(exchange.requestHeaders.getFirst("Origin"))) {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 403,
                        body = "Forbidden",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            if (exchange.requestMethod != "POST") {
                writeResponse(
                    exchange,
                    IjMcpHttpResponse(
                        statusCode = 405,
                        body = "Method Not Allowed",
                        contentType = "text/plain; charset=utf-8",
                    ),
                )
                return
            }

            val issuedCode = pairingCodeIssuer()
                ?: run {
                    writeResponse(
                        exchange,
                        IjMcpHttpResponse(
                            statusCode = 409,
                            body = json.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("status", "error")
                                    put("errorCode", "pairing_not_available")
                                    put("message", "No active target is available to issue a pairing code.")
                                },
                            ),
                        ),
                    )
                    return
                }

            val status = statusProvider()
            writeResponse(
                exchange,
                IjMcpHttpResponse(
                    statusCode = 200,
                    body = json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("status", "success")
                            put("pairingCode", issuedCode.code)
                            put("expiresAt", issuedCode.expiresAt.toString())
                            put("protocolVersion", IjMcpProtocol.protocolVersion)
                            put("requiresPairing", security.requiresPairing())
                            status?.let {
                                put("targetId", it.descriptor.targetId)
                                put("endpointUrl", it.endpointUrl)
                                put("projectName", it.descriptor.projectName)
                            }
                        },
                    ),
                ),
            )
        } finally {
            exchange.close()
        }
    }

    private fun parsePairingCode(requestBody: String): String? = runCatching {
        val body = json.parseToJsonElement(requestBody) as? JsonObject ?: return@runCatching null
        body["pairingCode"]
            ?.toString()
            ?.trim('"')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun isAllowedOrigin(originHeader: String?): Boolean {
        if (originHeader.isNullOrBlank()) {
            return true
        }

        val host = runCatching { URI(originHeader).host }.getOrNull() ?: return false

        return host == "localhost" || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)
    }

    private fun writeResponse(
        exchange: HttpExchange,
        response: IjMcpHttpResponse,
    ) {
        response.contentType?.let { exchange.responseHeaders.set("Content-Type", it) }

        if (response.body == null) {
            exchange.sendResponseHeaders(response.statusCode, -1)
            return
        }

        val responseBytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(response.statusCode, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }
}
