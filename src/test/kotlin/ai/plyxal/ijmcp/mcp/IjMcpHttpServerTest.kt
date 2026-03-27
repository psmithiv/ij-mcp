package ai.plyxal.ijmcp.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class IjMcpHttpServerTest {
    private val client = HttpClient.newHttpClient()

    @Test
    fun rejectsRequestsWithoutBearerToken() {
        withServer { port ->
            val response = client.send(
                requestBuilder(port)
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(401, response.statusCode())
        }
    }

    @Test
    fun rejectsInvalidOriginHeaders() {
        withServer { port ->
            val response = client.send(
                requestBuilder(port)
                    .header("Authorization", "Bearer test-token")
                    .header("Origin", "https://example.com")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(403, response.statusCode())
        }
    }

    @Test
    fun postInitRequestsRequireTheConfiguredProtocolHeader() {
        withServer { port ->
            val response = client.send(
                requestBuilder(port)
                    .header("Authorization", "Bearer test-token")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(400, response.statusCode())
        }
    }

    @Test
    fun getRequestsReturnMethodNotAllowed() {
        withServer { port ->
            val response = client.send(
                requestBuilder(port)
                    .header("Authorization", "Bearer test-token")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            assertEquals(405, response.statusCode())
        }
    }

    private fun withServer(block: (port: Int) -> Unit) {
        IjMcpHttpServer(
            IjMcpRequestRouter(),
            security = IjMcpStaticTokenSecurity("test-token"),
        ).use { server ->
            val port = server.start(IjMcpServerConfig(port = 0))
            block(port)
        }
    }

    private fun requestBuilder(port: Int): HttpRequest.Builder = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:$port${IjMcpProtocol.endpointPath}"),
    )
}
