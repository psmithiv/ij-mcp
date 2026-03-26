package ai.plyxal.ijmcp.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IjMcpToolRegistry(
    handlers: List<IjMcpToolHandler> = emptyList(),
) {
    private val handlersByName = buildMap {
        val customHandlers = handlers.associateBy { it.descriptor.name }

        IjMcpToolCatalog.descriptors.forEach { descriptor ->
            put(
                descriptor.name,
                customHandlers[descriptor.name] ?: IjMcpPlaceholderToolHandler(descriptor),
            )
        }
    }

    fun list(): List<IjMcpToolDescriptor> = IjMcpToolCatalog.descriptors

    fun find(name: String): IjMcpToolHandler? = handlersByName[name]
}

internal class IjMcpPlaceholderToolHandler(
    override val descriptor: IjMcpToolDescriptor,
) : IjMcpToolHandler {
    override fun call(arguments: JsonObject): IjMcpToolCallResult {
        val message = "Tool ${descriptor.name} is not implemented yet."

        return IjMcpToolCallResult(
            contentText = message,
            structuredContent = buildJsonObject {
                put("status", "error")
                put("errorCode", "internal_error")
                put("message", message)
            },
            isError = true,
        )
    }
}

internal object IjMcpToolCatalog {
    private val descriptorsByName: MutableMap<String, IjMcpToolDescriptor> = linkedMapOf()

