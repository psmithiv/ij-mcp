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
        (listOf(
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
                name = "move_caret",
                title = "Move Caret",
                description = "Move the caret in the active editor or in a targeted project file.",
                inputSchema = schemaObject(
                    required = listOf("line"),
                    properties = linkedMapOf(
                        "path" to stringSchema(
                            minLength = 1,
                            description = "Optional project-relative path or absolute path within the active project.",
                        ),
                        "line" to integerSchema(minimum = 1),
                        "column" to integerSchema(minimum = 1, defaultValue = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "path", "absolutePath", "caret"),
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
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "select_editor_range",
                title = "Select Editor Range",
                description = "Select a 1-based logical range in the active editor or in a targeted project file.",
                inputSchema = schemaObject(
                    required = listOf("startLine", "startColumn", "endLine", "endColumn"),
                    properties = linkedMapOf(
                        "path" to stringSchema(
                            minLength = 1,
                            description = "Optional project-relative path or absolute path within the active project.",
                        ),
                        "startLine" to integerSchema(minimum = 1),
                        "startColumn" to integerSchema(minimum = 1),
                        "endLine" to integerSchema(minimum = 1),
                        "endColumn" to integerSchema(minimum = 1),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "path", "absolutePath", "selection", "hasSelection"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "path" to stringSchema(),
                        "absolutePath" to stringSchema(),
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
                        "caret" to objectSchema(
                            required = listOf("line", "column"),
                            properties = linkedMapOf(
                                "line" to integerSchema(minimum = 1),
                                "column" to integerSchema(minimum = 1),
                            ),
                            includeSchema = false,
                        ),
                        "hasSelection" to booleanSchema(),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "list_tool_windows",
                title = "List Tool Windows",
                description = "List IntelliJ tool windows registered for the active project.",
                inputSchema = schemaObject(
                    required = emptyList(),
                    properties = linkedMapOf(),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "toolWindows"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "toolWindows" to arraySchema(toolWindowSchema()),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "activate_tool_window",
                title = "Activate Tool Window",
                description = "Activate an available IntelliJ tool window by id.",
                inputSchema = toolWindowIdInputSchema(),
                outputSchema = toolWindowOutputSchema(),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "hide_tool_window",
                title = "Hide Tool Window",
                description = "Hide an IntelliJ tool window by id.",
                inputSchema = toolWindowIdInputSchema(),
                outputSchema = toolWindowOutputSchema(),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "get_active_tool_window",
                title = "Get Active Tool Window",
                description = "Return the currently active IntelliJ tool window for the active project.",
                inputSchema = schemaObject(
                    required = emptyList(),
                    properties = linkedMapOf(),
                ),
                outputSchema = toolWindowOutputSchema(),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "list_tool_window_content",
                title = "List Tool Window Content",
                description = "List content tabs within an IntelliJ tool window.",
                inputSchema = toolWindowIdInputSchema(),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "id", "contents"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "id" to stringSchema(),
                        "contents" to arraySchema(toolWindowContentSchema()),
                    ),
                ),
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "focus_tool_window_content",
                title = "Focus Tool Window Content",
                description = "Focus a content tab within an IntelliJ tool window by content name or zero-based index.",
                inputSchema = schemaObject(
                    required = listOf("id"),
                    properties = linkedMapOf(
                        "id" to stringSchema(minLength = 1),
                        "contentName" to stringSchema(minLength = 1),
                        "contentIndex" to integerSchema(minimum = 0),
                    ),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName", "id", "content"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                        "id" to stringSchema(),
                        "content" to toolWindowContentSchema(),
                    ),
                ),
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = true,
            ),
            toolDescriptor(
                name = "return_to_editor",
                title = "Return To Editor",
                description = "Return focus from the active tool window to the editor component.",
                inputSchema = schemaObject(
                    required = emptyList(),
                    properties = linkedMapOf(),
                ),
                outputSchema = schemaObject(
                    required = listOf("status", "message", "projectName"),
                    properties = linkedMapOf(
                        "status" to enumStringSchema("success", "error"),
                        "message" to stringSchema(),
                        "errorCode" to stringSchema(),
                        "projectName" to stringSchema(),
                    ),
                ),
                readOnlyHint = false,
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
        ) + navigationSurfaceDescriptors())
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

    private fun toolWindowIdInputSchema(): JsonObject = schemaObject(
        required = listOf("id"),
        properties = linkedMapOf(
            "id" to stringSchema(minLength = 1),
        ),
    )

    private fun toolWindowOutputSchema(): JsonObject = schemaObject(
        required = listOf("status", "message", "projectName", "id", "title"),
        properties = linkedMapOf(
            "status" to enumStringSchema("success", "error"),
            "message" to stringSchema(),
            "errorCode" to stringSchema(),
            "projectName" to stringSchema(),
            "id" to stringSchema(),
            "title" to stringSchema(),
            "isAvailable" to booleanSchema(),
            "isVisible" to booleanSchema(),
            "isActive" to booleanSchema(),
            "contentCount" to integerSchema(minimum = 0),
            "selectedContentName" to stringSchema(),
        ),
    )

    private fun toolWindowSchema(): JsonObject = objectSchema(
        required = listOf("id", "title", "isAvailable", "isVisible", "isActive", "contentCount"),
        properties = linkedMapOf(
            "id" to stringSchema(),
            "title" to stringSchema(),
            "isAvailable" to booleanSchema(),
            "isVisible" to booleanSchema(),
            "isActive" to booleanSchema(),
            "contentCount" to integerSchema(minimum = 0),
            "selectedContentName" to stringSchema(),
        ),
        includeSchema = false,
    )

    private fun toolWindowContentSchema(): JsonObject = objectSchema(
        required = listOf("index", "displayName", "isSelected"),
        properties = linkedMapOf(
            "index" to integerSchema(minimum = 0),
            "displayName" to stringSchema(),
            "isSelected" to booleanSchema(),
        ),
        includeSchema = false,
    )

    private fun navigationSurfaceDescriptors(): List<IjMcpToolDescriptor> = listOf(
        navigationSurfaceDescriptor("list_project_view_roots", "List Project View Roots", "List root nodes for the project view.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_project_view_children", "List Project View Children", "List child nodes for a project-view path.", required = listOf("path"), readOnlyHint = true),
        navigationSurfaceDescriptor("get_project_view_selection", "Get Project View Selection", "Return the current project-view selection when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("select_project_view_node", "Select Project View Node", "Select a project-view node by path.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("expand_project_view_node", "Expand Project View Node", "Expand or reveal a project-view node by path.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("collapse_project_view_node", "Collapse Project View Node", "Collapse a project-view node by path when supported.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("open_gradle_tool_window", "Open Gradle Tool Window", "Open the Gradle tool window when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("list_gradle_projects", "List Gradle Projects", "List Gradle project roots inferred from project files.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_gradle_tasks", "List Gradle Tasks", "List Gradle tasks declared in project build files.", readOnlyHint = true),
        navigationSurfaceDescriptor("reveal_gradle_project", "Reveal Gradle Project", "Reveal a Gradle project path in the IDE.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("focus_gradle_task", "Focus Gradle Task", "Focus a Gradle task when the Gradle tool window is available.", required = listOf("taskPath"), readOnlyHint = false),
        navigationSurfaceDescriptor("get_gradle_sync_status", "Get Gradle Sync Status", "Return Gradle sync availability for the active project.", readOnlyHint = true),
        navigationSurfaceDescriptor("goto_file", "Go To File", "Open the best matching project file by path or query.", readOnlyHint = false),
        navigationSurfaceDescriptor("goto_symbol", "Go To Symbol", "Open the best matching project symbol by query.", required = listOf("query"), readOnlyHint = false),
        navigationSurfaceDescriptor("goto_declaration", "Go To Declaration", "Navigate to the declaration for the current editor context when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("goto_implementation", "Go To Implementation", "Navigate to implementations for the current editor context when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("find_usages", "Find Usages", "Find usages for the current editor context when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("get_structure_view", "Get Structure View", "Return a code-outline view for the active or targeted file.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_structure_nodes", "List Structure Nodes", "List code-outline nodes for the active or targeted file.", readOnlyHint = true),
        navigationSurfaceDescriptor("focus_structure_node", "Focus Structure Node", "Focus a structure node by symbol name.", required = listOf("query"), readOnlyHint = false),
        navigationSurfaceDescriptor("expand_structure_node", "Expand Structure Node", "Return children for a structure node by symbol name.", required = listOf("query"), readOnlyHint = true),
        navigationSurfaceDescriptor("collapse_structure_node", "Collapse Structure Node", "Acknowledge a structure-node collapse request.", required = listOf("query"), readOnlyHint = false),
        navigationSurfaceDescriptor("goto_structure_symbol", "Go To Structure Symbol", "Navigate to a symbol from the active file structure.", required = listOf("query"), readOnlyHint = false),
        navigationSurfaceDescriptor("list_run_configurations", "List Run Configurations", "List configured run configurations for the active project.", readOnlyHint = true),
        navigationSurfaceDescriptor("select_run_configuration", "Select Run Configuration", "Select a run configuration by name.", required = listOf("name"), readOnlyHint = false),
        navigationSurfaceDescriptor("get_active_run_session", "Get Active Run Session", "Return active run-session content when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("open_run_tool_window", "Open Run Tool Window", "Open the Run tool window.", readOnlyHint = false),
        navigationSurfaceDescriptor("open_debug_tool_window", "Open Debug Tool Window", "Open the Debug tool window.", readOnlyHint = false),
        navigationSurfaceDescriptor("list_services", "List Services", "List service-view content when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("focus_service_node", "Focus Service Node", "Focus a service node when supported.", required = listOf("query"), readOnlyHint = false),
        navigationSurfaceDescriptor("open_problems_tool_window", "Open Problems Tool Window", "Open the Problems tool window when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("list_problems", "List Problems", "List project problems when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("focus_problem", "Focus Problem", "Focus a problem by id when available.", required = listOf("id"), readOnlyHint = false),
        navigationSurfaceDescriptor("list_file_diagnostics", "List File Diagnostics", "List diagnostics for a project file.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_inspection_results", "List Inspection Results", "List available inspection results when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("focus_diagnostic", "Focus Diagnostic", "Focus a file diagnostic by id or location.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("list_vcs_roots", "List VCS Roots", "List version-control roots for the active project.", readOnlyHint = true),
        navigationSurfaceDescriptor("get_current_branch", "Get Current Branch", "Return the current branch for known VCS roots when available.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_changed_files", "List Changed Files", "List changed files from IntelliJ VCS state.", readOnlyHint = true),
        navigationSurfaceDescriptor("focus_changed_file", "Focus Changed File", "Open a changed file by path.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("open_vcs_log", "Open VCS Log", "Open the VCS log tool window when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("open_commit_tool_window", "Open Commit Tool Window", "Open the Commit tool window when available.", readOnlyHint = false),
        navigationSurfaceDescriptor("reveal_change_in_vcs", "Reveal Change In VCS", "Reveal a changed file in VCS context.", required = listOf("path"), readOnlyHint = false),
        navigationSurfaceDescriptor("get_ide_context", "Get IDE Context", "Return active IDE project, editor, and tool-window context.", readOnlyHint = true),
        navigationSurfaceDescriptor("get_ui_focus_context", "Get UI Focus Context", "Return a lightweight UI focus summary.", readOnlyHint = true),
        navigationSurfaceDescriptor("list_open_projects", "List Open Projects", "List currently open IntelliJ projects.", readOnlyHint = true),
        navigationSurfaceDescriptor("get_project_context", "Get Project Context", "Return project identity and path context.", readOnlyHint = true),
        navigationSurfaceDescriptor("get_mcp_server_status", "Get MCP Server Status", "Return IJ-MCP target status for this project.", readOnlyHint = true),
    )

    private fun navigationSurfaceDescriptor(
        name: String,
        title: String,
        description: String,
        required: List<String> = emptyList(),
        readOnlyHint: Boolean,
    ): IjMcpToolDescriptor = toolDescriptor(
        name = name,
        title = title,
        description = description,
        inputSchema = navigationSurfaceInputSchema(required),
        outputSchema = navigationSurfaceOutputSchema(),
        readOnlyHint = readOnlyHint,
        destructiveHint = false,
        idempotentHint = true,
    )

    private fun navigationSurfaceInputSchema(required: List<String>): JsonObject = schemaObject(
        required = required,
        properties = linkedMapOf(
            "path" to stringSchema(minLength = 1),
            "id" to stringSchema(minLength = 1),
            "query" to stringSchema(minLength = 1),
            "name" to stringSchema(minLength = 1),
            "taskPath" to stringSchema(minLength = 1),
            "limit" to integerSchema(minimum = 1, maximum = 100, defaultValue = 20),
        ),
    )

    private fun navigationSurfaceOutputSchema(): JsonObject = schemaObject(
        required = listOf("status", "message", "projectName"),
        properties = linkedMapOf(
            "status" to enumStringSchema("success", "error"),
            "message" to stringSchema(),
            "errorCode" to stringSchema(),
            "projectName" to stringSchema(),
            "path" to stringSchema(),
            "absolutePath" to stringSchema(),
            "id" to stringSchema(),
            "query" to stringSchema(),
            "totalReturned" to integerSchema(minimum = 0),
            "items" to arraySchema(flexibleObjectSchema()),
            "results" to arraySchema(flexibleObjectSchema()),
            "details" to flexibleObjectSchema(),
        ),
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

    private fun flexibleObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", true)
    }

    private fun arraySchema(items: JsonObject): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", items)
    }

    private fun stringArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
