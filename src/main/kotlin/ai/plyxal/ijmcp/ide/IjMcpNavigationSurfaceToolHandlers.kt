package ai.plyxal.ijmcp.ide

import ai.plyxal.ijmcp.mcp.IjMcpToolCallResult
import ai.plyxal.ijmcp.mcp.IjMcpToolCatalog
import ai.plyxal.ijmcp.mcp.IjMcpToolHandler
import ai.plyxal.ijmcp.mcp.IjMcpToolResults
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import com.intellij.execution.RunManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

internal class IjMcpNavigationSurfaceToolHandlers(
    private val project: Project,
    private val statusProvider: () -> IjMcpTargetStatus? = { null },
    private val pathResolver: IjMcpPathResolver = IjMcpPathResolver(),
) {
    fun all(): List<IjMcpToolHandler> = handledToolNames.map { ToolHandler(it) }

    private inner class ToolHandler(
        private val name: String,
    ) : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor(name)

        override fun call(arguments: JsonObject): IjMcpToolCallResult = when (name) {
            "list_project_view_roots" -> listProjectViewRoots()
            "list_project_view_children" -> listProjectViewChildren(arguments)
            "get_project_view_selection" -> getProjectViewSelection()
            "select_project_view_node", "expand_project_view_node" -> selectProjectViewNode(arguments, name)
            "collapse_project_view_node" -> acknowledgeProjectViewCollapse(arguments)
            "open_gradle_tool_window" -> openToolWindow("Gradle", "gradle_plugin_unavailable")
            "list_gradle_projects" -> listGradleProjects()
            "list_gradle_tasks" -> listGradleTasks(arguments)
            "reveal_gradle_project" -> selectProjectViewNode(arguments, name)
            "focus_gradle_task" -> focusGradleTask(arguments)
            "get_gradle_sync_status" -> getGradleSyncStatus()
            "goto_file" -> gotoFile(arguments)
            "goto_symbol" -> gotoSymbol(arguments)
            "goto_declaration", "goto_implementation", "find_usages" -> unavailable(
                errorCode = "navigation_target_unavailable",
                message = "This navigation target requires editor PSI actions that are not exposed by this IJ-MCP build.",
            )
            "get_structure_view", "list_structure_nodes" -> listStructureNodes(arguments)
            "focus_structure_node", "goto_structure_symbol" -> gotoStructureSymbol(arguments)
            "expand_structure_node" -> listStructureNodes(arguments)
            "collapse_structure_node" -> acknowledgeStructureCollapse(arguments)
            "list_run_configurations" -> listRunConfigurations()
            "select_run_configuration" -> selectRunConfiguration(arguments)
            "get_active_run_session" -> getActiveToolWindowContent("Run", "run_session_not_found")
            "open_run_tool_window" -> openToolWindow("Run", "tool_window_not_found")
            "open_debug_tool_window" -> openToolWindow("Debug", "tool_window_not_found")
            "list_services" -> listToolWindowContent("Services", "service_view_unavailable")
            "focus_service_node" -> unavailable("service_node_not_found", "Service node focusing is not available through this IJ-MCP build.")
            "open_problems_tool_window" -> openFirstToolWindow(listOf("Problems View", "Problems"), "diagnostics_unavailable")
            "list_problems", "list_file_diagnostics", "list_inspection_results" -> unavailable(
                errorCode = "diagnostics_unavailable",
                message = "Problem and inspection result extraction is not available through this IJ-MCP build.",
            )
            "focus_problem", "focus_diagnostic" -> focusDiagnostic(arguments)
            "list_vcs_roots" -> listVcsRoots()
            "get_current_branch" -> getCurrentBranch()
            "list_changed_files" -> listChangedFiles()
            "focus_changed_file", "reveal_change_in_vcs" -> gotoFile(arguments)
            "open_vcs_log" -> openFirstToolWindow(listOf("Version Control", "Git"), "vcs_unavailable")
            "open_commit_tool_window" -> openToolWindow("Commit", "vcs_unavailable")
            "get_ide_context" -> getIdeContext()
            "get_ui_focus_context" -> getUiFocusContext()
            "list_open_projects" -> listOpenProjects()
            "get_project_context" -> getProjectContext()
            "get_mcp_server_status" -> getMcpServerStatus()
            else -> unavailable("unsupported_tool", "Tool $name is not handled by this IJ-MCP build.")
        }
    }

    private fun listProjectViewRoots(): IjMcpToolCallResult {
        val root = projectRoot()
            ?: return unavailable("project_not_available", "The active project does not have a resolvable base directory.")

        return success("Listed project view roots.") {
            put("totalReturned", 1)
            put("items", buildJsonArray { add(root.nodeJson()) })
        }
    }

    private fun listProjectViewChildren(arguments: JsonObject): IjMcpToolCallResult {
        val directory = resolveVirtualFile(arguments.stringValue("path"), allowDirectory = true)
            ?: return unavailable("node_not_found", "The requested project-view node was not found.")
        if (!directory.isDirectory) {
            return unavailable("invalid_tool_arguments", "The requested project-view node is not a directory.")
        }

        val children = ReadAction.compute<List<VirtualFile>, RuntimeException> {
            directory.children.sortedWith(compareBy<VirtualFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }

        return success("Listed project view children.") {
            put("path", relativePath(directory))
            put("absolutePath", directory.presentableUrl)
            put("totalReturned", children.size)
            put(
                "items",
                buildJsonArray {
                    children.forEach { add(it.nodeJson()) }
                },
            )
        }
    }

    private fun getProjectViewSelection(): IjMcpToolCallResult {
        val selectedFile = ApplicationManager.getApplication().let {
            var file: VirtualFile? = null
            it.invokeAndWait {
                file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            }
            file
        } ?: return unavailable("node_not_found", "No project-view selection could be inferred.")

        return success("Read project view selection.") {
            put("path", relativePath(selectedFile))
            put("absolutePath", selectedFile.presentableUrl)
            put("details", selectedFile.nodeJson())
        }
    }

    private fun selectProjectViewNode(
        arguments: JsonObject,
        toolName: String,
    ): IjMcpToolCallResult {
        val file = resolveVirtualFile(arguments.stringValue("path"), allowDirectory = true)
            ?: return unavailable("node_not_found", "The requested project-view node was not found.")

        var selected = false
        ApplicationManager.getApplication().invokeAndWait {
            val psiElement = if (file.isDirectory) {
                PsiManager.getInstance(project).findDirectory(file)
            } else {
                PsiManager.getInstance(project).findFile(file)
            }
            if (psiElement != null) {
                ProjectView.getInstance(project).selectPsiElement(psiElement, true)
                selected = true
            }
        }

        if (!selected) {
            return unavailable("project_view_unavailable", "The requested node could not be selected in Project view.")
        }

        return success("Updated project view node for $toolName.") {
            put("path", relativePath(file))
            put("absolutePath", file.presentableUrl)
            put("details", file.nodeJson())
        }
    }

    private fun acknowledgeProjectViewCollapse(arguments: JsonObject): IjMcpToolCallResult {
        val file = resolveVirtualFile(arguments.stringValue("path"), allowDirectory = true)
            ?: return unavailable("node_not_found", "The requested project-view node was not found.")

        return success("Project view collapse acknowledged.") {
            put("path", relativePath(file))
            put("absolutePath", file.presentableUrl)
            put("details", buildJsonObject { put("collapsed", false) })
        }
    }

    private fun listGradleProjects(): IjMcpToolCallResult {
        val root = projectRoot()
            ?: return unavailable("project_not_available", "The active project does not have a resolvable base directory.")
        val projects = gradleBuildFiles(root).map { it.parent }.distinctBy { it.path }

        return success("Listed Gradle projects.") {
            put("totalReturned", projects.size)
            put(
                "items",
                buildJsonArray {
                    projects.forEach { projectFile ->
                        add(
                            buildJsonObject {
                                put("path", relativePath(projectFile))
                                put("absolutePath", projectFile.presentableUrl)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun listGradleTasks(arguments: JsonObject): IjMcpToolCallResult {
        val taskRegexes = listOf(
            Regex("""tasks\.(?:register|create)\(["']([^"']+)["']"""),
            Regex("""task\s+([A-Za-z0-9_:-]+)"""),
        )
        val tasks = mutableSetOf<String>()
        val root = arguments.stringValue("path")?.let { resolveVirtualFile(it, allowDirectory = true) } ?: projectRoot()
            ?: return unavailable("project_not_available", "The active project does not have a resolvable base directory.")

        gradleBuildFiles(root).forEach { file ->
            val text = runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
            taskRegexes.forEach { regex ->
                regex.findAll(text).forEach { match -> tasks += match.groupValues[1] }
            }
        }

        return success("Listed Gradle tasks.") {
            put("totalReturned", tasks.size)
            put(
                "items",
                buildJsonArray {
                    tasks.sorted().forEach { taskName ->
                        add(buildJsonObject { put("taskPath", taskName) })
                    }
                },
            )
        }
    }

    private fun focusGradleTask(arguments: JsonObject): IjMcpToolCallResult {
        val taskPath = arguments.stringValue("taskPath")
            ?: return unavailable("invalid_tool_arguments", "A non-empty taskPath is required.")
        return openToolWindow("Gradle", "gradle_plugin_unavailable", details = buildJsonObject { put("taskPath", taskPath) })
    }

    private fun getGradleSyncStatus(): IjMcpToolCallResult {
        val hasGradleFiles = projectRoot()?.let { gradleBuildFiles(it).isNotEmpty() } ?: false
        val hasGradleWindow = hasToolWindow("Gradle")
        return success("Read Gradle sync status.") {
            put(
                "details",
                buildJsonObject {
                    put("gradleFilesDetected", hasGradleFiles)
                    put("gradleToolWindowAvailable", hasGradleWindow)
                    put("syncStatus", if (hasGradleWindow) "available" else "unknown")
                },
            )
        }
    }

    private fun gotoFile(arguments: JsonObject): IjMcpToolCallResult {
        val path = arguments.stringValue("path")
        val query = arguments.stringValue("query")
        val file = when {
            path != null -> resolveFileOnly(path)
            query != null -> findFileByQuery(query)
            else -> null
        } ?: return unavailable("file_not_found", "No project file matched the supplied path or query.")

        openFile(file)
        return success("Opened file.") {
            put("path", relativePath(file))
            put("absolutePath", file.presentableUrl)
        }
    }

    private fun gotoSymbol(arguments: JsonObject): IjMcpToolCallResult {
        val query = arguments.stringValue("query")
            ?: return unavailable("invalid_tool_arguments", "A non-empty query is required.")
        val target = findNamedElement(query)
            ?: return unavailable("symbol_not_found", "No project symbol matched query \"$query\".")

        openElement(target)
        return success("Opened symbol.") {
            put("query", query)
            put("details", target.elementJson())
        }
    }

    private fun listStructureNodes(arguments: JsonObject): IjMcpToolCallResult {
        val file = resolveStructureFile(arguments)
            ?: return unavailable("no_active_editor", "No active or targeted file is available for structure navigation.")
        val nodes = structureNodes(file, arguments.limitValue())

        return success("Listed structure nodes.") {
            put("path", relativePath(file))
            put("absolutePath", file.presentableUrl)
            put("totalReturned", nodes.size)
            put("items", buildJsonArray { nodes.forEach { add(it.elementJson()) } })
        }
    }

    private fun gotoStructureSymbol(arguments: JsonObject): IjMcpToolCallResult {
        val query = arguments.stringValue("query")
            ?: return unavailable("invalid_tool_arguments", "A non-empty query is required.")
        val file = resolveStructureFile(arguments)
            ?: return unavailable("no_active_editor", "No active or targeted file is available for structure navigation.")
        val node = structureNodes(file, arguments.limitValue()).firstOrNull {
            it.name?.contains(query, ignoreCase = true) == true
        } ?: return unavailable("node_not_found", "No structure node matched query \"$query\".")

        openElement(node)
        return success("Focused structure node.") {
            put("query", query)
            put("details", node.elementJson())
        }
    }

    private fun acknowledgeStructureCollapse(arguments: JsonObject): IjMcpToolCallResult = success("Structure node collapse acknowledged.") {
        arguments.stringValue("query")?.let { put("query", it) }
        put("details", buildJsonObject { put("collapsed", false) })
    }

    private fun listRunConfigurations(): IjMcpToolCallResult {
        val settings = RunManager.getInstance(project).allSettings
        return success("Listed run configurations.") {
            put("totalReturned", settings.size)
            put(
                "items",
                buildJsonArray {
                    settings.forEach {
                        add(
                            buildJsonObject {
                                put("name", it.name)
                                put("type", it.type.displayName)
                                put("isSelected", RunManager.getInstance(project).selectedConfiguration == it)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun selectRunConfiguration(arguments: JsonObject): IjMcpToolCallResult {
        val name = arguments.stringValue("name")
            ?: return unavailable("invalid_tool_arguments", "A non-empty run configuration name is required.")
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.firstOrNull { it.name == name }
            ?: return unavailable("run_configuration_not_found", "Run configuration \"$name\" was not found.")
        runManager.selectedConfiguration = settings
        return success("Selected run configuration.") {
            put("details", buildJsonObject { put("name", settings.name); put("type", settings.type.displayName) })
        }
    }

    private fun getActiveToolWindowContent(
        id: String,
        errorCode: String,
    ): IjMcpToolCallResult {
        var selectedContentName: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            selectedContentName = ToolWindowManager.getInstance(project).getToolWindow(id)?.contentManager?.selectedContent?.displayName
        }
        return selectedContentName?.let {
            success("Read active $id session.") {
                put("id", id)
                put("details", buildJsonObject { put("selectedContentName", it) })
            }
        } ?: unavailable(errorCode, "No active $id content is available.")
    }

    private fun listToolWindowContent(
        id: String,
        errorCode: String,
    ): IjMcpToolCallResult {
        val contentNames = mutableListOf<String>()
        ApplicationManager.getApplication().invokeAndWait {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(id)
            toolWindow?.contentManager?.contents?.forEach { contentNames += it.displayName }
        }
        if (contentNames.isEmpty()) {
            return unavailable(errorCode, "$id content is not available.")
        }
        return success("Listed $id content.") {
            put("totalReturned", contentNames.size)
            put("items", buildJsonArray { contentNames.forEach { add(buildJsonObject { put("displayName", it) }) } })
        }
    }

    private fun focusDiagnostic(arguments: JsonObject): IjMcpToolCallResult {
        val path = arguments.stringValue("path") ?: return unavailable("diagnostic_not_found", "A file path is required to focus diagnostics.")
        return gotoFile(buildJsonObject { put("path", path) })
    }

    private fun listVcsRoots(): IjMcpToolCallResult {
        val roots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
        return success("Listed VCS roots.") {
            put("totalReturned", roots.size)
            put(
                "items",
                buildJsonArray {
                    roots.forEach { root ->
                        add(
                            buildJsonObject {
                                put("path", relativePath(root.path))
                                put("absolutePath", root.path.presentableUrl)
                                put("vcsName", root.vcs?.name ?: "")
                            },
                        )
                    }
                },
            )
        }
    }

    private fun getCurrentBranch(): IjMcpToolCallResult {
        val basePath = project.basePath ?: return unavailable("vcs_unavailable", "Project base path is not available.")
        val head = runCatching { Files.readString(Path.of(basePath, ".git", "HEAD")).trim() }.getOrNull()
            ?: return unavailable("branch_not_found", "No Git HEAD file was found for the project root.")
        val branch = head.removePrefix("ref: refs/heads/").takeIf { it != head }
        return success("Read current branch.") {
            put("details", buildJsonObject { put("branch", branch ?: head) })
        }
    }

    private fun listChangedFiles(): IjMcpToolCallResult {
        val changes = ChangeListManager.getInstance(project).allChanges
        return success("Listed changed files.") {
            put("totalReturned", changes.size)
            put(
                "items",
                buildJsonArray {
                    changes.forEach { change ->
                        val file = change.virtualFile ?: change.afterRevision?.file?.virtualFile ?: change.beforeRevision?.file?.virtualFile
                        add(
                            buildJsonObject {
                                put("status", change.type.name)
                                file?.let {
                                    put("path", relativePath(it))
                                    put("absolutePath", it.presentableUrl)
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun getIdeContext(): IjMcpToolCallResult = success("Read IDE context.") {
        activeFile()?.let {
            put("path", relativePath(it))
            put("absolutePath", it.presentableUrl)
        }
        activeToolWindowId()?.let { put("id", it) }
        put("details", projectContextJson())
    }

    private fun getUiFocusContext(): IjMcpToolCallResult = success("Read UI focus context.") {
        put(
            "details",
            buildJsonObject {
                activeToolWindowId()?.let { put("activeToolWindowId", it) }
                activeFile()?.let { put("activeEditorPath", relativePath(it)) }
            },
        )
    }

    private fun listOpenProjects(): IjMcpToolCallResult {
        val projects = ProjectManager.getInstance().openProjects.toList()
        return success("Listed open projects.") {
            put("totalReturned", projects.size)
            put(
                "items",
                buildJsonArray {
                    projects.forEach { openProject ->
                        add(
                            buildJsonObject {
                                put("name", openProject.name)
                                put("path", openProject.basePath.orEmpty())
                                put("isCurrent", openProject == project)
                            },
                        )
                    }
                },
            )
        }
    }

    private fun getProjectContext(): IjMcpToolCallResult = success("Read project context.") {
        put("details", projectContextJson())
    }

    private fun getMcpServerStatus(): IjMcpToolCallResult {
        val status = statusProvider()
            ?: return unavailable("context_unavailable", "IJ-MCP server status is not available in this handler context.")
        return success("Read MCP server status.") {
            put(
                "details",
                buildJsonObject {
                    put("targetId", status.descriptor.targetId)
                    put("projectName", status.descriptor.projectName)
                    put("projectPath", status.descriptor.projectPath)
                    put("running", status.running)
                    put("port", status.port)
                    put("endpointUrl", status.endpointUrl)
                    put("requiresPairing", status.requiresPairing)
                    status.lastError?.let { put("lastError", it) }
                },
            )
        }
    }

    private fun openToolWindow(
        id: String,
        errorCode: String,
        details: JsonObject = buildJsonObject {},
    ): IjMcpToolCallResult = openFirstToolWindow(listOf(id), errorCode, details)

    private fun openFirstToolWindow(
        ids: List<String>,
        errorCode: String,
        details: JsonObject = buildJsonObject {},
    ): IjMcpToolCallResult {
        var openedId: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            val manager = ToolWindowManager.getInstance(project)
            val toolWindow = ids.firstNotNullOfOrNull { id -> manager.getToolWindow(id)?.let { id to it } }
            if (toolWindow != null && toolWindow.second.isAvailable) {
                openedId = toolWindow.first
                toolWindow.second.activate(null, true)
            }
        }
        val id = openedId ?: return unavailable(errorCode, "None of these tool windows are available: ${ids.joinToString()}.")
        return success("Opened tool window $id.") {
            put("id", id)
            if (details.isNotEmpty()) {
                put("details", details)
            }
        }
    }

    private fun hasToolWindow(id: String): Boolean {
        var available = false
        ApplicationManager.getApplication().invokeAndWait {
            available = ToolWindowManager.getInstance(project).getToolWindow(id) != null
        }
        return available
    }

    private fun activeToolWindowId(): String? {
        var id: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            id = ToolWindowManager.getInstance(project).activeToolWindowId
        }
        return id
    }

    private fun activeFile(): VirtualFile? {
        var file: VirtualFile? = null
        ApplicationManager.getApplication().invokeAndWait {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        }
        return file
    }

    private fun resolveStructureFile(arguments: JsonObject): VirtualFile? =
        arguments.stringValue("path")?.let(::resolveFileOnly) ?: activeFile()

    private fun findFileByQuery(query: String): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
        val scope = GlobalSearchScope.projectScope(project)
        FilenameIndex.getVirtualFilesByName(query, scope).firstOrNull()
            ?: run {
                var match: VirtualFile? = null
                FilenameIndex.processAllFileNames({ name ->
                    if (name.contains(query, ignoreCase = true)) {
                        match = FilenameIndex.getVirtualFilesByName(name, scope).firstOrNull()
                        false
                    } else {
                        true
                    }
                }, scope, null)
                match
            }
    }

    private fun findNamedElement(query: String): PsiNamedElement? = ReadAction.compute<PsiNamedElement?, RuntimeException> {
        val cache = PsiShortNamesCache.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        cache.getClassesByName(query, scope).firstOrNull()
            ?: cache.getMethodsByName(query, scope).firstOrNull()
            ?: cache.getFieldsByName(query, scope).firstOrNull()
    }

    private fun structureNodes(
        file: VirtualFile,
        limit: Int,
    ): List<PsiNamedElement> = ReadAction.compute<List<PsiNamedElement>, RuntimeException> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute emptyList()
        val nodes = mutableListOf<PsiNamedElement>()
        psiFile.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (nodes.size >= limit) {
                        stopWalking()
                        return
                    }
                    if (element is PsiNamedElement && !element.name.isNullOrBlank() && element != psiFile) {
                        nodes += element
                    }
                    super.visitElement(element)
                }
            },
        )
        nodes
    }

    private fun openFile(file: VirtualFile) {
        ApplicationManager.getApplication().invokeAndWait {
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    private fun openElement(element: PsiElement) {
        ApplicationManager.getApplication().invokeAndWait {
            val file = element.containingFile?.virtualFile ?: return@invokeAndWait
            val offset = element.textOffset.coerceAtLeast(0)
            OpenFileDescriptor(project, file, offset).navigate(true)
        }
    }

    private fun resolveFileOnly(path: String): VirtualFile? = resolveVirtualFile(path, allowDirectory = false)

    private fun resolveVirtualFile(
        rawPath: String?,
        allowDirectory: Boolean,
    ): VirtualFile? = ReadAction.compute<VirtualFile?, RuntimeException> {
        val root = projectRoot() ?: return@compute null
        val raw = rawPath?.trim().orEmpty().ifBlank { "." }
        val nioPath = if (Path.of(raw).isAbsolute) Path.of(raw).normalize() else Path.of(root.path, raw).normalize()
        val file = LocalFileSystem.getInstance().findFileByNioFile(nioPath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
            ?: return@compute null
        if (!allowDirectory && file.isDirectory) {
            return@compute null
        }
        val relativePath = VfsUtilCore.getRelativePath(file, root, '/')
        if (file != root && relativePath == null) {
            return@compute null
        }
        file
    }

    private fun projectRoot(): VirtualFile? {
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(basePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
    }

    private fun gradleBuildFiles(root: VirtualFile): List<VirtualFile> {
        val results = mutableListOf<VirtualFile>()
        fun visit(file: VirtualFile, depth: Int) {
            if (depth > 4 || results.size >= 100) return
            if (!file.isDirectory) {
                if (file.name == "build.gradle" || file.name == "build.gradle.kts" || file.name == "settings.gradle" || file.name == "settings.gradle.kts") {
                    results += file
                }
                return
            }
            if (file.name == ".gradle" || file.name == "build") return
            file.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return results
    }

    private fun VirtualFile.nodeJson(): JsonObject = buildJsonObject {
        put("displayName", name)
        put("path", relativePath(this@nodeJson))
        put("absolutePath", presentableUrl)
        put("isDirectory", isDirectory)
    }

    private fun PsiElement.elementJson(): JsonObject {
        val file = containingFile?.virtualFile
        val document = containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val line = document?.getLineNumber(textOffset)?.plus(1)
        val column = document?.getLineStartOffset((line ?: 1) - 1)?.let { textOffset - it + 1 }
        return buildJsonObject {
            put("symbolName", (this@elementJson as? PsiNamedElement)?.name.orEmpty())
            file?.let {
                put("path", relativePath(it))
                put("absolutePath", it.presentableUrl)
            }
            line?.let { put("line", it) }
            column?.let { put("column", it) }
        }
    }

    private fun projectContextJson(): JsonObject = buildJsonObject {
        put("name", project.name)
        put("basePath", project.basePath.orEmpty())
        put("isDisposed", project.isDisposed)
    }

    private fun relativePath(file: VirtualFile): String {
        val root = projectRoot()
        return if (root != null) {
            VfsUtilCore.getRelativePath(file, root, '/') ?: file.path
        } else {
            file.path
        }
    }

    private fun success(
        message: String,
        content: JsonObjectBuilder.() -> Unit = {},
    ): IjMcpToolCallResult {
        val structuredContent = buildJsonObject {
            put("status", "success")
            put("message", message)
            put("projectName", project.name)
            content()
        }
        return IjMcpToolResults.success(
            contentText = message,
            structuredContent = structuredContent,
        )
    }

    private fun unavailable(
        errorCode: String,
        message: String,
    ): IjMcpToolCallResult = IjMcpToolResults.error(errorCode = errorCode, message = message)

    private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.limitValue(): Int {
        val value = (this["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 50
        return value.coerceIn(1, 100)
    }

    private companion object {
        val handledToolNames = listOf(
            "list_project_view_roots",
            "list_project_view_children",
            "get_project_view_selection",
            "select_project_view_node",
            "expand_project_view_node",
            "collapse_project_view_node",
            "open_gradle_tool_window",
            "list_gradle_projects",
            "list_gradle_tasks",
            "reveal_gradle_project",
            "focus_gradle_task",
            "get_gradle_sync_status",
            "goto_file",
            "goto_symbol",
            "goto_declaration",
            "goto_implementation",
            "find_usages",
            "get_structure_view",
            "list_structure_nodes",
            "focus_structure_node",
            "expand_structure_node",
            "collapse_structure_node",
            "goto_structure_symbol",
            "list_run_configurations",
            "select_run_configuration",
            "get_active_run_session",
            "open_run_tool_window",
            "open_debug_tool_window",
            "list_services",
            "focus_service_node",
            "open_problems_tool_window",
            "list_problems",
            "focus_problem",
            "list_file_diagnostics",
            "list_inspection_results",
            "focus_diagnostic",
            "list_vcs_roots",
            "get_current_branch",
            "list_changed_files",
            "focus_changed_file",
            "open_vcs_log",
            "open_commit_tool_window",
            "reveal_change_in_vcs",
            "get_ide_context",
            "get_ui_focus_context",
            "list_open_projects",
            "get_project_context",
            "get_mcp_server_status",
        )
    }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder
