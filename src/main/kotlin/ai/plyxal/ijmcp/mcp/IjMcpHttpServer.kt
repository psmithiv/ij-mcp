package ai.plyxal.ijmcp.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class IjMcpHttpServer(
    private val router: IjMcpRequestRouter,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : AutoCloseable {
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
            handleExchange(config, exchange)
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

    private fun handleExchange(
        config: IjMcpServerConfig,
        exchange: HttpExchange,
    ) {
        try {
            if (!isAuthorized(exchange, config)) {
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

    private fun isAuthorized(
        exchange: HttpExchange,
        config: IjMcpServerConfig,
    ): Boolean = exchange.requestHeaders.getFirst("Authorization") == "Bearer ${config.bearerToken}"

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
