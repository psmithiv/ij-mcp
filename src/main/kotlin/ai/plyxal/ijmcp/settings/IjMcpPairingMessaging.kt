package ai.plyxal.ijmcp.settings

import ai.plyxal.ijmcp.app.IssuedPairingCode
import ai.plyxal.ijmcp.model.IjMcpTargetStatus
import java.time.Instant
import java.time.format.DateTimeFormatter

internal class IjMcpPairingMessaging(
    private val dateFormatter: DateTimeFormatter,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun pairingWorkflow(
        status: IjMcpTargetStatus,
        issuedCode: IssuedPairingCode?,
    ): String {
        val activeCode = issuedCode?.takeUnless { nowProvider().isAfter(it.expiresAt) }

        return when {
            !status.running ->
                "Pairing is unavailable until this target is running."

            activeCode != null ->
                "Use this single-use code with `targets pair --code <pairingCode> ${status.descriptor.targetId}` before ${format(activeCode.expiresAt)}."

            status.requiresPairing ->
                "Generate a one-time code, then pair the CLI or gateway against target ${status.descriptor.targetId}."

            else ->
                "This target is already paired. Existing CLI and gateway sessions keep working until you reset access."
        }
    }

    fun resetImpact(
        status: IjMcpTargetStatus,
        issuedCode: IssuedPairingCode?,
    ): String {
        val activeCode = issuedCode?.takeUnless { nowProvider().isAfter(it.expiresAt) }

        return when {
            !status.running ->
                "Reset is only meaningful after the target is running and ready to accept a new pair."

            status.requiresPairing && activeCode == null ->
                "Reset clears any pending code and keeps the target waiting for a fresh pair."

            else ->
                "Reset revokes the current CLI and gateway token, clears any active code, and forces every client to pair again."
        }
    }

    fun codeExpiry(issuedCode: IssuedPairingCode?): String {
        val activeCode = issuedCode?.takeUnless { nowProvider().isAfter(it.expiresAt) }
        return if (activeCode == null) {
            "Pairing code: no active code. Generate one when you are ready to pair a CLI or gateway."
        } else {
            "Pairing code: single use until ${format(activeCode.expiresAt)}"
        }
    }

    private fun format(instant: Instant): String = dateFormatter.format(instant)
}
