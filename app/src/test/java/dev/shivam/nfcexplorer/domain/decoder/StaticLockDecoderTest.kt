package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.DynamicLockSupport
import dev.shivam.nfcexplorer.domain.model.PageRole
import dev.shivam.nfcexplorer.domain.model.WriteVerdict
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The most consequential decoder in Phase 1: it decides which pages the app will offer to
 * write. Every documented bit is asserted individually, because a single mask off by one
 * position would mislabel a page and either hide a writable page or invite a write to a
 * locked one.
 *
 * Bit tables: `docs/mf0icu1-reference.md`.
 */
class StaticLockDecoderTest {

    private fun decode(lock0: Int, lock1: Int) =
        StaticLockDecoder.decode(ByteBlock.ofInts(lock0, lock1))

    // --- Structural roles, independent of lock state ---

    @Test
    fun `UID pages are read only in hardware`() {
        val analysis = decode(0x00, 0x00)

        listOf(0, 1).forEach { page ->
            val access = analysis.accessFor(page)!!
            assertEquals(PageRole.UID, access.role)
            assertEquals(WriteVerdict.HARDWARE_READ_ONLY, access.verdict)
            assertNull(access.lockedBy)
        }
    }

    @Test
    fun `the lock page is reported as lock control`() {
        val access = decode(0x00, 0x00).accessFor(2)!!

        assertEquals(PageRole.LOCK_CONTROL, access.role)
        assertEquals(WriteVerdict.LOCK_CONTROL, access.verdict)
    }

    @Test
    fun `every page of the chip is covered exactly once`() {
        val analysis = decode(0x00, 0x00)

        assertEquals((0..15).toList(), analysis.pageAccess.map { it.page })
    }

    // --- Unlocked tag ---

    @Test
    fun `an unlocked tag has all user pages writable and OTP one way`() {
        val analysis = decode(0x00, 0x00)

        (4..15).forEach { page ->
            assertEquals(WriteVerdict.WRITABLE, analysis.accessFor(page)!!.verdict, "page $page")
        }
        assertEquals(WriteVerdict.OTP_ONE_WAY, analysis.accessFor(3)!!.verdict)
        assertEquals((4..15).toList(), analysis.writablePages)
    }

    // --- Fully locked tag ---

    @Test
    fun `a fully locked tag has OTP and every user page permanently locked`() {
        val analysis = decode(0xFF, 0xFF)

        (3..15).forEach { page ->
            assertEquals(
                WriteVerdict.PERMANENTLY_LOCKED,
                analysis.accessFor(page)!!.verdict,
                "page $page",
            )
        }
        assertTrue(analysis.writablePages.isEmpty())
        assertEquals((3..15).toList(), analysis.lockedPages)
    }

    // --- Individual lock bits ---

    @Test
    fun `L_OTP locks only the OTP page`() {
        val analysis = decode(0x08, 0x00)

        assertEquals(WriteVerdict.PERMANENTLY_LOCKED, analysis.accessFor(3)!!.verdict)
        assertEquals("L_OTP", analysis.accessFor(3)!!.lockedBy)
        assertEquals(listOf(3), analysis.lockedPages)
    }

    @Test
    fun `L_4 locks only page 4`() {
        val analysis = decode(0x10, 0x00)

        assertEquals(listOf(4), analysis.lockedPages)
        assertEquals("L_4", analysis.accessFor(4)!!.lockedBy)
        assertEquals(WriteVerdict.WRITABLE, analysis.accessFor(5)!!.verdict)
    }

    @Test
    fun `L_7 is the top bit of LOCK0`() {
        val analysis = decode(0x80, 0x00)

        assertEquals(listOf(7), analysis.lockedPages)
        assertEquals("L_7", analysis.accessFor(7)!!.lockedBy)
    }

    @Test
    fun `L_8 is the bottom bit of LOCK1`() {
        val analysis = decode(0x00, 0x01)

        assertEquals(listOf(8), analysis.lockedPages)
        assertEquals("L_8", analysis.accessFor(8)!!.lockedBy)
    }

    @Test
    fun `L_15 is the top bit of LOCK1`() {
        val analysis = decode(0x00, 0x80)

        assertEquals(listOf(15), analysis.lockedPages)
        assertEquals("L_15", analysis.accessFor(15)!!.lockedBy)
    }

    @Test
    fun `each LOCK1 bit maps to its own page in order`() {
        (8..15).forEach { page ->
            val analysis = decode(0x00, 1 shl (page - 8))
            assertEquals(listOf(page), analysis.lockedPages, "LOCK1 bit for page $page")
        }
    }

    @Test
    fun `each LOCK0 data lock bit maps to its own page in order`() {
        (4..7).forEach { page ->
            val analysis = decode(1 shl page, 0x00)
            assertEquals(listOf(page), analysis.lockedPages, "LOCK0 bit for page $page")
        }
    }

    // --- Block-locking bits: freezing is not the same as locking ---

