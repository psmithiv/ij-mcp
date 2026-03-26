package ai.plyxal.ijmcp.mcp

import kotlinx.serialization.json.JsonObject

internal data class IjMcpToolDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val annotations: JsonObject,
)

internal data class IjMcpToolCallResult(
    val contentText: String,
    val structuredContent: JsonObject,
    val isError: Boolean = false,
)

internal interface IjMcpToolHandler {
    val descriptor: IjMcpToolDescriptor

    fun call(arguments: JsonObject): IjMcpToolCallResult
}
