package dev.shivam.nfcexplorer.ui.component

/**
 * Semantic tone of a chip. Maps to the app-wide colour roles.
 */
enum class ChipTone {
    /** Writable, valid, supported. */
    POSITIVE,

    /** Locked, invalid, failed. */
    NEGATIVE,

    /** One-way or irreversible — caution rather than failure. */
    CAUTION,

    /** Read-only, absent, not established. Deliberately quiet. */
    NEUTRAL,
}
