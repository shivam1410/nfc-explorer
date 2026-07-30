package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.LockAnalysis
import dev.shivam.nfcexplorer.domain.model.WriteDecision
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.domain.writer.WriteGuard
import dev.shivam.nfcexplorer.logging.SessionLogger
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes one page, but only if the guard allows it, and proves the result by reading back.
 *
 * Blocking: performs tag I/O on the calling thread, like [ReadTagUseCase]. The repository moves
 * it to the IO dispatcher.
 *
 * Three properties matter here, and each is swept in `WritePageUseCaseTest`:
 *
 *  - **A refused write never reaches the transport.** The guard runs first and returns
 *    [WriteOutcome.Refused] before any I/O, so there is no path from a blocked decision to a
 *    byte on the wire.
 *  - **Success is demonstrated, not assumed.** The page is read back afterwards. On OTP and lock
 *    pages the chip ORs incoming bits rather than replacing them, so a write can legitimately
 *    succeed *and* store something different from what was sent — the user needs to see which,
 *    so that is reported as [WriteOutcome.Written] with `verified = false`, not as a failure.
 *  - **A failed read-back is not a failed write.** If verification cannot run, `readBack` is null
 *    and `verified` is null, which is different from a mismatch. The write still happened.
 */
class WritePageUseCase(
    private val guard: WriteGuard,
    private val logger: SessionLogger,
) {

    operator fun invoke(
        transport: UltralightTransport,
        page: Int,
        data: ByteArray,
        locks: LockAnalysis,
        expertMode: Boolean,
    ): WriteOutcome {
        val attempted = ByteBlock.copyOf(data)
        val decision = guard.evaluate(page, data, locks, expertMode)

        if (decision !is WriteDecision.Allowed) {
            logger.warn(
                category = CATEGORY,
                message = "write refused",
                payload = mapOf(
                    "page" to page.toString(),
                    "decision" to decision.toString(),
                    "expertMode" to expertMode.toString(),
                ),
            )
            return WriteOutcome.Refused(page, decision)
        }

        logger.info(
            category = CATEGORY,
            message = "writing page",
            payload = mapOf(
                "page" to page.toString(),
                "bytes" to attempted.toString(),
                "expertMode" to expertMode.toString(),
                "risk" to (decision.acknowledgedRisk?.name ?: "none"),
            ),
        )

        try {
            transport.writePage(page, data)
        } catch (failure: IOException) {
            val lockedBy = locks.accessFor(page)?.lockedBy
            logger.error(
                category = CATEGORY,
                message = "tag rejected the write",
                payload = mapOf(
                    "page" to page.toString(),
                    "exception" to (failure::class.simpleName ?: "IOException"),
                    "message" to (failure.message ?: ""),
                    "lockedBy" to (lockedBy ?: "unknown"),
                ),
            )
            return WriteOutcome.Failed(
                page = page,
                attempted = attempted,
                exceptionName = failure::class.simpleName ?: "IOException",
                message = failure.message,
                lockedBy = lockedBy,
            )
        }

        val readBack = readBack(transport, page)
        val outcome = WriteOutcome.Written(
            page = page,
            attempted = attempted,
            readBack = readBack,
            acknowledgedRisk = decision.acknowledgedRisk,
        )

        logger.info(
            category = CATEGORY,
            message = "write finished",
            payload = mapOf(
                "page" to page.toString(),
                "readBack" to (readBack?.toString() ?: "unavailable"),
                "verified" to (outcome.verified?.toString() ?: "unverified"),
            ),
        )
        return outcome
    }

    /**
     * Reads the written page back.
     *
     * `readPages` returns four pages starting at the offset, so only the first page's worth is
     * the result — the rest belong to neighbours. Returns null if verification cannot run, which
     * must not be conflated with a mismatch.
     */
    private fun readBack(transport: UltralightTransport, page: Int): ByteBlock? =
        try {
            val frame = transport.readPages(page)
            ByteBlock.copyOf(frame.copyOfRange(0, UltralightTransport.BYTES_PER_PAGE))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Catches Throwable, not just IOException. The write has ALREADY physically happened by
            // this point, so letting an unexpected exception escape here would discard the record of
            // a permanent hardware change -- a malformed short frame producing
            // IndexOutOfBoundsException, for instance. Verification failing is not the write failing.
            logger.warn(
                category = CATEGORY,
                message = "could not read back the written page",
                payload = mapOf(
                    "page" to page.toString(),
                    "exception" to (failure::class.simpleName ?: "Throwable"),
                ),
            )
            null
        }

    private companion object {
        const val CATEGORY = "write"
    }
}
