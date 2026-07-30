package dev.shivam.nfcexplorer.domain.writer

import dev.shivam.nfcexplorer.domain.model.LockAnalysis
import dev.shivam.nfcexplorer.domain.model.WriteBlockReason
import dev.shivam.nfcexplorer.domain.model.WriteDecision
import dev.shivam.nfcexplorer.domain.model.WriteRiskReason
import dev.shivam.nfcexplorer.domain.model.WriteVerdict
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport

/**
 * Decides whether a page write may proceed.
 *
 * The only thing standing between a mistaken tap and permanent hardware damage, so it is a
 * pure function of page index, lock state and expert mode — no I/O, no time, no
 * configuration. That makes every branch exhaustively testable, which is the point.
 *
 * Two rules hold unconditionally, and are swept in `WriteGuardTest` rather than sampled:
 *
 *  - expert mode can only turn [WriteDecision.RequiresExpertMode] into
 *    [WriteDecision.Allowed]. It never touches a [WriteDecision.Blocked].
 *  - a page whose lock state is unknown is never writable. Guessing would either invite a
 *    write that silently fails, or one that unexpectedly succeeds.
 *
 * The payload is checked for width only. Its contents never influence the decision, so no
 * caller can smuggle a different outcome through the bytes.
 */
class WriteGuard {

    fun evaluate(
        page: Int,
        data: ByteArray,
        locks: LockAnalysis,
        expertMode: Boolean,
    ): WriteDecision {
        if (data.size != UltralightTransport.BYTES_PER_PAGE) {
            return WriteDecision.Blocked(WriteBlockReason.INVALID_DATA_LENGTH)
        }

        val access = locks.accessFor(page)
            ?: return WriteDecision.Blocked(WriteBlockReason.INVALID_PAGE_INDEX)

        return when (access.verdict) {
            WriteVerdict.WRITABLE -> WriteDecision.Allowed()

            WriteVerdict.HARDWARE_READ_ONLY ->
                WriteDecision.Blocked(WriteBlockReason.UID_HARDWARE_READ_ONLY)

            WriteVerdict.PERMANENTLY_LOCKED ->
                WriteDecision.Blocked(WriteBlockReason.PAGE_PERMANENTLY_LOCKED)

            WriteVerdict.UNKNOWN_LOCK_STATE ->
                WriteDecision.Blocked(WriteBlockReason.LOCK_STATE_UNKNOWN)

            WriteVerdict.LOCK_CONTROL ->
                gate(WriteRiskReason.IRREVERSIBLE_LOCK_CONTROL, expertMode)

            WriteVerdict.OTP_ONE_WAY ->
                gate(WriteRiskReason.ONE_WAY_OTP, expertMode)
        }
    }

    /**
     * The risk travels with the approval so enabling expert mode does not make the danger
     * invisible — the UI keeps warning even once the write is permitted.
     */
    private fun gate(reason: WriteRiskReason, expertMode: Boolean): WriteDecision =
        if (expertMode) {
            WriteDecision.Allowed(acknowledgedRisk = reason)
        } else {
            WriteDecision.RequiresExpertMode(reason)
        }
}
