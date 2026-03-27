package ai.plyxal.ijmcp.ide

import ai.plyxal.ijmcp.mcp.IjMcpToolCallResult
import ai.plyxal.ijmcp.mcp.IjMcpToolCatalog
import ai.plyxal.ijmcp.mcp.IjMcpToolHandler
import ai.plyxal.ijmcp.mcp.IjMcpToolResults
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class IjMcpSearchToolHandlers(
    private val project: Project,
    private val pathResolver: IjMcpPathResolver = IjMcpPathResolver(),
) {
    fun all(): List<IjMcpToolHandler> = listOf(
        SearchFilesToolHandler(),
        SearchSymbolsToolHandler(),
    )

    private inner class SearchFilesToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("search_files")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val query = arguments.stringValue("query")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty query is required.",
                )
            val limit = arguments.limitValue()
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Limit must be an integer between 1 and 100.",
                )

            val results = ReadAction.compute<List<IjMcpFileSearchResult>, RuntimeException> {
                val scope = GlobalSearchScope.projectScope(project)
                val candidateNames = linkedMapOf<String, IjMcpSearchMatchKind>()

                FilenameIndex.processAllFileNames({ name ->
                    val matchKind = IjMcpSearchMatchKind.fromCandidate(name, query) ?: return@processAllFileNames true
                    val existing = candidateNames[name]
                    if (existing == null || matchKind.rank < existing.rank) {
                        candidateNames[name] = matchKind
                    }
                    true
                }, scope, null)

                val orderedCandidates = candidateNames.entries
                    .sortedWith(
                        compareBy<Map.Entry<String, IjMcpSearchMatchKind>>(
                            { it.value.rank },
                            { it.key.lowercase() },
                            { it.key },
                        ),
                    )

                val collectedResults = linkedMapOf<String, IjMcpFileSearchResult>()
                for ((name, matchKind) in orderedCandidates) {
                    for (file in FilenameIndex.getVirtualFilesByName(name, scope)) {
                        when (val resolved = pathResolver.describe(project, file)) {
                            is IjMcpResolvedFileResult.Success -> {
                                collectedResults.putIfAbsent(
                                    resolved.resolvedFile.absolutePath,
                                    IjMcpFileSearchResult(
                                        displayName = resolved.resolvedFile.file.name,
                                        path = resolved.resolvedFile.path,
                                        absolutePath = resolved.resolvedFile.absolutePath,
                                        matchKind = matchKind.wireValue,
                                    ),
                                )
                            }

                            is IjMcpResolvedFileResult.Failure -> Unit
                        }

                        if (collectedResults.size >= limit) {
                            return@compute collectedResults.values.take(limit)
                        }
                    }
                }

                collectedResults.values.take(limit)
            }

            if (results.isEmpty()) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "file_not_found",
                    message = "No project files matched query \"$query\".",
                )
            }

            IjMcpToolResults.success(
                contentText = "Found ${results.size} files matching \"$query\".",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Found matching files.")
                    put("projectName", project.name)
                    put("query", query)
                    put("totalReturned", results.size)
                    put(
                        "results",
                        buildJsonArray {
                            results.forEach { result ->
                                add(
                                    buildJsonObject {
                                        put("displayName", result.displayName)
                                        put("path", result.path)
                                        put("absolutePath", result.absolutePath)
                                        put("matchKind", result.matchKind)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private inner class SearchSymbolsToolHandler : IjMcpToolHandler {
        override val descriptor = IjMcpToolCatalog.descriptor("search_symbols")

        override fun call(arguments: JsonObject): IjMcpToolCallResult = withProject { project ->
            val query = arguments.stringValue("query")
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "A non-empty query is required.",
                )
            val limit = arguments.limitValue()
                ?: return@withProject IjMcpToolResults.error(
                    errorCode = "invalid_tool_arguments",
                    message = "Limit must be an integer between 1 and 100.",
                )

            val results = ReadAction.compute<List<IjMcpSymbolSearchResult>, RuntimeException> {
                val cache = PsiShortNamesCache.getInstance(project)
                val scope = GlobalSearchScope.projectScope(project)
                val candidateNames = collectSymbolCandidateNames(cache, scope, query)
                val collectedResults = linkedMapOf<String, IjMcpSymbolSearchResult>()

                fun addResult(element: PsiElement, symbolName: String, containerName: String?): Boolean {
                    val result = buildSymbolSearchResult(project, element, symbolName, containerName) ?: return true
                    val key = buildString {
                        append(result.symbolName)
                        append('|')
                        append(result.absolutePath)
                        append('|')
                        append(result.line ?: 0)
                        append('|')
                        append(result.column ?: 0)
                        append('|')
                        append(result.containerName.orEmpty())
                    }
                    collectedResults.putIfAbsent(key, result)
                    return collectedResults.size < limit
                }

                for (candidate in candidateNames) {
                    cache.processClassesWithName(
                        candidate.name,
                        { psiClass -> addResult(psiClass, psiClass.name ?: candidate.name, containerName(psiClass)) },
                        scope,
                        null,
                    )
                    if (collectedResults.size >= limit) {
                        return@compute collectedResults.values.take(limit)
                    }

                    cache.processMethodsWithName(
                        candidate.name,
                        { psiMethod -> addResult(psiMethod, psiMethod.name, containerName(psiMethod)) },
                        scope,
                        null,
                    )
                    if (collectedResults.size >= limit) {
                        return@compute collectedResults.values.take(limit)
                    }

                    cache.processFieldsWithName(
                        candidate.name,
                        { psiField -> addResult(psiField, psiField.name, containerName(psiField)) },
                        scope,
                        null,
                    )
                    if (collectedResults.size >= limit) {
                        return@compute collectedResults.values.take(limit)
                    }
                }

                collectedResults.values.take(limit)
            }

            if (results.isEmpty()) {
                return@withProject IjMcpToolResults.error(
                    errorCode = "symbol_not_found",
                    message = "No project symbols matched query \"$query\".",
                )
            }

            IjMcpToolResults.success(
                contentText = "Found ${results.size} symbols matching \"$query\".",
                structuredContent = buildJsonObject {
                    put("status", "success")
                    put("message", "Found matching symbols.")
                    put("projectName", project.name)
                    put("query", query)
                    put("totalReturned", results.size)
                    put(
                        "results",
                        buildJsonArray {
                            results.forEach { result ->
                                add(
                                    buildJsonObject {
                                        put("symbolName", result.symbolName)
                                        result.containerName?.let { put("containerName", it) }
                                        put("path", result.path)
                                        put("absolutePath", result.absolutePath)
                                        result.line?.let { put("line", it) }
                                        result.column?.let { put("column", it) }
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
    }

    private fun withProject(action: (project: Project) -> IjMcpToolCallResult): IjMcpToolCallResult = action(project)

    private fun collectSymbolCandidateNames(
        cache: PsiShortNamesCache,
        scope: GlobalSearchScope,
        query: String,
    ): List<IjMcpSymbolCandidate> {
        val candidates = linkedMapOf<String, IjMcpSearchMatchKind>()

        fun addCandidate(name: String): Boolean {
            val matchKind = IjMcpSearchMatchKind.fromCandidate(name, query) ?: return true
            val existing = candidates[name]
            if (existing == null || matchKind.rank < existing.rank) {
                candidates[name] = matchKind
            }
            return true
        }

        cache.processAllClassNames(::addCandidate, scope, null)
        cache.processAllMethodNames(::addCandidate, scope, null)
        cache.processAllFieldNames(::addCandidate, scope, null)

        return candidates.entries
            .sortedWith(
                compareBy<Map.Entry<String, IjMcpSearchMatchKind>>(
                    { it.value.rank },
                    { it.key.lowercase() },
                    { it.key },
                ),
            )
            .map { IjMcpSymbolCandidate(name = it.key, matchKind = it.value) }
    }

    private fun buildSymbolSearchResult(
        project: Project,
        element: PsiElement,
        symbolName: String,
        containerName: String?,
    ): IjMcpSymbolSearchResult? {
        val navigationElement = element.navigationElement.takeIf { it.isValid } ?: element
        val containingFile = navigationElement.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val resolvedFile = when (val result = pathResolver.describe(project, virtualFile)) {
            is IjMcpResolvedFileResult.Success -> result.resolvedFile
            is IjMcpResolvedFileResult.Failure -> return null
        }

        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
        val textOffset = navigationElement.textOffset
        val location = if (document != null && textOffset >= 0) {
            val safeOffset = textOffset.coerceAtMost(document.textLength.coerceAtLeast(1) - 1)
            val line = document.getLineNumber(safeOffset)
            val lineStart = document.getLineStartOffset(line)
            IjMcpSymbolLocation(
                line = line + 1,
                column = (safeOffset - lineStart) + 1,
            )
        } else {
            null
        }

        return IjMcpSymbolSearchResult(
            symbolName = symbolName,
            containerName = containerName,
            path = resolvedFile.path,
            absolutePath = resolvedFile.absolutePath,
            line = location?.line,
            column = location?.column,
        )
    }

    private fun containerName(element: PsiClass): String? = element.qualifiedName
        ?.substringBeforeLast('.', "")
        ?.takeIf { it.isNotEmpty() }

    private fun containerName(element: PsiMethod): String? = element.containingClass?.qualifiedName
        ?: element.containingClass?.name

    private fun containerName(element: PsiField): String? = element.containingClass?.qualifiedName
        ?: element.containingClass?.name

    private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.limitValue(default: Int = 20): Int? {
        val primitive = this["limit"] as? JsonPrimitive ?: return default
        val parsed = primitive.content.toIntOrNull() ?: return null
        return parsed.takeIf { it in 1..100 }
    }
}

private enum class IjMcpSearchMatchKind(
    val wireValue: String,
    val rank: Int,
) {
    EXACT("exact", 0),
    PREFIX("prefix", 1),
    SUBSTRING("substring", 2),
    OTHER("other", 3),
    ;

    companion object {
        fun fromCandidate(candidate: String, query: String): IjMcpSearchMatchKind? {
            val normalizedCandidate = candidate.lowercase()
            val normalizedQuery = query.lowercase()

            return when {
                normalizedCandidate == normalizedQuery -> EXACT
                normalizedCandidate.startsWith(normalizedQuery) -> PREFIX
                normalizedCandidate.contains(normalizedQuery) -> SUBSTRING
                else -> null
            }
        }
    }
}

private data class IjMcpFileSearchResult(
    val displayName: String,
    val path: String,
    val absolutePath: String,
    val matchKind: String,
)

private data class IjMcpSymbolCandidate(
    val name: String,
    val matchKind: IjMcpSearchMatchKind,
)

private data class IjMcpSymbolSearchResult(
    val symbolName: String,
    val containerName: String?,
    val path: String,
    val absolutePath: String,
    val line: Int?,
    val column: Int?,
)

private data class IjMcpSymbolLocation(
    val line: Int,
    val column: Int,
)
