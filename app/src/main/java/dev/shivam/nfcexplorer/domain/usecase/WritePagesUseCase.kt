package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.model.LockAnalysis
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Writes a consecutive run of pages, one page at a time, through the same guard as a single write.
 *
 * **Stops at the first page that does not succeed.** The usual causes — the tag left the field, or a
 * page is locked — apply to every page after it as well, so continuing would produce a list of
 * identical failures instead of information, and would keep pushing writes at a tag that is already
 * refusing. A partial result is reported honestly rather than being retried into a mess.
 *
 * There is deliberately no bulk fast path: every page goes through [WritePageUseCase], so the guard
 * and the read-back verification apply identically whether one page is written or twelve. A
 * separate bulk path would be a second place for the safety rules to drift out of step.
 */
class WritePagesUseCase(
    private val writePage: WritePageUseCase,
    private val logger: SessionLogger,
) {

    operator fun invoke(
        transport: UltralightTransport,
        startPage: Int,
        pages: List<ByteArray>,
        locks: LockAnalysis,
        expertMode: Boolean,
    ): WriteBatchResult {
        if (pages.isEmpty()) {
            logger.warn(
                category = CATEGORY,
                message = "nothing to write",
                payload = mapOf("startPage" to startPage.toString()),
            )
            return WriteBatchResult.empty(startPage)
        }

        logger.info(
            category = CATEGORY,
            message = "batch write starting",
            payload = mapOf(
                "startPage" to startPage.toString(),
                "pages" to pages.size.toString(),
                "expertMode" to expertMode.toString(),
            ),
        )

        val outcomes = mutableListOf<WriteOutcome>()
        try {
            for ((offset, payload) in pages.withIndex()) {
                val outcome = writePage(
                    transport = transport,
                    page = startPage + offset,
                    data = payload,
                    locks = locks,
                    expertMode = expertMode,
                )
                outcomes += outcome
                if (outcome !is WriteOutcome.Written) break
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            // Pages written before this point are permanently altered on the tag. Letting the
            // exception unwind would discard `outcomes` and report the whole batch as a generic
            // failure, hiding real hardware changes from a user who may then retry believing
            // nothing happened. The partial result is reported instead.
            logger.error(
                category = CATEGORY,
                message = "batch write aborted unexpectedly; earlier pages were already written",
                payload = mapOf(
                    "pagesWritten" to outcomes.count { it is WriteOutcome.Written }.toString(),
                    "exception" to (unexpected::class.simpleName ?: "Throwable"),
                    "message" to (unexpected.message ?: ""),
                ),
            )
        }

        val result = WriteBatchResult(
            startPage = startPage,
            pagesRequested = pages.size,
            outcomes = outcomes,
        )

        logger.info(
            category = CATEGORY,
            message = "batch write finished",
            payload = mapOf(
                "startPage" to startPage.toString(),
                "pagesWritten" to result.writtenCount.toString(),
                "pagesRequested" to result.pagesRequested.toString(),
                "complete" to result.allSucceeded.toString(),
            ),
        )
        return result
    }

    private companion object {
        const val CATEGORY = "write"
    }
}
