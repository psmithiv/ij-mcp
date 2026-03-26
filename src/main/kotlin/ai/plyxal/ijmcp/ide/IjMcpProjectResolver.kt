package ai.plyxal.ijmcp.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

internal class IjMcpProjectResolver {
    fun resolveActiveProject(): IjMcpProjectResolution {
        val openProjects = ProjectManager.getInstance().openProjects

        return when {
            openProjects.isEmpty() -> IjMcpProjectResolution.Failure(
                errorCode = "project_not_available",
                message = "No open IntelliJ project is available.",
            )

            openProjects.size == 1 -> IjMcpProjectResolution.Success(openProjects.first())

            else -> IjMcpProjectResolution.Failure(
                errorCode = "ambiguous_project",
                message = "Multiple IntelliJ projects are open and the plugin cannot choose one implicitly.",
            )
        }
    }
}

internal sealed interface IjMcpProjectResolution {
    data class Success(
        val project: Project,
    ) : IjMcpProjectResolution

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : IjMcpProjectResolution
}
