package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpPairingExchangeResult
import ai.plyxal.ijmcp.settings.IjMcpTargetCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class IjMcpTargetAuthManagerTest {
    @Test
    fun pairingExchangeCreatesAndAuthorizesATargetToken() {
        val store = InMemoryTargetCredentialStore()
        val authManager = IjMcpTargetAuthManager(
            targetId = "target-a",
            credentialStore = store,
            tokenFactory = { "issued-token" },
            pairingCodeFactory = { "PAIR1234" },
        )

        val issuedPairingCode = authManager.issuePairingCode()
        val exchange = authManager.exchangePairingCode(issuedPairingCode.code)

        assertTrue(exchange is IjMcpPairingExchangeResult.Success)
        assertEquals("issued-token", (exchange as IjMcpPairingExchangeResult.Success).bearerToken)
        assertEquals("issued-token", store.loadTargetToken("target-a"))
        assertFalse(authManager.requiresPairing())
        assertTrue(authManager.isAuthorized("Bearer issued-token"))
    }

    @Test
    fun invalidOrExpiredPairingCodesFailWithoutIssuingTokens() {
        val clock = MutableClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = InMemoryTargetCredentialStore()
        val authManager = IjMcpTargetAuthManager(
            targetId = "target-a",
            credentialStore = store,
            clock = clock,
            codeTtl = Duration.ofSeconds(30),
            tokenFactory = { "issued-token" },
            pairingCodeFactory = { "PAIR1234" },
        )

        authManager.issuePairingCode()
        val invalidExchange = authManager.exchangePairingCode("WRONG123")
        clock.advance(Duration.ofSeconds(31))
        val expiredExchange = authManager.exchangePairingCode("PAIR1234")

        assertTrue(invalidExchange is IjMcpPairingExchangeResult.Failure)
        assertEquals("pairing_code_invalid", (invalidExchange as IjMcpPairingExchangeResult.Failure).errorCode)
        assertTrue(expiredExchange is IjMcpPairingExchangeResult.Failure)
        assertEquals("pairing_code_expired", (expiredExchange as IjMcpPairingExchangeResult.Failure).errorCode)
        assertTrue(store.loadTargetToken("target-a").isNullOrBlank())
        assertTrue(authManager.requiresPairing())
    }

    @Test
    fun resetClearsTargetScopedCredentials() {
        val store = InMemoryTargetCredentialStore()
        val authManager = IjMcpTargetAuthManager(
            targetId = "target-a",
            credentialStore = store,
            tokenFactory = { "issued-token" },
            pairingCodeFactory = { "PAIR1234" },
        )

        authManager.bootstrapLegacyToken("legacy-token")
        assertTrue(authManager.isAuthorized("Bearer legacy-token"))

        authManager.reset()

        assertFalse(authManager.isAuthorized("Bearer legacy-token"))
        assertTrue(authManager.requiresPairing())
    }

    @Test
    fun targetScopedTokensAreIsolatedAcrossTargets() {
        val store = InMemoryTargetCredentialStore()
        val targetA = IjMcpTargetAuthManager(
            targetId = "target-a",
            credentialStore = store,
            tokenFactory = { "token-a" },
            pairingCodeFactory = { "PAIRA123" },
        )
        val targetB = IjMcpTargetAuthManager(
            targetId = "target-b",
            credentialStore = store,
            tokenFactory = { "token-b" },
            pairingCodeFactory = { "PAIRB123" },
        )

        targetA.issuePairingCode()
        targetB.issuePairingCode()
        targetA.exchangePairingCode("PAIRA123")
        targetB.exchangePairingCode("PAIRB123")

        assertTrue(targetA.isAuthorized("Bearer token-a"))
        assertFalse(targetA.isAuthorized("Bearer token-b"))
        assertTrue(targetB.isAuthorized("Bearer token-b"))
        assertFalse(targetB.isAuthorized("Bearer token-a"))
    }
}

private class InMemoryTargetCredentialStore : IjMcpTargetCredentialStore {
    private val tokens = mutableMapOf<String, String>()

    override fun loadTargetToken(targetId: String): String? = tokens[targetId]

    override fun storeTargetToken(targetId: String, token: String?) {
        if (token.isNullOrBlank()) {
            tokens.remove(targetId)
        } else {
            tokens[targetId] = token
        }
    }
}

private class MutableClock(
    private var currentInstant: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
