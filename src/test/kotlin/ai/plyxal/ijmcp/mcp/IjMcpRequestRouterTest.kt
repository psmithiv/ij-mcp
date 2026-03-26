package ai.plyxal.ijmcp.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IjMcpRequestRouterTest {
    private val json = Json

    @Test
    fun initializeReturnsNegotiatedCapabilities() {
        val router = IjMcpRequestRouter(pluginVersion = "test-version")

        val response = router.handlePost(
            requestBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""",
            protocolVersionHeader = null,
        )

        val body = json.parseToJsonElement(requireNotNull(response.body)).jsonObject
        val result = body.getValue("result").jsonObject

        assertEquals(200, response.statusCode)
        assertEquals(IjMcpProtocol.protocolVersion, result.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("test-version", result.getValue("serverInfo").jsonObject.getValue("version").jsonPrimitive.content)
        assertTrue(result.getValue("capabilities").jsonObject.containsKey("tools"))
    }

    @Test
    fun notificationsInitializedReturnsAcceptedWithoutBody() {
        val router = IjMcpRequestRouter()

        val response = router.handlePost(
            requestBody = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}""",
            protocolVersionHeader = IjMcpProtocol.protocolVersion,
        )

        assertEquals(202, response.statusCode)
        assertEquals(null, response.body)
    }

    @Test
    fun toolsListReturnsStaticToolCatalog() {
        val router = IjMcpRequestRouter()

        val response = router.handlePost(
            requestBody = """{"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}""",
            protocolVersionHeader = IjMcpProtocol.protocolVersion,
        )

        val body = json.parseToJsonElement(requireNotNull(response.body)).jsonObject
        val tools = body.getValue("result").jsonObject.getValue("tools").jsonArray

        assertEquals(200, response.statusCode)
        assertEquals(8, tools.size)
        assertEquals("open_file", tools.first().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun toolsCallUsesStructuredToolErrorsForPlaceholderHandlers() {
        val router = IjMcpRequestRouter()

        val response = router.handlePost(
            requestBody = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"open_file","arguments":{"path":"README.md"}}}""",
            protocolVersionHeader = IjMcpProtocol.protocolVersion,
        )

        val body = json.parseToJsonElement(requireNotNull(response.body)).jsonObject
        val result = body.getValue("result").jsonObject
        val structuredContent = result.getValue("structuredContent").jsonObject

        assertEquals(200, response.statusCode)
        assertTrue(result.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals("error", structuredContent.getValue("status").jsonPrimitive.content)
        assertEquals("internal_error", structuredContent.getValue("errorCode").jsonPrimitive.content)
        assertNotNull(result.getValue("content"))
    }

    @Test
    fun unknownMethodsFailAtProtocolLayer() {
        val router = IjMcpRequestRouter()

        val response = router.handlePost(
            requestBody = """{"jsonrpc":"2.0","id":99,"method":"buffers/list","params":{}}""",
            protocolVersionHeader = IjMcpProtocol.protocolVersion,
        )

        val body = json.parseToJsonElement(requireNotNull(response.body)).jsonObject
        val error = body.getValue("error").jsonObject

        assertEquals(200, response.statusCode)
        assertEquals("-32601", error.getValue("code").jsonPrimitive.content)
        assertEquals("Method not found", error.getValue("message").jsonPrimitive.content)
        assertFalse(body.containsKey("result"))
    }
}
