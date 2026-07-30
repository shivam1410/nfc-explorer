package dev.shivam.nfcexplorer.domain.model

/**
 * Why a write is irreversible and therefore gated behind expert mode.
 */
enum class WriteRiskReason {
    /** Page 0x02: lock bits are set-only, so this can permanently close pages. */
    IRREVERSIBLE_LOCK_CONTROL,

    /** Page 0x03: OTP bits are OR-ed and can never be cleared. */
    ONE_WAY_OTP,
}

/**
 * Why a write cannot proceed at all.
 */
enum class WriteBlockReason {
    /** Pages 0x00-0x01 hold the UID and are fixed in hardware. */
    UID_HARDWARE_READ_ONLY,

    /** A lock bit is set; this chip offers no unlock path. */
    PAGE_PERMANENTLY_LOCKED,

    /** Page index outside the chip's geometry. */
    INVALID_PAGE_INDEX,

    /** Payload was not exactly one page wide. */
    INVALID_DATA_LENGTH,

    /** Lock state is unknown because page 0x02 could not be read. */
    LOCK_STATE_UNKNOWN,
}

/**
 * Outcome of the write policy. Reasons are enums so user-facing wording can live in
 * `strings.xml` while the decision itself stays in pure domain code.
 *
 * There is intentionally no way to turn a [Blocked] into an [Allowed]: expert mode
 * unlocks only [RequiresExpertMode].
 */
sealed interface WriteDecision {

    /**
     * The write may proceed.
     *
     * [acknowledgedRisk] is non-null when this page is only writable because expert mode is
     * on. Turning expert mode on must not make the danger invisible, so the reason travels
     * with the approval and the UI keeps warning.
     */
    data class Allowed(val acknowledgedRisk: WriteRiskReason? = null) : WriteDecision

    /** Irreversible, and expert mode is off. Enabling it turns this into [Allowed]. */
    data class RequiresExpertMode(val reason: WriteRiskReason) : WriteDecision

    /** Not writable at all. Expert mode does not affect this outcome. */
    data class Blocked(val reason: WriteBlockReason) : WriteDecision
}
