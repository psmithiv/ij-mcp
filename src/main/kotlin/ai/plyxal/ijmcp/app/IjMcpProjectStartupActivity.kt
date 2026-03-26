package ai.plyxal.ijmcp.app

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class IjMcpProjectStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            service<IjMcpAppService>().applyConfiguredState()
        }
    }
}
