package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IjMcpPairingMessagingTest {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)

    @Test
    fun runningTargetWithActiveCodeShowsSingleUsePairingWorkflow() {
        val messaging = IjMcpPairingMessaging(
            dateFormatter = formatter,
            nowProvider = { Instant.parse("2026-04-06T18:00:00Z") },
        )
        val issuedCode = IssuedPairingCode(
            code = "PAIR1234",
            expiresAt = Instant.parse("2026-04-06T18:10:00Z"),
        )

        val workflow = messaging.pairingWorkflow(requiresPairingStatus(), issuedCode)
        val expiry = messaging.codeExpiry(issuedCode)

        assertTrue(workflow.contains("`targets pair --code <pairingCode> target-a`"))
        assertTrue(workflow.contains("2026-04-06T18:10:00Z"))
        assertEquals("Pairing code: single use until 2026-04-06T18:10:00Z", expiry)
    }

    @Test
    fun pairedTargetExplainsThatExistingClientsRemainAuthorizedUntilReset() {
        val messaging = IjMcpPairingMessaging(
            dateFormatter = formatter,
            nowProvider = { Instant.parse("2026-04-06T18:00:00Z") },
        )

        val workflow = messaging.pairingWorkflow(
            requiresPairingStatus().copy(requiresPairing = false),
            issuedCode = null,
        )
        val resetImpact = messaging.resetImpact(
            requiresPairingStatus().copy(requiresPairing = false),
            issuedCode = null,
        )

        assertTrue(workflow.contains("Existing CLI and gateway sessions keep working until you reset access."))
        assertTrue(resetImpact.contains("forces every client to pair again"))
    }

    @Test
    fun expiredCodeFallsBackToNoActiveCodeMessaging() {
        val messaging = IjMcpPairingMessaging(
            dateFormatter = formatter,
            nowProvider = { Instant.parse("2026-04-06T18:30:00Z") },
        )
        val expiredCode = IssuedPairingCode(
            code = "PAIR1234",
            expiresAt = Instant.parse("2026-04-06T18:10:00Z"),
        )

        assertTrue(messaging.codeExpiry(expiredCode).contains("no active code"))
        assertTrue(messaging.pairingWorkflow(requiresPairingStatus(), expiredCode).contains("Generate a one-time code"))
    }

    private fun requiresPairingStatus(): IjMcpTargetStatus = IjMcpTargetStatus(
        descriptor = IjMcpTargetDescriptor(
            targetId = "target-a",
            ideInstanceId = "ide-a",
            pid = 1234,
            productCode = "IC",
            productName = "IntelliJ IDEA Community Edition",
            projectName = "ij-mcp",
            projectPath = "/tmp/ij-mcp",
        ),
        running = true,
        port = 8765,
        endpointUrl = "http://127.0.0.1:8765/mcp",
        requiresPairing = true,
    )
}
