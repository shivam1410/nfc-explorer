package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.BlockLockBit
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.DynamicLockSupport
import dev.shivam.nfcexplorer.domain.model.LockAnalysis
import dev.shivam.nfcexplorer.domain.model.LockBit
import dev.shivam.nfcexplorer.domain.model.PageAccess
import dev.shivam.nfcexplorer.domain.model.PageRole
import dev.shivam.nfcexplorer.domain.model.WriteVerdict

/**
 * Decodes the MF0ICU1 static lock bytes into a per-page access picture.
 *
 * Two independent facts come out of these two bytes, and conflating them is the classic
 * mistake:
 *
 *  - a **lock bit** (`L_*`) makes its page permanently read-only;
 *  - a **block-locking bit** (`BL_*`) freezes a *range of lock bits*, so whether those pages
 *    are locked can never change again. An unlocked page with a frozen lock bit stays
 *    writable forever.
 *
 * Both are set-only. There is no unlock path, no key and no reset on this chip.
 *
 * Bit tables: `docs/mf0icu1-reference.md`.
 */
object StaticLockDecoder {

    private const val LOCK0_INDEX = 0
    private const val LOCK1_INDEX = 1

    private const val BL_OTP_MASK = 0x01
    private const val BL_9_4_MASK = 0x02
    private const val BL_15_10_MASK = 0x04
    private const val L_OTP_MASK = 0x08

    private val UID_PAGES = 0..1
    private const val LOCK_PAGE = 2
    private const val OTP_PAGE = 3
    private const val FIRST_USER_PAGE = 4
    private const val LAST_PAGE = 15
    private const val LOCK0_LAST_USER_PAGE = 7
    private const val LOCK1_FIRST_PAGE = 8

    /** Dynamic lock bits are an EV1/NTAG feature; this chip predates them entirely. */
    private const val DYNAMIC_LOCK_INTRODUCED_IN = "MIFARE Ultralight EV1 / NTAG21x"

    private val ALL_PAGES = 0..LAST_PAGE

    /**
     * Extracts the two static lock bytes from a `READ` frame taken at [LOCK_PAGE].
     *
     * `readPages(2)` returns pages 2, 3, 4, 5 — sixteen bytes — so the lock bytes are bytes 2 and 3
     * of the frame, being bytes 2 and 3 of page `0x02` itself. Getting this offset wrong would feed
     * the decoder the wrong bytes and mislabel which pages are writable, so it lives here as a named
     * function with tests rather than as two magic indices inside a repository.
     *
     * Returns null for a short or absent frame, which the decoder turns into
     * [WriteVerdict.UNKNOWN_LOCK_STATE] and the guard turns into a refusal — the safe direction.
     */
    fun lockBytesFromLockPageFrame(frame: ByteArray?): ByteBlock? {
        if (frame == null || frame.size <= LOCK1_FRAME_OFFSET) return null
        return ByteBlock.ofInts(
            frame[LOCK0_FRAME_OFFSET].toInt() and 0xFF,
            frame[LOCK1_FRAME_OFFSET].toInt() and 0xFF,
        )
    }

    /** Page whose frame carries the lock bytes. */
    const val LOCK_PAGE_ADDRESS = 2

    private const val LOCK0_FRAME_OFFSET = 2
    private const val LOCK1_FRAME_OFFSET = 3