    val descriptors: List<IjMcpToolDescriptor> by lazy {
        listOf(
            toolDescriptor(
                name = "open_file",
                title = "Open File",
                description = "Open a project file in IntelliJ and optionally move the caret to a 1-based line and column.",
                inputSchema = schemaObject(
                    required = listOf("path"),
                    properties = linkedMapOf(
                        "path" to stringSchema(
                            minLength = 1,
                            description = "Project-relative path or absolute path within the active project.",
                        ),
                        "line" to integerSchema(minimum = 1),
                        "column" to integerSchema(minimum = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
                        "tabAction" to enumStringSchema("opened", "focused"),
                        "caret" to objectSchema(
                            required = listOf("line", "column"),
                            properties = linkedMapOf(
                                "line" to integerSchema(minimum = 1),
                                "column" to integerSchema(minimum = 1),
                            ),
                            includeSchema = false,
                        ),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "focus_tab",
                title = "Focus Tab",
                description = "Focus an already open editor tab by file path.",
                inputSchema = schemaObject(
                    required = listOf("path"),
                    properties = linkedMapOf(
                        "path" to stringSchema(minLength = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "list_open_tabs",
                title = "List Open Tabs",
                description = "List the open editor tabs for the active project.",
                inputSchema = schemaObject(
                    required = emptyList(),
                    properties = linkedMapOf(),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "tabs"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "activePath" to stringSchema(),
                        "tabs" to arraySchema(
                            objectSchema(
                                required = listOf("displayName", "path", "absolutePath", "isActive"),
                                properties = linkedMapOf(
                                    "displayName" to stringSchema(),
                                    "path" to stringSchema(),
                                    "absolutePath" to stringSchema(),
                                    "isActive" to booleanSchema(),
                                ),
                                includeSchema = false,
                            ),
                        ),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "close_tab",
                title = "Close Tab",
                description = "Close an already open editor tab by file path.",
                inputSchema = schemaObject(
                    required = listOf("path"),
                    properties = linkedMapOf(
                        "path" to stringSchema(minLength = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
                        "closed" to booleanSchema(),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
            ),
            toolDescriptor(
                name = "reveal_file_in_project",
                title = "Reveal File In Project",
                description = "Reveal a file in the IntelliJ Project tool window.",
                inputSchema = schemaObject(
                    required = listOf("path"),
                    properties = linkedMapOf(
                        "path" to stringSchema(minLength = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
                        "revealed" to booleanSchema(),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "get_active_editor_context",
                title = "Get Active Editor Context",
                description = "Return the active editor file, caret position, and selection coordinates when present.",
                inputSchema = schemaObject(
                    required = emptyList(),
                    properties = linkedMapOf(),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
                        "caret" to objectSchema(
                            required = listOf("line", "column"),
                            properties = linkedMapOf(
                                "line" to integerSchema(minimum = 1),
                                "column" to integerSchema(minimum = 1),
                            ),
                            includeSchema = false,
                        ),
                        "selection" to objectSchema(
                            required = listOf("startLine", "startColumn", "endLine", "endColumn"),
                            properties = linkedMapOf(
                                "startLine" to integerSchema(minimum = 1),
                                "startColumn" to integerSchema(minimum = 1),
                                "endLine" to integerSchema(minimum = 1),
                                "endColumn" to integerSchema(minimum = 1),
                            ),
                            includeSchema = false,
                        ),
                        "hasSelection" to booleanSchema(),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "search_files",
                title = "Search Files",
                description = "Search project files by name using IntelliJ-aware indexing.",
                inputSchema = schemaObject(
                    required = listOf("query"),
                    properties = linkedMapOf(
                        "query" to stringSchema(minLength = 1),
                        "limit" to integerSchema(minimum = 1, maximum = 100, defaultValue = 20),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "query", "results", "totalReturned"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "query" to stringSchema(),
                        "totalReturned" to integerSchema(minimum = 0),
                        "results" to arraySchema(
                            objectSchema(
                                required = listOf("displayName", "path", "absolutePath", "matchKind"),
                                properties = linkedMapOf(
                                    "displayName" to stringSchema(),
                                    "path" to stringSchema(),
                                    "absolutePath" to stringSchema(),
                                    "matchKind" to enumStringSchema("exact", "prefix", "substring", "other"),
                                ),
                                includeSchema = false,
                            ),
                        ),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "search_symbols",
                title = "Search Symbols",
                description = "Search project symbols by name using IntelliJ navigation indexes.",
                inputSchema = schemaObject(
                    required = listOf("query"),
                    properties = linkedMapOf(
                        "query" to stringSchema(minLength = 1),
                        "limit" to integerSchema(minimum = 1, maximum = 100, defaultValue = 20),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "query", "results", "totalReturned"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "query" to stringSchema(),
                        "totalReturned" to integerSchema(minimum = 0),
                        "results" to arraySchema(
                            objectSchema(
                                required = listOf("symbolName", "path", "absolutePath"),
                                properties = linkedMapOf(
                                    "symbolName" to stringSchema(),
                                    "containerName" to stringSchema(),
                                    "path" to stringSchema(),
                                    "absolutePath" to stringSchema(),
                                    "line" to integerSchema(minimum = 1),
                                    "column" to integerSchema(minimum = 1),
                                ),
                                includeSchema = false,
                            ),
                        ),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
        )
            .onEach { descriptor -> descriptorsByName[descriptor.name] = descriptor }
    }

    fun descriptor(name: String): IjMcpToolDescriptor = descriptorsByName[name]
        ?: descriptors.first { it.name == name }

    private fun toolDescriptor(
        name: String,
        title: String,
        description: String,
        inputSchema: JsonObject,
        outputSchema: JsonObject,
        readOnlyHint: Boolean,
        destructiveHint: Boolean,
        idempotentHint: Boolean,
    ) = IjMcpToolDescriptor(
        name = name,
        title = title,
        description = description,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        annotations = buildJsonObject {
            put("title", title)
            put("readOnlyHint", readOnlyHint)
            put("destructiveHint", destructiveHint)
            put("idempotentHint", idempotentHint)
            put("openWorldHint", false)
        },
    )

    private fun schemaObject(
        required: List<String>,
        properties: LinkedHashMap<String, JsonObject>,
        includeSchema: Boolean = true,
    ): JsonObject = buildJsonObject {
        if (includeSchema) {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        }
        put("type", "object")
        put("additionalProperties", false)
        if (required.isNotEmpty()) {
            put("required", stringArray(required))
        }
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, schema) -> put(name, schema) }
            },
        )
    }

    private fun objectSchema(
        required: List<String>,
        properties: LinkedHashMap<String, JsonObject>,
        includeSchema: Boolean = false,
    ): JsonObject = schemaObject(
        required = required,
        properties = properties,
        includeSchema = includeSchema,
    )

    private fun stringSchema(
        minLength: Int? = null,
        description: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "string")
        minLength?.let { put("minLength", it) }
        description?.let { put("description", it) }
    }

    private fun enumStringSchema(vararg values: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", stringArray(values.asList()))
    }

    private fun integerSchema(
        minimum: Int? = null,
        maximum: Int? = null,
        defaultValue: Int? = null,
    ): JsonObject = buildJsonObject {
        put("type", "integer")
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
        defaultValue?.let { put("default", it) }
    }

    private fun booleanSchema(): JsonObject = buildJsonObject {
        put("type", "boolean")
    }

    private fun arraySchema(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
