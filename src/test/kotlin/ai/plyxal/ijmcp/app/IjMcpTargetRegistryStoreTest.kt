package ai.plyxal.ijmcp.app

import ai.plyxal.ijmcp.mcp.IjMcpProtocol
import ai.plyxal.ijmcp.model.IjMcpTargetDescriptor
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class IjMcpTargetRegistryStoreTest {
    @Test
    fun upsertAndRemoveManageTargetRecords() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-test")
        val clock = RegistryTestClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = IjMcpTargetRegistryStore(registryRoot = registryRoot, clock = clock)

        store.upsert(sampleStatus("target-a", 9001))

        val registrations = store.readTargets()

        assertEquals(1, registrations.size)
        assertEquals("target-a", registrations.single().targetId)
        assertEquals(9001, registrations.single().port)
        assertEquals(IjMcpProtocol.protocolVersion, registrations.single().protocolVersion)

        store.remove("target-a")

        assertTrue(store.readTargets().isEmpty())
    }

    @Test
    fun multiWindowTargetsAreTrackedIndependently() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-multi-window")
        val clock = RegistryTestClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = IjMcpTargetRegistryStore(registryRoot = registryRoot, clock = clock)

        store.upsert(sampleStatus("target-a", 9001, "Project A", "/tmp/project-a"))
        store.upsert(sampleStatus("target-b", 9002, "Project B", "/tmp/project-b"))
        store.upsert(sampleStatus("target-a", 9011, "Project A", "/tmp/project-a"))

        val registrations = store.readTargets()

        assertEquals(2, registrations.size)
        assertEquals(9011, registrations.first { it.targetId == "target-a" }.port)
        assertEquals(9002, registrations.first { it.targetId == "target-b" }.port)

        store.remove("target-a")

        val remainingTargets = store.readTargets()
        assertEquals(1, remainingTargets.size)
        assertEquals("target-b", remainingTargets.single().targetId)
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
        val clock = RegistryTestClock(Instant.parse("2026-03-27T20:00:00Z"))
        val store = IjMcpTargetRegistryStore(
            registryRoot = registryRoot,
            clock = clock,
            staleAfter = Duration.ofSeconds(45),
        )

        store.upsert(sampleStatus("target-a", 9001))
        clock.advance(Duration.ofSeconds(46))

        assertTrue(store.readTargets().isEmpty())
        assertTrue(Files.readString(registryRoot.resolve("targets.json")).contains("\"targets\": []"))
    }

    @Test
    fun readTargetsTimesOutWhenRegistryLockCannotBeAcquired() {
        val registryRoot = Files.createTempDirectory("ijmcp-registry-lock-timeout")
        val store = IjMcpTargetRegistryStore(
            registryRoot = registryRoot,
            lockTimeout = Duration.ofMillis(150),
            lockRetryDelay = Duration.ofMillis(10),
        )
        val lockFile = registryRoot.resolve("targets.lock")

        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val startedAt = System.nanoTime()

                try {
                    store.readTargets()
                    fail("Expected registry lock acquisition to time out.")
                } catch (exception: IjMcpTargetRegistryLockTimeoutException) {
                    val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
                    assertTrue(exception.message!!.contains("targets.lock"))
                    assertTrue("Expected timeout within one second, elapsed=$elapsed", elapsed < Duration.ofSeconds(1))
                }
            }
        }
    }

    private fun sampleStatus(
        targetId: String,
        port: Int,
        projectName: String = "ij-mcp",
        projectPath: String = "/tmp/ij-mcp",
    ): IjMcpTargetStatus = IjMcpTargetStatus(
        descriptor = IjMcpTargetDescriptor(
            targetId = targetId,
            ideInstanceId = "ide-1",
            pid = 4242L,
            productCode = "IC",
            productName = "IntelliJ IDEA Community Edition",
            projectName = projectName,
            projectPath = projectPath,
        ),
        running = true,
        port = port,
        endpointUrl = "http://127.0.0.1:$port/mcp",
        requiresPairing = false,
    )
}

private class RegistryTestClock(
    private var currentInstant: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = currentInstant

    fun advance(duration: Duration) {
        currentInstant = currentInstant.plus(duration)
    }
}
