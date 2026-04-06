package ai.plyxal.ijmcp.cli

import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun main(args: Array<String>) {
    val exitCode = IjMcpCli().run(args.toList())
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

internal class IjMcpCli(
    private val stateStore: IjMcpCliStateStore = IjMcpCliStateStore(),
    private val registryReader: IjMcpTargetRegistryReader = IjMcpTargetRegistryReader(),
    private val httpClient: IjMcpCliHttpClient = IjMcpCliHttpClient(),
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val selectedTargetResolver = IjMcpSelectedTargetResolver(stateStore, registryReader, httpClient)

    fun run(args: List<String>): Int {
        if (args.isEmpty()) {
            printUsage(stderr)
            return 64
        }

        return when (args.first()) {
            "targets" -> runTargets(args.drop(1))
            "mcp" -> runMcp(args.drop(1))
            "gateway" -> runGateway(args.drop(1))
            "help", "--help", "-h" -> {
                printUsage(stdout)
                0
            }
            else -> {
                stderr.println("Unknown command: ${args.first()}")
                printUsage(stderr)
                64
            }
        }
    }

    private fun runTargets(args: List<String>): Int {
        if (args.isEmpty()) {
            printTargetsUsage(stderr)
            return 64
        }

        return when (args.first()) {
            "list" -> {
                val state = stateStore.load()
                val targets = registryReader.readTargets()
                if (targets.isEmpty()) {
                    stdout.println("No IJ-MCP targets are currently registered.")
                } else {
                    targets.forEach { target ->
                        val selectionMarker = if (state.selectedTargetId == target.targetId) "*" else " "
                        val credentialMarker = if (state.credentialsByTargetId.containsKey(target.targetId)) "paired" else "unpaired"
                        stdout.println(
                            "$selectionMarker ${target.targetId} ${target.projectName} [$credentialMarker] ${target.endpointUrl}",
                        )
                    }
                }
                0
            }

            "current" -> {
                val summary = selectedTargetResolver.describeSelectedTargetStatus()
                stdout.println("routeStatus=${summary.routeStatus}")
                stdout.println("registryFile=${summary.registryFile}")
                stdout.println("selectedTargetId=${summary.selectedTargetId ?: ""}")
                stdout.println("projectName=${summary.projectName ?: ""}")
                stdout.println("projectPath=${summary.projectPath ?: ""}")
                stdout.println("endpointUrl=${summary.endpointUrl ?: ""}")
                stdout.println("paired=${summary.paired}")
                stdout.println("running=${summary.running?.toString() ?: ""}")
                stdout.println("requiresPairing=${summary.requiresPairing?.toString() ?: ""}")
                if (!summary.recoveryCode.isNullOrBlank()) {
                    stdout.println("recoveryCode=${summary.recoveryCode}")
                }
                if (!summary.recoveryAction.isNullOrBlank()) {
                    stdout.println("recoveryAction=${summary.recoveryAction}")
                }
                if (summary.recoveryCode == null) 0 else 1
            }

            "select" -> {
                val targetId = args.getOrNull(1)
                if (targetId.isNullOrBlank()) {
                    stderr.println("Usage: targets select <targetId>")
                    return 64
                }

                val registration = registryReader.readTargets().firstOrNull { it.targetId == targetId }
                if (registration == null) {
                    stderr.println("Target $targetId is not present in ${registryReader.registryFile()}.")
                    return 1
                }

                val state = stateStore.load()
                stateStore.save(
                    state.copy(selectedTargetId = targetId),
                )
                stdout.println("Selected target $targetId (${registration.projectName}).")
                0
            }

            "pair" -> pairSelectedTarget(args.drop(1))
            "forget" -> forgetTarget(args.drop(1))
            else -> {
                stderr.println("Unknown targets subcommand: ${args.first()}")
                printTargetsUsage(stderr)
                64
            }
        }
    }

    private fun pairSelectedTarget(args: List<String>): Int {
        val parsed = parsePairArguments(args) ?: run {
            stderr.println("Usage: targets pair --code <pairingCode> [targetId]")
            return 64
        }

        val registration = resolveTargetForMutation(parsed.targetId) ?: return 1
        val pairingResponse = httpClient.pair(registration, parsed.pairingCode).getOrElse { exception ->
            stderr.println("Pairing failed for target ${registration.targetId}: ${exception.message}")
            return 1
        }

        val bearerToken = pairingResponse.bearerToken
        if (bearerToken.isNullOrBlank()) {
            stderr.println("Pairing succeeded without a bearer token in the response.")
            return 1
        }

        val state = stateStore.load()
        stateStore.save(
            state.copy(
                selectedTargetId = registration.targetId,
                credentialsByTargetId = state.credentialsByTargetId + (registration.targetId to bearerToken),
            ),
        )
        stdout.println("Paired target ${registration.targetId} (${registration.projectName}).")
        return 0
    }

    private fun forgetTarget(args: List<String>): Int {
        val state = stateStore.load()
        val targetId = args.firstOrNull() ?: state.selectedTargetId
        if (targetId.isNullOrBlank()) {
            stderr.println("No target specified and no sticky target is selected.")
            return 1
        }

        stateStore.save(
            state.copy(
                selectedTargetId = state.selectedTargetId.takeUnless { it == targetId },
                credentialsByTargetId = state.credentialsByTargetId - targetId,
            ),
        )
        stdout.println("Forgot local state for target $targetId.")
        return 0
    }

    private fun runMcp(args: List<String>): Int {
        if (args.isEmpty()) {
            printMcpUsage(stderr)
            return 64
        }

        return when (args.first()) {
            "tools-list" -> {
                val target = resolveSelectedConnectedTarget() ?: return 1
                val result = httpClient.toolsList(target).getOrElse { exception ->
                    stderr.println(exception.message)
                    return 1
                }
                stdout.println(json.encodeToString(JsonObject.serializer(), result.json))
                0
            }

            "call" -> {
                val toolName = args.getOrNull(1)
                if (toolName.isNullOrBlank()) {
                    stderr.println("Usage: mcp call <toolName> [jsonArguments]")
                    return 64
                }

                val rawArguments = args.getOrNull(2) ?: "{}"
                val arguments = runCatching {
                    json.parseToJsonElement(rawArguments) as? JsonObject
                }.getOrNull()

                if (arguments == null) {
                    stderr.println("Tool arguments must be a JSON object.")
                    return 64
                }

                val target = resolveSelectedConnectedTarget() ?: return 1
                val result = httpClient.toolCall(target, toolName, arguments).getOrElse { exception ->
                    stderr.println(exception.message)
                    return 1
                }
                stdout.println(json.encodeToString(JsonObject.serializer(), result.json))
                0
            }

            else -> {
                stderr.println("Unknown mcp subcommand: ${args.first()}")
                printMcpUsage(stderr)
                64
            }
        }
    }

    private fun runGateway(args: List<String>): Int {
        if (args.isEmpty()) {
            printGatewayUsage(stderr)
            return 64
        }

        return when (args.first()) {
            "config" -> {
                val gatewayConfig = ensureGatewayConfig()
                stdout.println("endpointUrl=http://127.0.0.1:${gatewayConfig.port}/mcp")
                stdout.println("healthUrl=http://127.0.0.1:${gatewayConfig.port}/health")
                stdout.println("gatewayBearerToken=${gatewayConfig.bearerToken}")
                stdout.println("selectedTargetId=${stateStore.load().selectedTargetId ?: ""}")
                0
            }

            "serve" -> serveGateway()
            else -> {
                stderr.println("Unknown gateway subcommand: ${args.first()}")
                printGatewayUsage(stderr)
                64
            }
        }
    }

    private fun resolveTargetForMutation(targetId: String?): IjMcpTargetRegistration? {
        val state = stateStore.load()
        val resolvedTargetId = targetId ?: state.selectedTargetId
        if (resolvedTargetId.isNullOrBlank()) {
            stderr.println("No target selected. Run `targets list` and `targets select <targetId>` first.")
            return null
        }

        val registration = registryReader.readTargets().firstOrNull { it.targetId == resolvedTargetId }
        if (registration == null) {
            stderr.println(
                "Selected target $resolvedTargetId is unavailable. Run `targets list` and `targets select <targetId>`.",
            )
            return null
        }

        return registration
    }

    private fun resolveSelectedConnectedTarget(): IjMcpResolvedTarget? {
        return selectedTargetResolver.resolveSelectedConnectedTarget().getOrElse { exception ->
            printExceptionWithRecovery(exception)
            null
        }
    }

    private fun printExceptionWithRecovery(exception: Throwable) {
        stderr.println(exception.message)
        if (exception is IjMcpTargetRouteFailure) {
            stderr.println("recoveryCode=${exception.recoveryCode}")
            stderr.println("recoveryAction=${exception.recoveryAction}")
            if (!exception.selectedTargetId.isNullOrBlank()) {
                stderr.println("selectedTargetId=${exception.selectedTargetId}")
            }
        }
    }

    private fun serveGateway(): Int {
        val gatewayConfig = ensureGatewayConfig()
        val latch = CountDownLatch(1)
        val gatewayPreflight = IjMcpGatewayPreflight(selectedTargetResolver)

        IjMcpCliGatewayServer(
            config = gatewayConfig,
            preflight = gatewayPreflight,
            routeSummaryProvider = { selectedTargetResolver.describeStickyRoute() },
            httpClient = httpClient,
        ).use { server ->
            val port = server.start(IjMcpGatewayServerConfig(port = gatewayConfig.port))
            stdout.println("IJ-MCP gateway listening on http://127.0.0.1:$port/mcp")
            stdout.println("Use `gateway config` to print the stable bearer token and health endpoint.")

            val shutdownHook = Thread {
                runCatching { server.close() }
                latch.countDown()
            }
            Runtime.getRuntime().addShutdownHook(shutdownHook)

            try {
                latch.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 130
            } finally {
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            }
        }

        return 0
    }

    private fun ensureGatewayConfig(): IjMcpGatewayConfig {
        val state = stateStore.load()
        val normalizedPort = state.gatewayPort.takeIf { it in 1..65535 } ?: IJ_MCP_GATEWAY_DEFAULT_PORT
        val normalizedToken = state.gatewayBearerToken
            ?.takeIf { it.isNotBlank() }
            ?: "ijmcp-gateway-${UUID.randomUUID()}"

        val normalizedState = state.copy(
            gatewayPort = normalizedPort,
            gatewayBearerToken = normalizedToken,
        )
        if (normalizedState != state) {
            stateStore.save(normalizedState)
        }

        return IjMcpGatewayConfig(
            port = normalizedPort,
            bearerToken = normalizedToken,
        )
    }

    private fun parsePairArguments(args: List<String>): PairArguments? {
        if (args.size < 2 || args.first() != "--code") {
            return null
        }

        val pairingCode = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val targetId = args.getOrNull(2)
        return PairArguments(
            pairingCode = pairingCode,
            targetId = targetId,
        )
    }

    private fun printUsage(stream: PrintStream) {
        stream.println("Usage:")
        stream.println("  ij-mcp-cli targets <list|current|select|pair|forget> ...")
        stream.println("  ij-mcp-cli mcp <tools-list|call> ...")
        stream.println("  ij-mcp-cli gateway <config|serve> ...")
    }

    private fun printTargetsUsage(stream: PrintStream) {
        stream.println("Targets usage:")
        stream.println("  targets list")
        stream.println("  targets current")
        stream.println("  targets select <targetId>")
        stream.println("  targets pair --code <pairingCode> [targetId]")
        stream.println("  targets forget [targetId]")
    }

    private fun printMcpUsage(stream: PrintStream) {
        stream.println("MCP usage:")
        stream.println("  mcp tools-list")
        stream.println("  mcp call <toolName> [jsonArguments]")
    }

    private fun printGatewayUsage(stream: PrintStream) {
        stream.println("Gateway usage:")
        stream.println("  gateway config")
        stream.println("  gateway serve")
    }
}

private data class PairArguments(
    val pairingCode: String,
    val targetId: String?,
)
