package dev.shivam.nfcexplorer.domain.model

/**
 * Result of attempting to write one page.
 *
 * [Written] carries the read-back so success is *demonstrated* rather than assumed — a write
 * that the tag accepted but stored differently (OTP and lock bytes OR incoming bits rather than
 * replacing them) is a success with a different result, and the user needs to see which.
 */
/**
 * Result of writing a run of pages.
 *
 * [outcomes] covers only the pages actually **attempted**. A batch stops at the first page that
 * does not succeed, because the usual causes — the tag left the field, or a page is locked — will
 * affect every page after it too, and hammering a tag that is already refusing produces noise
 * rather than information. [pagesRequested] is retained so a caller can see how far it got.
 */
data class WriteBatchResult(
    val startPage: Int,
    val pagesRequested: Int,
    val outcomes: List<WriteOutcome>,
) {
    val writtenCount: Int get() = outcomes.count { it is WriteOutcome.Written }

    /**
     * True only when something was requested *and* all of it was written.
     *
     * The `pagesRequested > 0` guard matters: without it an empty batch would be vacuously
     * successful and the UI would report a write that never happened.
     */
    val allSucceeded: Boolean
        get() = pagesRequested > 0 &&
            outcomes.size == pagesRequested &&
            outcomes.all { it is WriteOutcome.Written }

    /** The outcome that stopped the batch, or null if every page succeeded. */
    val stoppedBy: WriteOutcome? get() = outcomes.firstOrNull { it !is WriteOutcome.Written }

    /** True when at least one page was written but the batch did not finish. */
    val isPartial: Boolean get() = writtenCount > 0 && !allSucceeded

    companion object {
        fun empty(startPage: Int) = WriteBatchResult(startPage, pagesRequested = 0, outcomes = emptyList())
    }
}

sealed interface WriteOutcome {

    data class Written(
        val page: Int,
        val attempted: ByteBlock,
        val readBack: ByteBlock?,
        val acknowledgedRisk: WriteRiskReason? = null,
    ) : WriteOutcome {
        /** Null read-back means verification could not run, which is not the same as a mismatch. */
        val verified: Boolean? get() = readBack?.let { it == attempted }
    }

    /** The guard declined before anything reached the tag. */
    data class Refused(
        val page: Int,
        val decision: WriteDecision,
    ) : WriteOutcome

    /**
     * The tag rejected the write. [lockedBy] is populated when the lock analysis already
     * explains why, which is how a bare `IOException` from the platform becomes a useful
     * diagnostic.
     */
    data class Failed(
        val page: Int,
        val attempted: ByteBlock,
        val exceptionName: String,
        val message: String?,
        val lockedBy: String? = null,
    ) : WriteOutcome
}
