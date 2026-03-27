package ai.plyxal.ijmcp.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service

internal interface IjMcpTargetCredentialStore {
    fun loadTargetToken(targetId: String): String?

    fun storeTargetToken(targetId: String, token: String?)
}

@Service(Service.Level.APP)
class IjMcpSecretStore : IjMcpTargetCredentialStore {
    private val legacyCredentialAttributes = CredentialAttributes("IJ-MCP Local Bearer Token")

    internal fun loadLegacyToken(): String? = PasswordSafe.instance
        .getPassword(legacyCredentialAttributes)
        ?.takeIf(String::isNotBlank)

    internal fun hasLegacyToken(): Boolean = !loadLegacyToken().isNullOrBlank()

    internal fun storeLegacyToken(token: String?) {
        PasswordSafe.instance.setPassword(
            legacyCredentialAttributes,
            token?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    override fun loadTargetToken(targetId: String): String? = PasswordSafe.instance
        .getPassword(targetCredentialAttributes(targetId))
        ?.takeIf(String::isNotBlank)

    override fun storeTargetToken(targetId: String, token: String?) {
        PasswordSafe.instance.setPassword(
            targetCredentialAttributes(targetId),
            token?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    internal fun hasStoredToken(): Boolean = hasLegacyToken()

    internal fun loadToken(): String? = loadLegacyToken()

    internal fun storeToken(token: String?) {
        storeLegacyToken(token)
    }

    private fun targetCredentialAttributes(targetId: String): CredentialAttributes = CredentialAttributes(
        "IJ-MCP Target Bearer Token",
        targetId,
    )
}
