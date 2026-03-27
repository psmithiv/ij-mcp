package ai.plyxal.ijmcp.mcp

internal interface IjMcpServerSecurity {
    fun isAuthorized(authorizationHeader: String?): Boolean

    fun requiresPairing(): Boolean

    fun exchangePairingCode(pairingCode: String): IjMcpPairingExchangeResult
}

internal sealed interface IjMcpPairingExchangeResult {
    data class Success(
        val bearerToken: String,
    ) : IjMcpPairingExchangeResult

    data class Failure(
        val errorCode: String,
        val message: String,
        val statusCode: Int,
    ) : IjMcpPairingExchangeResult
}

internal class IjMcpStaticTokenSecurity(
    private val bearerToken: String,
) : IjMcpServerSecurity {
    override fun isAuthorized(authorizationHeader: String?): Boolean = authorizationHeader == "Bearer $bearerToken"

    override fun requiresPairing(): Boolean = false

    override fun exchangePairingCode(pairingCode: String): IjMcpPairingExchangeResult = IjMcpPairingExchangeResult.Failure(
        errorCode = "pairing_not_supported",
        message = "This server does not support pairing code exchange.",
        statusCode = 405,
    )
}
