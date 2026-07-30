package dev.shivam.nfcexplorer.domain.model

/**
 * What a page is for, structurally.
 */
enum class PageRole {
    UID,
    LOCK_CONTROL,
    OTP,
    USER_DATA,
}

/**
 * Whether and how a page can be written.
 *
 * An enum rather than a sentence: user-facing wording lives in `strings.xml`, since the
 * domain layer has no access to resources and must not carry translatable prose.
 */
enum class WriteVerdict {
    /** Writable now. */
    WRITABLE,

    /** A lock bit is set. There is no unlock path on this chip. */
    PERMANENTLY_LOCKED,

    /** Bits can only be OR-ed to 1 and never cleared. */
    OTP_ONE_WAY,

    /** UID pages, fixed at production. */
    HARDWARE_READ_ONLY,

    /** Writing here changes lock state irreversibly. */
    LOCK_CONTROL,
}

/**
 * One `L_*` lock bit. [name] is protocol nomenclature (`L_7`, `L_OTP`), not prose.
 *
 * [isFrozen] is the subtle part: a block-locking bit can freeze this bit's current state,
 * so an *unlocked* page may be permanently unlockable-from and a *locked* one permanently
 * locked. Locked and frozen are independent facts and both are reported.
 */
data class LockBit(
    val name: String,
    val isSet: Boolean,
    val protectedPage: Int,
    val isFrozen: Boolean,
)

/**
 * One `BL_*` block-locking bit and the lock bits it freezes.
 */
data class BlockLockBit(
    val name: String,
    val isSet: Boolean,
    val freezesPages: IntRange,
)

/**
 * Per-page write access. [lockedBy] names the responsible lock bit when one applies, so
 * the UI can say precisely why a page is closed rather than just that it is.
 */
data class PageAccess(
    val page: Int,
    val role: PageRole,
    val verdict: WriteVerdict,
    val lockedBy: String? = null,
)

/**
 * Dynamic lock bit support.
 *
 * MF0ICU1 has none, and modelling that as [NotSupportedByChip] rather than as an empty
 * byte array keeps the UI honest — absence is reported as absence, not as zeros.
 */
sealed interface DynamicLockSupport {

    /** The chip has no dynamic lock bytes at all. */
    data class NotSupportedByChip(val introducedIn: String) : DynamicLockSupport

    /** The chip has dynamic lock bytes. Decoding lands with NTAG21x in Phase 2. */
    data class Present(val bytes: ByteBlock) : DynamicLockSupport
}

/**
 * Complete access-control picture for a tag.
 *
 * [staticLockBytes] is null when page `0x02` could not be read, in which case the page
 * verdicts fall back to structural roles only and nothing about locking is asserted.
 */
data class LockAnalysis(
    val staticLockBytes: ByteBlock?,
    val lockBits: List<LockBit>,
    val blockLockBits: List<BlockLockBit>,
    val pageAccess: List<PageAccess>,
    val dynamicLockSupport: DynamicLockSupport,
) {
    fun accessFor(page: Int): PageAccess? = pageAccess.firstOrNull { it.page == page }

    val lockedPages: List<Int>
        get() = pageAccess.filter { it.verdict == WriteVerdict.PERMANENTLY_LOCKED }.map { it.page }

    val writablePages: List<Int>
        get() = pageAccess.filter { it.verdict == WriteVerdict.WRITABLE }.map { it.page }
}
