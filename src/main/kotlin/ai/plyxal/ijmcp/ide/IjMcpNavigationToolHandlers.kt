package ai.plyxal.ijmcp.ide

import ai.plyxal.ijmcp.mcp.IjMcpToolCallResult
import ai.plyxal.ijmcp.mcp.IjMcpToolCatalog
import ai.plyxal.ijmcp.mcp.IjMcpToolHandler
import ai.plyxal.ijmcp.mcp.IjMcpToolResults
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IjMcpNavigationToolHandlers(
    private val project: Project,
    private val pathResolver: IjMcpPathResolver = IjMcpPathResolver(),
) {
    fun all(): List<IjMcpToolHandler> = listOf(
        OpenFileToolHandler(),
        FocusTabToolHandler(),
        ListOpenTabsToolHandler(),
        CloseTabToolHandler(),
        RevealFileInProjectToolHandler(),
        GetActiveEditorContextToolHandler(),
        MoveCaretToolHandler(),
        SelectEditorRangeToolHandler(),
    )

    private inner class OpenFileToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("open_file")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val path = arguments.stringValue("path")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty path is required.",
                )

            val line = arguments.intValue("line")
            val column = arguments.intValue("column")

            if (column != null && line == null) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A column cannot be supplied without a line.",
                )
            }

            if ((line != null && line < 1) || (column != null && column < 1)) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Line and column values must be 1-based positive integers.",
                )
            }

            val resolvedFile = when (val resolution = resolveNavigationFile(project, path)) {
                is ResolvedNavigationFile.Success -> resolution.resolvedFile
                is ResolvedNavigationFile.Failure -> return@withProject resolution.errorResult
            }

            var wasOpen = false
            var caretLine = line ?: 1
            var caretColumn = column ?: 1

            ApplicationManager.getApplication().invokeAndWait {
                val fileEditorManager = FileEditorManager.getInstance(project)
                wasOpen = fileEditorManager.isFileOpen(resolvedFile.file)

                val descriptor = if (line != null) {
                    OpenFileDescriptor(project, resolvedFile.file, line - 1, (column ?: 1) - 1)
                } else {
                    OpenFileDescriptor(project, resolvedFile.file)
                }

                descriptor.navigate(true)

                fileEditorManager.selectedTextEditor?.let { editor ->
                    val position = editor.caretModel.logicalPosition
                    caretLine = position.line + 1
                    caretColumn = position.column + 1
                }
            }

            IjMcpToolResults.success(
                contentText = "Opened ${resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Opened file.")
                    put("projectName", resolvedFile.projectName)
                    put("path", resolvedFile.path)
                    put("absolutePath", resolvedFile.absolutePath)
                    put("tabAction", if (wasOpen) "focused" else "opened")
                    put(
                        "caret",
                        buildJsonObject {
                            put("line", caretLine)
                            put("column", caretColumn)
                        },
                    )
                },
            )
        }
    }

    private inner class FocusTabToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("focus_tab")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val path = arguments.stringValue("path")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty path is required.",
                )

            val resolvedFile = when (val resolution = resolveNavigationFile(project, path)) {
                is ResolvedNavigationFile.Success -> resolution.resolvedFile
                is ResolvedNavigationFile.Failure -> return@withProject resolution.errorResult
            }
            var isOpen = false

            ApplicationManager.getApplication().invokeAndWait {
                val fileEditorManager = FileEditorManager.getInstance(project)
                isOpen = fileEditorManager.isFileOpen(resolvedFile.file)
                if (isOpen) {
                    OpenFileDescriptor(project, resolvedFile.file).navigate(true)
                }
            }

            if (!isOpen) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "tab_not_open",
                    message = "The requested file is not currently open in an editor tab.",
                )
            }

            IjMcpToolResults.success(
                contentText = "Focused ${resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Focused tab.")
                    put("projectName", resolvedFile.projectName)
                    put("path", resolvedFile.path)
                    put("absolutePath", resolvedFile.absolutePath)
                },
            )
        }
    }

    private inner class ListOpenTabsToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("list_open_tabs")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val tabs = mutableListOf<IjMcpResolvedFile>()
            var activePath: String? = null

            ApplicationManager.getApplication().invokeAndWait {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val selectedFile = fileEditorManager.selectedFiles.firstOrNull()

                fileEditorManager.openFiles.forEach { file ->
                    when (val description = pathResolver.describe(project, file)) {
                        is IjMcpResolvedFileResult.Success -> {
                            if (selectedFile == file) {
                                activePath = description.resolvedFile.path
                            }
                            tabs += description.resolvedFile
                        }

                        is IjMcpResolvedFileResult.Failure -> Unit
                    }
                }
            }

            IjMcpToolResults.success(
                contentText = "Listed ${tabs.size} open tabs.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Listed open tabs.")
                    put("projectName", project.name)
                    activePath?.let { put("activePath", it) }
                    put(
                        "tabs",
                        buildJsonArray {
                            tabs.forEach { tab ->
                                add(
                                    buildJsonObject {
                                        put("displayName", tab.file.name)
                                        put("path", tab.path)
                                        put("absolutePath", tab.absolutePath)
                                        put("isActive", tab.path == activePath)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private inner class CloseTabToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("close_tab")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val path = arguments.stringValue("path")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty path is required.",
                )

            val resolvedFile = when (val resolution = resolveNavigationFile(project, path)) {
                is ResolvedNavigationFile.Success -> resolution.resolvedFile
                is ResolvedNavigationFile.Failure -> return@withProject resolution.errorResult
            }
            var wasClosed = false

            ApplicationManager.getApplication().invokeAndWait {
                val fileEditorManager = FileEditorManager.getInstance(project)
                if (fileEditorManager.isFileOpen(resolvedFile.file)) {
                    fileEditorManager.closeFile(resolvedFile.file)
                    wasClosed = !fileEditorManager.isFileOpen(resolvedFile.file)
                }
            }

            if (!wasClosed) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "tab_not_open",
                    message = "The requested file is not currently open in an editor tab.",
                )
            }

            IjMcpToolResults.success(
                contentText = "Closed ${resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Closed tab.")
                    put("projectName", resolvedFile.projectName)
                    put("path", resolvedFile.path)
                    put("absolutePath", resolvedFile.absolutePath)
                    put("closed", true)
                },
            )
        }
    }

    private inner class RevealFileInProjectToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("reveal_file_in_project")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val path = arguments.stringValue("path")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty path is required.",
                )

            val resolvedFile = when (val resolution = resolveNavigationFile(project, path)) {
                is ResolvedNavigationFile.Success -> resolution.resolvedFile
                is ResolvedNavigationFile.Failure -> return@withProject resolution.errorResult
            }
            var revealed = false

            ApplicationManager.getApplication().invokeAndWait {
                val psiFile = PsiManager.getInstance(project).findFile(resolvedFile.file)
                if (psiFile != null) {
                    ProjectView.getInstance(project).selectPsiElement(psiFile, true)
                    revealed = true
                }
            }

            if (!revealed) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "internal_error",
                    message = "The file could not be revealed in the Project view.",
                )
            }

            IjMcpToolResults.success(
                contentText = "Revealed ${resolvedFile.path} in the Project view.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Revealed file in project view.")
                    put("projectName", resolvedFile.projectName)
                    put("path", resolvedFile.path)
                    put("absolutePath", resolvedFile.absolutePath)
                    put("revealed", true)
                },
            )
        }
    }

    private inner class GetActiveEditorContextToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("get_active_editor_context")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            var activeFile: VirtualFile? = null
            var caretLine = 1
            var caretColumn = 1
            var hasSelection = false
            var selectionStartLine = 1
            var selectionStartColumn = 1
            var selectionEndLine = 1
            var selectionEndColumn = 1

            ApplicationManager.getApplication().invokeAndWait {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                if (editor != null) {
                    activeFile = FileDocumentManager.getInstance().getFile(editor.document)

                    val caretPosition = editor.caretModel.logicalPosition
                    caretLine = caretPosition.line + 1
                    caretColumn = caretPosition.column + 1

                    val selectionModel = editor.selectionModel
                    hasSelection = selectionModel.hasSelection()
                    if (hasSelection) {
                        val start = editor.offsetToLogicalPosition(selectionModel.selectionStart)
                        val end = editor.offsetToLogicalPosition(selectionModel.selectionEnd)
                        selectionStartLine = start.line + 1
                        selectionStartColumn = start.column + 1
                        selectionEndLine = end.line + 1
                        selectionEndColumn = end.column + 1
                    }
                }
            }

            val file = activeFile
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "no_active_editor",
                    message = "No active editor is available for the current project.",
                )

            val resolvedFile = when (val result = pathResolver.describe(project, file)) {
                is IjMcpResolvedFileResult.Success -> result.resolvedFile
                is IjMcpResolvedFileResult.Failure -> {
                    return@withProject IjMcpToolResults.error(
                        errorCode = result.errorCode,
                        message = result.message,
                    )
                }
            }

            IjMcpToolResults.success(
                contentText = "Read active editor context for ${resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Read active editor context.")
                    put("projectName", resolvedFile.projectName)
                    put("path", resolvedFile.path)
                    put("absolutePath", resolvedFile.absolutePath)
                    put(
                        "caret",
                        buildJsonObject {
                            put("line", caretLine)
                            put("column", caretColumn)
                        },
                    )
                    put("hasSelection", hasSelection)
                    if (hasSelection) {
                        put(
                            "selection",
                            buildJsonObject {
                                put("startLine", selectionStartLine)
                                put("startColumn", selectionStartColumn)
                                put("endLine", selectionEndLine)
                                put("endColumn", selectionEndColumn)
                            },
                        )
                    }
                },
            )
        }
    }

    private inner class MoveCaretToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("move_caret")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val line = arguments.intValue("line")
            val column = arguments.intValue("column") ?: 1
            val path = arguments.stringValue("path")

            if (line == null || line < 1 || column < 1) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Line and column values must be 1-based positive integers.",
                )
            }

            val context = when (val result = resolveEditorNavigationContext(project, path)) {
                is EditorNavigationContextResult.Success -> result.context
                is EditorNavigationContextResult.Failure -> return@withProject result.errorResult
            }

            var caretLine = line
            var caretColumn = column
            var positionOutOfRange = false

            ApplicationManager.getApplication().invokeAndWait {
                val offset = context.editor.offsetForPosition(line, column)
                if (offset == null) {
                    positionOutOfRange = true
                    return@invokeAndWait
                }

                context.editor.caretModel.moveToOffset(offset)
                context.editor.selectionModel.removeSelection()
                context.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

                val caretPosition = context.editor.caretModel.logicalPosition
                caretLine = caretPosition.line + 1
                caretColumn = caretPosition.column + 1
            }

            if (positionOutOfRange) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "position_out_of_range",
                    message = "The requested caret position is outside the document.",
                )
            }

            IjMcpToolResults.success(
                contentText = "Moved caret in ${context.resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Moved caret.")
                    put("projectName", context.resolvedFile.projectName)
                    put("path", context.resolvedFile.path)
                    put("absolutePath", context.resolvedFile.absolutePath)
                    put(
                        "caret",
                        buildJsonObject {
                            put("line", caretLine)
                            put("column", caretColumn)
                        },
                    )
                },
            )
        }
    }

    private inner class SelectEditorRangeToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("select_editor_range")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val path = arguments.stringValue("path")
            val startLine = arguments.intValue("startLine")
            val startColumn = arguments.intValue("startColumn")
            val endLine = arguments.intValue("endLine")
            val endColumn = arguments.intValue("endColumn")

            if (
                startLine == null ||
                startColumn == null ||
                endLine == null ||
                endColumn == null ||
                startLine < 1 ||
                startColumn < 1 ||
                endLine < 1 ||
                endColumn < 1
            ) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Selection coordinates must be 1-based positive integers.",
                )
            }

            if (startLine > endLine || (startLine == endLine && startColumn >= endColumn)) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Selection start must be before selection end.",
                )
            }

            val context = when (val result = resolveEditorNavigationContext(project, path)) {
                is EditorNavigationContextResult.Success -> result.context
                is EditorNavigationContextResult.Failure -> return@withProject result.errorResult
            }

            var selectedStartLine = startLine
            var selectedStartColumn = startColumn
            var selectedEndLine = endLine
            var selectedEndColumn = endColumn
            var caretLine = endLine
            var caretColumn = endColumn
            var positionOutOfRange = false

            ApplicationManager.getApplication().invokeAndWait {
                val startOffset = context.editor.offsetForPosition(startLine, startColumn)
                val endOffset = context.editor.offsetForPosition(endLine, endColumn)
                if (startOffset == null || endOffset == null) {
                    positionOutOfRange = true
                    return@invokeAndWait
                }

                context.editor.selectionModel.setSelection(startOffset, endOffset)
                context.editor.caretModel.moveToOffset(endOffset)
                context.editor.scrollingModel.scrollToCaret(ScrollType.CENTER)

                val selectionModel = context.editor.selectionModel
                val selectedStart = context.editor.offsetToLogicalPosition(selectionModel.selectionStart)
                val selectedEnd = context.editor.offsetToLogicalPosition(selectionModel.selectionEnd)
                selectedStartLine = selectedStart.line + 1
                selectedStartColumn = selectedStart.column + 1
                selectedEndLine = selectedEnd.line + 1
                selectedEndColumn = selectedEnd.column + 1

                val caretPosition = context.editor.caretModel.logicalPosition
                caretLine = caretPosition.line + 1
                caretColumn = caretPosition.column + 1
            }

            if (positionOutOfRange) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "position_out_of_range",
                    message = "The requested selection range is outside the document.",
                )
            }

            IjMcpToolResults.success(
                contentText = "Selected range in ${context.resolvedFile.path}.",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Selected editor range.")
                    put("projectName", context.resolvedFile.projectName)
                    put("path", context.resolvedFile.path)
                    put("absolutePath", context.resolvedFile.absolutePath)
                    put("hasSelection", true)
                    put(
                        "selection",
                        buildJsonObject {
                            put("startLine", selectedStartLine)
                            put("startColumn", selectedStartColumn)
                            put("endLine", selectedEndLine)
                            put("endColumn", selectedEndColumn)
                        },
                    )
                    put(
                        "caret",
                        buildJsonObject {
                            put("line", caretLine)
                            put("column", caretColumn)
                        },
                    )
                },
            )
        }
    }

    private fun withProject(action: (project: Project) -> IjMcpToolCallResult): IjMcpToolCallResult = action(project)

    private fun resolveNavigationFile(
        project: Project,
        path: String,
    ): ResolvedNavigationFile = when (val fileResult = pathResolver.resolveFile(project, path)) {
        is IjMcpResolvedFileResult.Success -> ResolvedNavigationFile.Success(fileResult.resolvedFile)
        is IjMcpResolvedFileResult.Failure -> ResolvedNavigationFile.Failure(
            IjMcpToolResults.error(
                errorCode = fileResult.errorCode,
                message = fileResult.message,
            ),
        )
    }

    private fun resolveEditorNavigationContext(
        project: Project,
        path: String?,
    ): EditorNavigationContextResult {
        var editor: Editor? = null
        var file: VirtualFile? = null

        if (path != null) {
            val resolvedFile = when (val resolution = resolveNavigationFile(project, path)) {
                is ResolvedNavigationFile.Success -> resolution.resolvedFile
                is ResolvedNavigationFile.Failure -> return EditorNavigationContextResult.Failure(resolution.errorResult)
            }

            ApplicationManager.getApplication().invokeAndWait {
                editor = FileEditorManager.getInstance(project)
                    .openTextEditor(OpenFileDescriptor(project, resolvedFile.file), true)
                file = resolvedFile.file
            }

            val openedEditor = editor
                ?: return EditorNavigationContextResult.Failure(
                    IjMcpToolResults.error(
                        errorCode = "editor_unavailable",
                        message = "The requested file could not be opened in a text editor.",
                    ),
                )

            return EditorNavigationContextResult.Success(EditorNavigationContext(resolvedFile, openedEditor))
        }

        ApplicationManager.getApplication().invokeAndWait {
            val selectedEditor = FileEditorManager.getInstance(project).selectedTextEditor
            editor = selectedEditor
            file = selectedEditor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        }

        val activeEditor = editor
            ?: return EditorNavigationContextResult.Failure(
                IjMcpToolResults.error(
                    errorCode = "no_active_editor",
                    message = "No active editor is available for the current project.",
                ),
            )

        val activeFile = file
            ?: return EditorNavigationContextResult.Failure(
                IjMcpToolResults.error(
                    errorCode = "editor_file_unavailable",
                    message = "The active editor is not backed by a project file.",
                ),
            )

        val resolvedFile = when (val result = pathResolver.describe(project, activeFile)) {
            is IjMcpResolvedFileResult.Success -> result.resolvedFile
            is IjMcpResolvedFileResult.Failure -> {
                return EditorNavigationContextResult.Failure(
                    IjMcpToolResults.error(
                        errorCode = result.errorCode,
                        message = result.message,
                    ),
                )
            }
        }

        return EditorNavigationContextResult.Success(EditorNavigationContext(resolvedFile, activeEditor))
    }

    private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.intValue(name: String): Int? = (this[name] as? JsonPrimitive)
        ?.content
        ?.toIntOrNull()

    private fun Editor.offsetForPosition(line: Int, column: Int): Int? {
        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) {
            return null
        }

        val offset = document.getLineStartOffset(lineIndex) + column - 1
        return offset.takeIf { it <= document.getLineEndOffset(lineIndex) }
    }
}

private sealed interface ResolvedNavigationFile {
    data class Success(
        val resolvedFile: IjMcpResolvedFile,
    ) : ResolvedNavigationFile

    data class Failure(
        val errorResult: IjMcpToolCallResult,
    ) : ResolvedNavigationFile
}

private data class EditorNavigationContext(
    val resolvedFile: IjMcpResolvedFile,
    val editor: Editor,
)

private sealed interface EditorNavigationContextResult {
    data class Success(
        val context: EditorNavigationContext,
    ) : EditorNavigationContextResult

    data class Failure(
        val errorResult: IjMcpToolCallResult,
    ) : EditorNavigationContextResult
}