    /**
     * @param lockBytes the two bytes at page `0x02` offsets 2 and 3, or null when that page
     *   could not be read. Null produces [WriteVerdict.UNKNOWN_LOCK_STATE] for every page
     *   whose state depends on a lock bit — never a guess of writable.
     */
    fun decode(lockBytes: ByteBlock?): LockAnalysis {
        if (lockBytes == null) return withUnknownLockState()

        require(lockBytes.size == 2) {
            "static lock state is 2 bytes (LOCK0, LOCK1), got ${lockBytes.size}"
        }

        val lock0 = lockBytes.unsignedAt(LOCK0_INDEX)
        val lock1 = lockBytes.unsignedAt(LOCK1_INDEX)

        val blockLockBits = listOf(
            BlockLockBit("BL_OTP", lock0 and BL_OTP_MASK != 0, OTP_PAGE..OTP_PAGE),
            BlockLockBit("BL_9_4", lock0 and BL_9_4_MASK != 0, FIRST_USER_PAGE..9),
            BlockLockBit("BL_15_10", lock0 and BL_15_10_MASK != 0, 10..LAST_PAGE),
        )

        val lockBits = buildList {
            add(
                LockBit(
                    name = "L_OTP",
                    isSet = lock0 and L_OTP_MASK != 0,
                    protectedPage = OTP_PAGE,
                    isFrozen = isFrozen(OTP_PAGE, blockLockBits),
                ),
            )
            for (page in FIRST_USER_PAGE..LAST_PAGE) {
                add(
                    LockBit(
                        name = "L_$page",
                        isSet = isUserPageLocked(page, lock0, lock1),
                        protectedPage = page,
                        isFrozen = isFrozen(page, blockLockBits),
                    ),
                )
            }
        }

        val bitsByPage = lockBits.associateBy { it.protectedPage }

        return LockAnalysis(
            staticLockBytes = lockBytes,
            lockBits = lockBits,
            blockLockBits = blockLockBits,
            pageAccess = ALL_PAGES.map { page ->
                accessFor(page, bitsByPage[page], lockStateKnown = true)
            },
            dynamicLockSupport = DynamicLockSupport.NotSupportedByChip(DYNAMIC_LOCK_INTRODUCED_IN),
        )
    }

    private fun accessFor(page: Int, lockBit: LockBit?, lockStateKnown: Boolean): PageAccess = when {
        page in UID_PAGES -> PageAccess(page, PageRole.UID, WriteVerdict.HARDWARE_READ_ONLY)

        // Lock bits are OR-only, so writing them blind cannot clear anything — but it can
        // permanently close pages that were never successfully read. Not something to offer.
        page == LOCK_PAGE -> PageAccess(
            page = page,
            role = PageRole.LOCK_CONTROL,
            verdict = if (lockStateKnown) {
                WriteVerdict.LOCK_CONTROL
            } else {
                WriteVerdict.UNKNOWN_LOCK_STATE
            },
        )

        page == OTP_PAGE -> when {
            lockBit == null -> PageAccess(page, PageRole.OTP, WriteVerdict.UNKNOWN_LOCK_STATE)
            lockBit.isSet ->
                PageAccess(page, PageRole.OTP, WriteVerdict.PERMANENTLY_LOCKED, lockBit.name)
            // Not locked, but OTP bits are OR-ed, so a write is still one-way.
            else -> PageAccess(page, PageRole.OTP, WriteVerdict.OTP_ONE_WAY)
        }

        else -> when {
            lockBit == null -> PageAccess(page, PageRole.USER_DATA, WriteVerdict.UNKNOWN_LOCK_STATE)
            lockBit.isSet ->
                PageAccess(page, PageRole.USER_DATA, WriteVerdict.PERMANENTLY_LOCKED, lockBit.name)
            else -> PageAccess(page, PageRole.USER_DATA, WriteVerdict.WRITABLE)
        }
    }

    /**
     * `L_4`..`L_7` occupy the top nibble of `LOCK0` at their own page number, and `L_8`..`L_15`
     * occupy `LOCK1` from bit 0 upward.
     */
    private fun isUserPageLocked(page: Int, lock0: Int, lock1: Int): Boolean = when (page) {
        in FIRST_USER_PAGE..LOCK0_LAST_USER_PAGE -> lock0 and (1 shl page) != 0
        in LOCK1_FIRST_PAGE..LAST_PAGE -> lock1 and (1 shl (page - LOCK1_FIRST_PAGE)) != 0
        else -> false
    }

    private fun isFrozen(page: Int, blockLockBits: List<BlockLockBit>): Boolean =
        blockLockBits.any { it.isSet && page in it.freezesPages }

    /**
     * Structural *roles* are still known when page `0x02` is unreadable — the UID pages and the
     * lock page are fixed by the chip's layout — but no write verdict that depends on lock state
     * is asserted, including for the lock page itself.
     */
    private fun withUnknownLockState() = LockAnalysis(
        staticLockBytes = null,
        lockBits = emptyList(),
        blockLockBits = emptyList(),
        pageAccess = ALL_PAGES.map { page ->
            accessFor(page, lockBit = null, lockStateKnown = false)
        },
        dynamicLockSupport = DynamicLockSupport.NotSupportedByChip(DYNAMIC_LOCK_INTRODUCED_IN),
    )
}
