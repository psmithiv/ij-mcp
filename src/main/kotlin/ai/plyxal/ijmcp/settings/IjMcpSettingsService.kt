package ai.plyxal.ijmcp.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "IjMcpSettings",
    storages = [Storage("ijmcp.xml")],
)
class IjMcpSettingsService : PersistentStateComponent<IjMcpSettingsState> {
    private var state = IjMcpSettingsState()

    override fun getState(): IjMcpSettingsState = state

    override fun loadState(state: IjMcpSettingsState) {
        this.state = state
    }

    internal fun snapshot(): IjMcpSettingsState = state.copy()

    internal fun update(
        enabled: Boolean,
        port: Int,
        autoTrustLocalClients: Boolean,
        manageCodexConfig: Boolean,
    ) {
        state = state.copy(
            enabled = enabled,
            port = port,
            autoTrustLocalClients = autoTrustLocalClients,
            manageCodexConfig = manageCodexConfig,
        )
    }
}
