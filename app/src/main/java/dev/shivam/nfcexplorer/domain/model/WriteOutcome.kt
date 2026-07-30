package dev.shivam.nfcexplorer.domain.model

/**
 * Result of attempting to write one page.
 *
 * [Written] carries the read-back so success is *demonstrated* rather than assumed — a write
 * that the tag accepted but stored differently (OTP and lock bytes OR incoming bits rather than
 * replacing them) is a success with a different result, and the user needs to see which.
 */
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
