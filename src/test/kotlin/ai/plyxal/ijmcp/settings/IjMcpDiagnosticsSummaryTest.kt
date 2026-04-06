package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetRegistration
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IjMcpDiagnosticsSummaryTest {
    @Test
    fun runtimeIdentityIncludesProductProcessAndTarget() {
        val summary = IjMcpDiagnosticsSummary.runtimeIdentity(targetStatus())

        assertTrue(summary.contains("IntelliJ IDEA Community Edition"))
        assertTrue(summary.contains("pid 4321"))
        assertTrue(summary.contains("target target-a"))
    }

    @Test
    fun registryEntryPrefersPublishedEndpointAndLastSeen() {
        val summary = IjMcpDiagnosticsSummary.registryEntry(
            status = targetStatus(),
            registration = IjMcpTargetRegistration(
                targetId = "target-a",
                ideInstanceId = "ide-a",
                pid = 4321,
                productCode = "IC",
                productName = "IntelliJ IDEA Community Edition",
                projectName = "ij-mcp",
                projectPath = "/tmp/ij-mcp",
                endpointUrl = "http://127.0.0.1:8765/mcp",
                port = 8765,
                protocolVersion = "2025-11-25",
                requiresPairing = false,
                lastSeenAt = "2026-04-06T18:45:00Z",
            ),
        )

        assertEquals(
            "Registered at 2026-04-06T18:45:00Z via http://127.0.0.1:8765/mcp",
            summary,
        )
    }

    @Test
    fun lastErrorFallsBackToNoErrorWhenTargetIsHealthy() {
        assertEquals(
            "No target error reported.",
            IjMcpDiagnosticsSummary.lastError(targetStatus()),
        )
    }

    private fun targetStatus(): IjMcpTargetStatus = IjMcpTargetStatus(
        descriptor = IjMcpTargetDescriptor(
            targetId = "target-a",
            ideInstanceId = "ide-a",
            pid = 4321,
            productCode = "IC",
            productName = "IntelliJ IDEA Community Edition",
            projectName = "ij-mcp",
            projectPath = "/tmp/ij-mcp",
        ),
        running = true,
        port = 8765,
        endpointUrl = "http://127.0.0.1:8765/mcp",
        requiresPairing = false,
    )
}
