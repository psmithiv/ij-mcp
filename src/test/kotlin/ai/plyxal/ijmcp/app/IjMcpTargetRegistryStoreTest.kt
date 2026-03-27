package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class IjMcpTargetRegistryStoreTest {
    @Test
    fun upsertAndRemoveManageTargetRecords() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-test")
        val clock = MutableClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = IjMcpTargetRegistryStore(registryRoot = registryRoot, clock = clock)

        store.upsert(sampleStatus("target-a", 9001), requiresPairing = false)

        val registrations = store.readTargets()

        assertEquals(1, registrations.size)
        assertEquals("target-a", registrations.single().targetId)
        assertEquals(9001, registrations.single().port)
        assertEquals(IjMcpProtocol.protocolVersion, registrations.single().protocolVersion)

        store.remove("target-a")

        assertTrue(store.readTargets().isEmpty())
    }

    @Test
    fun readTargetsRecoversFromCorruptRegistryFiles() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-corrupt")
        val store = IjMcpTargetRegistryStore(registryRoot = registryRoot)
        Files.createDirectories(registryRoot)
        Files.writeString(registryRoot.resolve("targets.json"), "{not valid json")

        val registrations = store.readTargets()

        assertTrue(registrations.isEmpty())
        assertTrue(
            Files.list(registryRoot).use { paths ->
                paths.anyMatch { it.fileName.toString().startsWith("targets.corrupt-") }
            },
        )
    }

    @Test
    fun readTargetsDropsStaleEntries() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-stale")
        val clock = MutableClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = IjMcpTargetRegistryStore(
            registryRoot = registryRoot,
            clock = clock,
            staleAfter = Duration.ofSeconds(45),
        )

        store.upsert(sampleStatus("target-a", 9001), requiresPairing = false)
        clock.advance(Duration.ofSeconds(46))

        assertTrue(store.readTargets().isEmpty())
        assertTrue(Files.readString(registryRoot.resolve("targets.json")).contains("\"targets\": []"))
    }

    private fun sampleStatus(targetId: String, port: Int): IjMcpTargetStatus = IjMcpTargetStatus(
        descriptor = IjMcpTargetDescriptor(
            targetId = targetId,
            ideInstanceId = "ide-1",
            pid = 4242L,
            productCode = "IC",
            productName = "IntelliJ IDEA Community Edition",
            projectName = "ij-mcp",
            projectPath = "/tmp/ij-mcp",
        ),
        running = true,
        port = port,
        endpointUrl = "http://127.0.0.1:$port/mcp",
    )
}

private class MutableClock(
    private var currentInstant: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
