package ai.plyxal.ijmcp.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class IjMcpSecretStore {
    private val credentialAttributes = CredentialAttributes("IJ-MCP Local Bearer Token")

    internal fun loadToken(): String? = PasswordSafe.instance.getPassword(credentialAttributes)?.takeIf(String::isNotBlank)

    internal fun hasStoredToken(): Boolean = !loadToken().isNullOrBlank()

    internal fun storeToken(token: String?) {
        val normalizedToken = token?.trim()?.takeIf { it.isNotBlank() }

        PasswordSafe.instance.setPassword(
            credentialAttributes,
            normalizedToken,
        )
    }
}
