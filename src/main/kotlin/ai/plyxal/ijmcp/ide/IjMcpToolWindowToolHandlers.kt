package ai.plyxal.ijmcp.ide

import ai.plyxal.ijmcp.mcp.IjMcpToolCallResult
import ai.plyxal.ijmcp.mcp.IjMcpToolCatalog
import ai.plyxal.ijmcp.mcp.IjMcpToolHandler
import ai.plyxal.ijmcp.mcp.IjMcpToolResults
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IjMcpToolWindowToolHandlers(
    private val project: Project,
) {
    fun all(): List<IjMcpToolHandler> = listOf(
        ListToolWindowsToolHandler(),
        ActivateToolWindowToolHandler(),
        HideToolWindowToolHandler(),
        GetActiveToolWindowToolHandler(),
        ListToolWindowContentToolHandler(),
        FocusToolWindowContentToolHandler(),
        ReturnToEditorToolHandler(),
    )

    private inner class ListToolWindowsToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("list_tool_windows")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val windows = mutableListOf<ToolWindowSnapshot>()

            ApplicationManager.getApplication().invokeAndWait {
                val manager = ToolWindowManager.getInstance(project)
                manager.toolWindowIds.sorted().forEach { id ->
                    manager.getToolWindow(id)?.let { toolWindow ->
                        windows += toolWindow.snapshot(id)
                    }
                }
            }

            IjMcpToolResults.success(
                contentText = "Listed ${windows.size} tool windows.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Listed tool windows.")
                    put("projectName", project.name)
                    put(
                        "toolWindows",
                        buildJsonArray {
                            windows.forEach { add(it.toJson()) }
                        },
                    )
                },
            )
        }
    }

    private inner class ActivateToolWindowToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("activate_tool_window")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withToolWindow(arguments) { id, toolWindow ->
            if (!toolWindow.isAvailable) {
                return@withToolWindow IjMcpToolResults.error(
                    errorCode = "tool_window_unavailable",
                    message = "Tool window $id is not currently available.",
                )
            }

            var snapshot: ToolWindowSnapshot? = null
            ApplicationManager.getApplication().invokeAndWait {
                toolWindow.activate(null, true)
                snapshot = toolWindow.snapshot(id)
            }

            IjMcpToolResults.success(
                contentText = "Activated tool window $id.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Activated tool window.")
                    put("projectName", project.name)
                    snapshot?.toJson()?.forEach { (key, value) -> put(key, value) }
                },
            )
        }
    }

    private inner class HideToolWindowToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("hide_tool_window")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withToolWindow(arguments) { id, toolWindow ->
            var snapshot: ToolWindowSnapshot? = null
            ApplicationManager.getApplication().invokeAndWait {
                toolWindow.hide(null)
                snapshot = toolWindow.snapshot(id)
            }

            IjMcpToolResults.success(
                contentText = "Hid tool window $id.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Hid tool window.")
                    put("projectName", project.name)
                    snapshot?.toJson()?.forEach { (key, value) -> put(key, value) }
                },
            )
        }
    }

    private inner class GetActiveToolWindowToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("get_active_tool_window")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            var snapshot: ToolWindowSnapshot? = null
            ApplicationManager.getApplication().invokeAndWait {
                val manager = ToolWindowManager.getInstance(project)
                val activeId = manager.activeToolWindowId
                if (activeId != null) {
                    snapshot = manager.getToolWindow(activeId)?.snapshot(activeId)
                }
            }

            val activeToolWindow = snapshot
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "no_active_tool_window",
                    message = "No active tool window is available for the current project.",
                )

            IjMcpToolResults.success(
                contentText = "Read active tool window ${activeToolWindow.id}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Read active tool window.")
                    put("projectName", project.name)
                    activeToolWindow.toJson().forEach { (key, value) -> put(key, value) }
                },
            )
        }
    }

    private inner class ListToolWindowContentToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("list_tool_window_content")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withToolWindow(arguments) { id, toolWindow ->
            val contents = mutableListOf<ToolWindowContentSnapshot>()

            ApplicationManager.getApplication().invokeAndWait {
                val selectedContent = toolWindow.contentManager.selectedContent
                toolWindow.contentManager.contents.forEachIndexed { index, content ->
                    contents += content.snapshot(
                        index = index,
                        isSelected = content == selectedContent,
                    )
                }
            }

            IjMcpToolResults.success(
                contentText = "Listed ${contents.size} content entries for tool window $id.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Listed tool window content.")
                    put("projectName", project.name)
                    put("id", id)
                    put(
                        "contents",
                        buildJsonArray {
                            contents.forEach { add(it.toJson()) }
                        },
                    )
                },
            )
        }
    }

    private inner class FocusToolWindowContentToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("focus_tool_window_content")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withToolWindow(arguments) { id, toolWindow ->
            val contentName = arguments.stringValue("contentName")
            val contentIndex = arguments.intValue("contentIndex")
            if (contentName == null && contentIndex == null) {
                return@withToolWindow IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A contentName or contentIndex is required.",
                )
            }
            if (contentIndex != null && contentIndex < 0) {
                return@withToolWindow IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "contentIndex must be zero or greater.",
                )
            }

            var selectedSnapshot: ToolWindowContentSnapshot? = null
            ApplicationManager.getApplication().invokeAndWait {
                val contents = toolWindow.contentManager.contents
                val selectedContent = contentName?.let { expectedName ->
                    contents.firstOrNull { it.matchesName(expectedName) }
                } ?: contentIndex?.let { contents.getOrNull(it) }

                if (selectedContent != null) {
                    toolWindow.contentManager.setSelectedContent(selectedContent, true)
                    toolWindow.activate(null, true)
                    val selectedIndex = contents.indexOf(selectedContent)
                    selectedSnapshot = selectedContent.snapshot(selectedIndex, isSelected = true)
                }
            }

            val focusedContent = selectedSnapshot
                ?: return@withToolWindow IjMcpToolResults.error(
                    errorCode = "content_not_found",
                    message = "The requested tool window content was not found.",
                )

            IjMcpToolResults.success(
                contentText = "Focused content ${focusedContent.displayName} in tool window $id.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Focused tool window content.")
                    put("projectName", project.name)
                    put("id", id)
                    put("content", focusedContent.toJson())
                },
            )
        }
    }

    private inner class ReturnToEditorToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("return_to_editor")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            ApplicationManager.getApplication().invokeAndWait {
                ToolWindowManager.getInstance(project).activateEditorComponent()
            }

            IjMcpToolResults.success(
                contentText = "Returned focus to the editor.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Returned focus to editor.")
                    put("projectName", project.name)
                },
            )
        }
    }

    private fun withProject(action: (project: Project) -> IjMcpToolCallResult): IjMcpToolCallResult = action(project)

    private fun withToolWindow(
        arguments: JsonObject,
        action: (id: String, toolWindow: ToolWindow) -> IjMcpToolCallResult,
    ): IjMcpToolCallResult = withProject { project ->
        val id = arguments.stringValue("id")
            ?: return@withProject IjMcpToolResults.error(
                errorCode = "invalid_tool_arguments",
                message = "A non-empty tool window id is required.",
            )

        var toolWindow: ToolWindow? = null
        ApplicationManager.getApplication().invokeAndWait {
            toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id)
        }

        action(
            id,
            toolWindow
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "tool_window_not_found",
                    message = "Tool window $id was not found.",
                ),
        )
    }

    private fun ToolWindow.snapshot(id: String): ToolWindowSnapshot = ToolWindowSnapshot(
        id = id,
        title = stripeTitle,
        isAvailable = isAvailable,
        isVisible = isVisible,
        isActive = isActive,
        contentCount = contentManager.contentCount,
        selectedContentName = contentManager.selectedContent?.displayName,
    )

    private fun Content.snapshot(
        index: Int,
        isSelected: Boolean,
    ): ToolWindowContentSnapshot = ToolWindowContentSnapshot(
        index = index,
        displayName = displayName,
        isSelected = isSelected,
    )

    private fun Content.matchesName(name: String): Boolean = displayName.equals(name, ignoreCase = true)

    private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intValue(name: String): Int? = (this[name] as? JsonPrimitive)
        ?.content
        ?.toIntOrNull()
}

private data class ToolWindowSnapshot(
    val id: String,
    val title: String,
    val isAvailable: Boolean,
    val isVisible: Boolean,
    val isActive: Boolean,
    val contentCount: Int,
    val selectedContentName: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("isAvailable", isAvailable)
        put("isVisible", isVisible)
        put("isActive", isActive)
        put("contentCount", contentCount)
        selectedContentName?.let { put("selectedContentName", it) }
    }
}

private data class ToolWindowContentSnapshot(
    val index: Int,
    val displayName: String,
    val isSelected: Boolean,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("index", index)
        put("displayName", displayName)
        put("isSelected", isSelected)
    }
}
