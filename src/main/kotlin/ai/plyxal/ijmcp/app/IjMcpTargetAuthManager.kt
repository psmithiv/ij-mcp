package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpPairingExchangeResult
import ai.plyxal.ijmcp.mcp.IjMcpServerSecurity
import ai.plyxal.ijmcp.settings.IjMcpTargetCredentialStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class IjMcpTargetAuthManager(
    private val targetId: String,
    private val credentialStore: IjMcpTargetCredentialStore,
    private val clock: Clock = Clock.systemUTC(),
    private val codeTtl: Duration = Duration.ofMinutes(10),
    private val tokenFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "")
    },
    private val pairingCodeFactory: () -> String = {
        UUID.randomUUID().toString().replace("-", "").take(8)
    },
) : IjMcpServerSecurity {
    private val lock = Any()
    private var activePairingCode: ActivePairingCode? = null

    fun bootstrapLegacyToken(legacyToken: String?) {
        synchronized(lock) {
            if (credentialStore.loadTargetToken(targetId).isNullOrBlank()) {
                credentialStore.storeTargetToken(targetId, legacyToken)
            }
        }
    }

    fun issuePairingCode(): IssuedPairingCode = synchronized(lock) {
        val code = pairingCodeFactory()
        val expiresAt = Instant.now(clock).plus(codeTtl)
        activePairingCode = ActivePairingCode(
            code = code,
            expiresAt = expiresAt,
        )
        IssuedPairingCode(
            code = code,
            expiresAt = expiresAt,
        )
    }

    fun reset() {
        synchronized(lock) {
            activePairingCode = null
            credentialStore.storeTargetToken(targetId, null)
        }
    }

    override fun isAuthorized(authorizationHeader: String?): Boolean {
        val expectedToken = credentialStore.loadTargetToken(targetId) ?: return false
        return authorizationHeader == "Bearer $expectedToken"
    }

    override fun requiresPairing(): Boolean = credentialStore.loadTargetToken(targetId).isNullOrBlank()

    override fun exchangePairingCode(pairingCode: String): IjMcpPairingExchangeResult = synchronized(lock) {
        val activeCode = activePairingCode
            ?: return@synchronized IjMcpPairingExchangeResult.Failure(
                errorCode = "pairing_not_available",
                message = "No pairing code is currently active for this target.",
                statusCode = 409,
            )

        val now = Instant.now(clock)
        if (now.isAfter(activeCode.expiresAt)) {
            activePairingCode = null
            return@synchronized IjMcpPairingExchangeResult.Failure(
                errorCode = "pairing_code_expired",
                message = "The supplied pairing code has expired.",
                statusCode = 401,
            )
        }

        if (activeCode.code != pairingCode.trim()) {
            return@synchronized IjMcpPairingExchangeResult.Failure(
                errorCode = "pairing_code_invalid",
                message = "The supplied pairing code is invalid.",
                statusCode = 401,
            )
        }

        activePairingCode = null
        val bearerToken = tokenFactory()
        credentialStore.storeTargetToken(targetId, bearerToken)

        IjMcpPairingExchangeResult.Success(bearerToken = bearerToken)
    }
}

internal data class IssuedPairingCode(
    val code: String,
    val expiresAt: Instant,
)

private data class ActivePairingCode(
    val code: String,
    val expiresAt: Instant,
)
