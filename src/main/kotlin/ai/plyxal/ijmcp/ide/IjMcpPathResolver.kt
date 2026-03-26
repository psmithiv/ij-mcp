package ai.plyxal.ijmcp.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

internal class IjMcpPathResolver {
    fun resolveFile(
        project: Project,
        rawPath: String,
    ): IjMcpResolvedFileResult = ReadAction.compute<IjMcpResolvedFileResult, RuntimeException> {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.isEmpty()) {
            return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "invalid_tool_arguments",
                message = "A non-empty path is required.",
            )
        }

        val projectDir = project.guessProjectDir()
            ?: return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "project_not_available",
                message = "The active project does not have a resolvable base directory.",
            )

        val localFileSystem = LocalFileSystem.getInstance()
        val nioPath = try {
            val candidate = Paths.get(trimmedPath)
            if (candidate.isAbsolute) {
                candidate.normalize()
            } else {
                Paths.get(projectDir.path, trimmedPath).normalize()
            }
        } catch (_: InvalidPathException) {
            return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "invalid_tool_arguments",
                message = "The supplied path is invalid: $rawPath",
            )
        }

        val file = localFileSystem.findFileByNioFile(nioPath)
            ?: localFileSystem.refreshAndFindFileByNioFile(nioPath)
            ?: return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "file_not_found",
                message = "No project file exists at $trimmedPath.",
            )

        if (file.isDirectory) {
            return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "file_not_found",
                message = "The supplied path points to a directory, not a file.",
            )
        }

        return@compute describe(project, file)
    }

    fun describe(
        project: Project,
        file: VirtualFile,
    ): IjMcpResolvedFileResult = ReadAction.compute<IjMcpResolvedFileResult, RuntimeException> {
        val projectDir = project.guessProjectDir()
            ?: return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "project_not_available",
                message = "The active project does not have a resolvable base directory.",
            )

        if (!ProjectFileIndex.getInstance(project).isInContent(file)) {
            return@compute IjMcpResolvedFileResult.Failure(
                errorCode = "outside_project",
                message = "The supplied file is outside the active project boundary.",
            )
        }

        val relativePath = VfsUtilCore.getRelativePath(file, projectDir, '/')
            ?: file.path.removePrefix("${projectDir.path}/")

        IjMcpResolvedFileResult.Success(
            IjMcpResolvedFile(
                project = project,
                projectName = project.name,
                file = file,
                path = relativePath,
                absolutePath = nioPathString(file.path),
            ),
        )
    }

    private fun Project.guessProjectDir(): VirtualFile? {
        val basePath = basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath(basePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
    }

    private fun nioPathString(path: String): String = Path.of(path).normalize().toString()
}

internal data class IjMcpResolvedFile(
    val project: Project,
    val projectName: String,
    val file: VirtualFile,
    val path: String,
    val absolutePath: String,
)

internal sealed interface IjMcpResolvedFileResult {
    data class Success(
        val resolvedFile: IjMcpResolvedFile,
    ) : IjMcpResolvedFileResult

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : IjMcpResolvedFileResult
}
