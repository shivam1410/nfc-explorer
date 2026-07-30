package dev.shivam.nfcexplorer.domain.writer

import dev.shivam.nfcexplorer.domain.decoder.StaticLockDecoder
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.WriteBlockReason
import dev.shivam.nfcexplorer.domain.model.WriteDecision
import dev.shivam.nfcexplorer.domain.model.WriteRiskReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard is the only thing standing between a mistaken tap and permanent hardware damage,
 * so every branch is asserted, and invariant I3 is swept exhaustively rather than sampled.
 */
class WriteGuardTest {

    private val guard = WriteGuard()

    private val unlocked = StaticLockDecoder.decode(ByteBlock.ofInts(0x00, 0x00))
    private val fullyLocked = StaticLockDecoder.decode(ByteBlock.ofInts(0xFF, 0xFF))
    private val unknownLocks = StaticLockDecoder.decode(null)

    private val payload = ByteArray(4) { 0x11 }

    private fun evaluate(
        page: Int,
        locks: dev.shivam.nfcexplorer.domain.model.LockAnalysis = unlocked,
        expertMode: Boolean = false,
        data: ByteArray = payload,
    ) = guard.evaluate(page, data, locks, expertMode)

    // --- Ordinary user pages ---

    @Test
    fun `an unlocked user page is allowed with no acknowledged risk`() {
        assertEquals(WriteDecision.Allowed(), evaluate(page = 7))
    }

    @Test
    fun `every unlocked user page is allowed`() {
        (4..15).forEach { page ->
            assertEquals(WriteDecision.Allowed(), evaluate(page), "page $page")
        }
    }

    // --- Locked pages ---