    @Test
    fun `BL_9_4 freezes the lock bits for pages 4 to 9 without locking them`() {
        val analysis = decode(0x02, 0x00)

        // Still writable - and now permanently so, since the lock bits can never change.
        (4..9).forEach { page ->
            assertEquals(WriteVerdict.WRITABLE, analysis.accessFor(page)!!.verdict, "page $page")
        }
        (4..9).forEach { page ->
            assertTrue(analysis.lockBits.single { it.protectedPage == page }.isFrozen, "page $page")
        }
        // Pages outside the range are untouched.
        (10..15).forEach { page ->
            assertFalse(analysis.lockBits.single { it.protectedPage == page }.isFrozen, "page $page")
        }
    }

    @Test
    fun `BL_15_10 freezes the lock bits for pages 10 to 15`() {
        val analysis = decode(0x04, 0x00)

        (10..15).forEach { page ->
            assertTrue(analysis.lockBits.single { it.protectedPage == page }.isFrozen, "page $page")
        }
        (4..9).forEach { page ->
            assertFalse(analysis.lockBits.single { it.protectedPage == page }.isFrozen, "page $page")
        }
    }

    @Test
    fun `BL_OTP freezes the OTP lock bit`() {
        val analysis = decode(0x01, 0x00)

        assertTrue(analysis.lockBits.single { it.protectedPage == 3 }.isFrozen)
    }

    @Test
    fun `block locking bits report the ranges they freeze`() {
        val analysis = decode(0x07, 0x00)

        val byName = analysis.blockLockBits.associateBy { it.name }
        assertEquals(3..3, byName["BL_OTP"]!!.freezesPages)
        assertEquals(4..9, byName["BL_9_4"]!!.freezesPages)
        assertEquals(10..15, byName["BL_15_10"]!!.freezesPages)
        assertTrue(analysis.blockLockBits.all { it.isSet })
    }

    @Test
    fun `block locking bits are clear on an unlocked tag`() {
        assertTrue(decode(0x00, 0x00).blockLockBits.none { it.isSet })
    }

    // --- Dynamic lock bits: absent, and reported as absent ---

    @Test
    fun `dynamic lock bits are reported as unsupported by this chip`() {
        val support = decode(0x00, 0x00).dynamicLockSupport

        // Must not be reported as Present with zero bytes - MF0ICU1 has no such bytes at all.
        assertTrue(support is DynamicLockSupport.NotSupportedByChip)
        assertTrue(support.introducedIn.isNotBlank())
    }

    // --- Unreadable lock page ---

    @Test
    fun `an unreadable lock page yields unknown rather than writable`() {
        val analysis = StaticLockDecoder.decode(null)

        assertNull(analysis.staticLockBytes)
        (3..15).forEach { page ->
            assertEquals(
                WriteVerdict.UNKNOWN_LOCK_STATE,
                analysis.accessFor(page)!!.verdict,
                "page $page",
            )
        }
        assertTrue(analysis.writablePages.isEmpty())
        assertTrue(analysis.lockBits.isEmpty())
        assertTrue(analysis.blockLockBits.isEmpty())
    }

    @Test
    fun `structural roles survive an unreadable lock page`() {
        val analysis = StaticLockDecoder.decode(null)

        assertEquals(WriteVerdict.HARDWARE_READ_ONLY, analysis.accessFor(0)!!.verdict)
        assertEquals(WriteVerdict.LOCK_CONTROL, analysis.accessFor(2)!!.verdict)
    }

    // --- Cross-check against the chip emulation ---

    @Test
    fun `every page the decoder calls locked is actually rejected by the chip`() {
        // FakeUltralightTransport derives lock state from memory independently of this
        // decoder. Agreement between two separate implementations is far stronger evidence
        // than either one matching its own assumptions.
        listOf(0x00 to 0x00, 0xFF to 0xFF, 0x08 to 0x00, 0x10 to 0x00, 0xF8 to 0xFF, 0x00 to 0x81)
            .forEach { (lock0, lock1) ->
                val analysis = decode(lock0, lock1)
                val transport = FakeUltralightTransport(
                    Mf0icu1Fixtures.image(lock0 = lock0, lock1 = lock1),
                ).apply { connect() }

                (4..15).forEach { page ->
                    val verdict = analysis.accessFor(page)!!.verdict
                    val rejected = runCatching {
                        transport.writePage(page, ByteArray(4) { 0x11 })
                    }.isFailure

                    assertEquals(
                        verdict == WriteVerdict.PERMANENTLY_LOCKED,
                        rejected,
                        "page $page with LOCK0=${lock0.toString(16)} LOCK1=${lock1.toString(16)}: " +
                            "decoder said $verdict but chip rejected=$rejected",
                    )
                }
            }
    }

    // --- Input validation ---

    @Test
    fun `lock bit list covers OTP plus all twelve user pages`() {
        val analysis = decode(0x00, 0x00)

        assertEquals(13, analysis.lockBits.size)
        assertEquals(listOf(3) + (4..15), analysis.lockBits.map { it.protectedPage })
    }
}
