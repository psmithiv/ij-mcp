package ai.plyxal.ijmcp.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object IjMcpToolResults {
    fun success(
        contentText: String,
        structuredContent: JsonObject,
    ): IjMcpToolCallResult = IjMcpToolCallResult(
        contentText = contentText,
        structuredContent = structuredContent,
        isError = false,
    )

    fun error(
        errorCode: String,
        message: String,
    ): IjMcpToolCallResult = IjMcpToolCallResult(
        contentText = message,
        structuredContent = buildJsonObject {
            put("status", "error")
            put("errorCode", errorCode)
            put("message", message)
        },
        isError = true,
    )
}
