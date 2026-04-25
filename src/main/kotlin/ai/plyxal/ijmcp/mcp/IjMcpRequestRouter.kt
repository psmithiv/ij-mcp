package ai.plyxal.ijmcp.mcp

import ai.plyxal.ijmcp.app.IjMcpBuildInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class IjMcpHttpResponse(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String? = "application/json; charset=utf-8",
)

internal class IjMcpRequestRouter(
    private val toolRegistry: IjMcpToolRegistry = IjMcpToolRegistry(),
    private val pluginVersion: String = IjMcpBuildInfo.pluginVersion,
) {
    private val json = Json {
        prettyPrint = false
    }

    fun handlePost(
        requestBody: String,
        protocolVersionHeader: String?,
    ): IjMcpHttpResponse {
        val request = try {
            json.parseToJsonElement(requestBody).jsonObject
        } catch (_: Exception) {
            return jsonRpcErrorResponse(
                id = JsonNull,
                code = -32700,
                message = "Parse error",
            )
        }

        val requestId = request["id"] ?: JsonNull

        if (request["jsonrpc"].stringContent() != IjMcpProtocol.jsonRpcVersion) {
            return jsonRpcErrorResponse(requestId, -32600, "Invalid Request")
        }

        val method = request["method"].stringContent()
            ?: return jsonRpcErrorResponse(requestId, -32600, "Invalid Request")

        if (
            method != "initialize" &&
            protocolVersionHeader != null &&
            protocolVersionHeader != IjMcpProtocol.protocolVersion
        ) {
            return IjMcpHttpResponse(
                statusCode = 400,
                body = "Unsupported MCP-Protocol-Version header.",
                contentType = "text/plain; charset=utf-8",
            )
        }

        return try {
            when (method) {
                "initialize" -> handleInitialize(requestId)
                "notifications/initialized" -> IjMcpHttpResponse(statusCode = 202, body = null, contentType = null)
                "ping" -> handlePing(requestId)
                "tools/list" -> handleToolsList(requestId)
                "tools/call" -> handleToolsCall(requestId, request["params"])
                else -> jsonRpcErrorResponse(requestId, -32601, "Method not found")
            }
        } catch (_: Exception) {
            jsonRpcErrorResponse(requestId, -32603, "Internal error")
        }
    }

    private fun handleInitialize(requestId: JsonElement): IjMcpHttpResponse = jsonRpcSuccessResponse(
        id = requestId,
        result = buildJsonObject {
            put("protocolVersion", IjMcpProtocol.protocolVersion)
            put(
                "capabilities",
                buildJsonObject {
                    put("tools", buildJsonObject {})
                },
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", IjMcpProtocol.serverName)
                    put("title", IjMcpProtocol.serverTitle)
                    put("version", pluginVersion)
                    put("description", IjMcpProtocol.serverDescription)
                    put("websiteUrl", IjMcpProtocol.websiteUrl)
                },
            )
            put("instructions", IjMcpProtocol.instructions)
        },
    )

    private fun handlePing(requestId: JsonElement): IjMcpHttpResponse = jsonRpcSuccessResponse(
        id = requestId,
        result = buildJsonObject {},
    )

    private fun handleToolsList(requestId: JsonElement): IjMcpHttpResponse = jsonRpcSuccessResponse(
        id = requestId,
        result = buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    toolRegistry.list().forEach { descriptor ->
                        add(
                            buildJsonObject {
                                put("name", descriptor.name)
                                put("title", descriptor.title)
                                put("description", descriptor.description)
                                put("inputSchema", descriptor.inputSchema)
                                put("outputSchema", descriptor.outputSchema)
                                put("annotations", descriptor.annotations)
                            },
                        )
                    }
                },
            )
        },
    )

    private fun handleToolsCall(
        requestId: JsonElement,
        paramsElement: JsonElement?,
    ): IjMcpHttpResponse {
        val params = paramsElement as? JsonObject
            ?: return jsonRpcErrorResponse(requestId, -32602, "Invalid params")

        val toolName = params["name"].stringContent()
            ?: return jsonRpcErrorResponse(requestId, -32602, "Invalid params")

        val arguments = when (val rawArguments = params["arguments"]) {
            null -> buildJsonObject {}
            is JsonObject -> rawArguments
            else -> return jsonRpcErrorResponse(requestId, -32602, "Invalid params")
        }

        val handler = toolRegistry.find(toolName)
            ?: return jsonRpcErrorResponse(requestId, -32601, "Unknown tool: $toolName")

        val result = handler.call(arguments)

        return jsonRpcSuccessResponse(
            id = requestId,
            result = buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", result.contentText)
                            },
                        )
                    },
                )
                put("structuredContent", result.structuredContent)
                if (result.isError) {
                    put("isError", true)
                }
            },
        )
    }

    private fun jsonRpcSuccessResponse(
        id: JsonElement,
        result: JsonObject,
    ): IjMcpHttpResponse = IjMcpHttpResponse(
        statusCode = 200,
        body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", IjMcpProtocol.jsonRpcVersion)
                put("id", id)
                put("result", result)
            },
        ),
    )

    private fun jsonRpcErrorResponse(
        id: JsonElement,
        code: Int,
        message: String,
    ): IjMcpHttpResponse = IjMcpHttpResponse(
        statusCode = 200,
        body = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", IjMcpProtocol.jsonRpcVersion)
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            },
        ),
    )

    private fun JsonElement?.stringContent(): String? = (this as? JsonPrimitive)?.content
}