    @Test
    fun `a locked page is blocked`() {
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.PAGE_PERMANENTLY_LOCKED),
            evaluate(page = 7, locks = fullyLocked),
        )
    }

    @Test
    fun `expert mode does not unlock a locked page`() {
        // There is no unlock path on this chip; expert mode must not pretend otherwise.
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.PAGE_PERMANENTLY_LOCKED),
            evaluate(page = 7, locks = fullyLocked, expertMode = true),
        )
    }

    // --- UID pages ---

    @Test
    fun `the UID pages are blocked`() {
        listOf(0, 1).forEach { page ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.UID_HARDWARE_READ_ONLY),
                evaluate(page),
                "page $page",
            )
        }
    }

    @Test
    fun `expert mode does not open the UID pages`() {
        listOf(0, 1).forEach { page ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.UID_HARDWARE_READ_ONLY),
                evaluate(page, expertMode = true),
                "page $page",
            )
        }
    }

    // --- Lock control page ---

    @Test
    fun `the lock page requires expert mode`() {
        assertEquals(
            WriteDecision.RequiresExpertMode(WriteRiskReason.IRREVERSIBLE_LOCK_CONTROL),
            evaluate(page = 2),
        )
    }

    @Test
    fun `the lock page is allowed under expert mode but keeps the risk attached`() {
        assertEquals(
            WriteDecision.Allowed(WriteRiskReason.IRREVERSIBLE_LOCK_CONTROL),
            evaluate(page = 2, expertMode = true),
        )
    }

    // --- OTP page ---

    @Test
    fun `the OTP page requires expert mode`() {
        assertEquals(
            WriteDecision.RequiresExpertMode(WriteRiskReason.ONE_WAY_OTP),
            evaluate(page = 3),
        )
    }

    @Test
    fun `the OTP page is allowed under expert mode but keeps the risk attached`() {
        assertEquals(
            WriteDecision.Allowed(WriteRiskReason.ONE_WAY_OTP),
            evaluate(page = 3, expertMode = true),
        )
    }

    @Test
    fun `a locked OTP page is blocked even under expert mode`() {
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.PAGE_PERMANENTLY_LOCKED),
            evaluate(page = 3, locks = fullyLocked, expertMode = true),
        )
    }

    // --- Unknown lock state ---

    @Test
    fun `an unknown lock state blocks the write`() {
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.LOCK_STATE_UNKNOWN),
            evaluate(page = 7, locks = unknownLocks),
        )
    }

    @Test
    fun `expert mode does not override an unknown lock state`() {
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.LOCK_STATE_UNKNOWN),
            evaluate(page = 7, locks = unknownLocks, expertMode = true),
        )
    }

    @Test
    fun `the lock page is blocked when the lock state could not be read`() {
        // Lock bits are OR-only, so writing them from an unknown starting state cannot clear
        // anything -- but it can still permanently close pages the app never managed to read.
        // Setting lock bits blind is not a thing this tool should offer.
        listOf(false, true).forEach { expertMode ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.LOCK_STATE_UNKNOWN),
                evaluate(page = 2, locks = unknownLocks, expertMode = expertMode),
                "expertMode=$expertMode",
            )
        }
    }

    @Test
    fun `the OTP page is blocked when the lock state could not be read`() {
        listOf(false, true).forEach { expertMode ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.LOCK_STATE_UNKNOWN),
                evaluate(page = 3, locks = unknownLocks, expertMode = expertMode),
                "expertMode=$expertMode",
            )
        }
    }

    // --- Malformed input ---

    @Test
    fun `a payload that is not one page wide is blocked`() {
        listOf(0, 1, 3, 5, 16).forEach { size ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.INVALID_DATA_LENGTH),
                evaluate(page = 7, data = ByteArray(size)),
                "payload size $size",
            )
        }
    }

    @Test
    fun `a page outside the chip geometry is blocked`() {
        listOf(-1, 16, 99).forEach { page ->
            assertEquals(
                WriteDecision.Blocked(WriteBlockReason.INVALID_PAGE_INDEX),
                evaluate(page),
                "page $page",
            )
        }
    }

    // --- Invariant I3, swept rather than sampled ---

    @Test
    fun `no combination of inputs ever allows a UID page or a locked page`() {
        val allLockStates = listOf(unlocked, fullyLocked, unknownLocks)

        for (locks in allLockStates) {
            for (page in -1..16) {
                for (expertMode in listOf(false, true)) {
                    val decision = guard.evaluate(page, payload, locks, expertMode)
                    val isAllowed = decision is WriteDecision.Allowed

                    if (page in 0..1) {
                        assertTrue(
                            !isAllowed,
                            "UID page $page was allowed (expert=$expertMode)",
                        )
                    }
                    if (locks === fullyLocked && page in 3..15) {
                        assertTrue(
                            !isAllowed,
                            "locked page $page was allowed (expert=$expertMode)",
                        )
                    }
                    // Page 2 included: the lock-control page must not be writable blind
                    // either, which the earlier version of this sweep failed to assert.
                    if (locks === unknownLocks && page >= 2) {
                        assertTrue(
                            !isAllowed,
                            "page $page allowed with unknown locks (expert=$expertMode)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `expert mode never changes a blocked decision into an allowed one`() {
        val allLockStates = listOf(unlocked, fullyLocked, unknownLocks)

        for (locks in allLockStates) {
            for (page in -1..16) {
                val withoutExpert = guard.evaluate(page, payload, locks, expertMode = false)
                val withExpert = guard.evaluate(page, payload, locks, expertMode = true)

                if (withoutExpert is WriteDecision.Blocked) {
                    assertEquals(
                        withoutExpert,
                        withExpert,
                        "page $page changed under expert mode",
                    )
                }
            }
        }
    }

    @Test
    fun `the guard does not read or retain the caller's array`() {
        val mutable = ByteArray(4) { 0x11 }
        val decision = guard.evaluate(7, mutable, unlocked, expertMode = false)

        mutable[0] = 0x7F

        // The decision depends only on page and lock state, never on payload content.
        assertEquals(decision, guard.evaluate(7, mutable, unlocked, expertMode = false))
    }
}
