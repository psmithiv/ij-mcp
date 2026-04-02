package ai.plyxal.ijmcp.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class IjMcpCliHttpClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun health(registration: IjMcpTargetRegistration): Result<IjMcpHealthResponse> = runCatching {
        val response = httpClient.send(
            HttpRequest.newBuilder(healthUri(registration.endpointUrl))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        require(response.statusCode() == 200) {
            "Health check failed with HTTP ${response.statusCode()}."
        }

        json.decodeFromString<IjMcpHealthResponse>(response.body())
    }

    fun pair(
        registration: IjMcpTargetRegistration,
        pairingCode: String,
    ): Result<IjMcpPairingResponse> = runCatching {
        val response = httpClient.send(
            HttpRequest.newBuilder(pairingUri(registration.endpointUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("pairingCode", pairingCode)
                            },
                        ),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        val pairingResponse = json.decodeFromString<IjMcpPairingResponse>(response.body())
        require(response.statusCode() == 200 && pairingResponse.status == "success") {
            pairingResponse.message ?: "Pairing failed with HTTP ${response.statusCode()}."
        }

        pairingResponse
    }

    fun toolsList(target: IjMcpResolvedTarget): Result<IjMcpJsonRpcResult> = postJsonRpc(
        endpointUrl = target.registration.endpointUrl,
        bearerToken = target.bearerToken,
        payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/list")
            put("params", buildJsonObject {})
        },
    )

    fun toolCall(
        target: IjMcpResolvedTarget,
        toolName: String,
        arguments: JsonObject,
    ): Result<IjMcpJsonRpcResult> = postJsonRpc(
        endpointUrl = target.registration.endpointUrl,
        bearerToken = target.bearerToken,
        payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", toolName)
                    put("arguments", arguments)
                },
            )
        },
    )

    fun initialize(target: IjMcpResolvedTarget): Result<Unit> = postJsonRpc(
        endpointUrl = target.registration.endpointUrl,
        bearerToken = target.bearerToken,
        payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", IJ_MCP_PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "ij-mcp-cli")
                            put("version", IjMcpCliBuildInfo.cliVersion)
                        },
                    )
                },
            )
        },
    ).map { Unit }

    private fun postJsonRpc(
        endpointUrl: String,
        bearerToken: String,
        payload: JsonObject,
    ): Result<IjMcpJsonRpcResult> = runCatching {
        val response = httpClient.send(
            HttpRequest.newBuilder(URI.create(endpointUrl))
                .header("Authorization", "Bearer $bearerToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("MCP-Protocol-Version", IJ_MCP_PROTOCOL_VERSION)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(JsonObject.serializer(), payload),
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        require(response.statusCode() == 200) {
            "MCP request failed with HTTP ${response.statusCode()}."
        }

        val responseJson = json.parseToJsonElement(response.body()).jsonObject
        val errorObject = responseJson["error"]?.jsonObject
        if (errorObject != null) {
            val message = errorObject["message"]?.toString()?.trim('"') ?: "MCP request failed."
            throw IllegalStateException(message)
        }

        IjMcpJsonRpcResult(responseJson)
    }

    private fun healthUri(endpointUrl: String): URI = replacementPath(endpointUrl, "/health")

    private fun pairingUri(endpointUrl: String): URI = replacementPath(endpointUrl, "/pair")

    private fun replacementPath(
        endpointUrl: String,
        path: String,
    ): URI {
        val endpoint = URI.create(endpointUrl)
        return URI(
            endpoint.scheme,
            endpoint.userInfo,
            endpoint.host,
            endpoint.port,
            path,
            null,
            null,
        )
    }
}
