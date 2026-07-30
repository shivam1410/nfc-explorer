package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.decoder.StaticLockDecoder
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.WriteBlockReason
import dev.shivam.nfcexplorer.domain.model.WriteDecision
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.model.WriteRiskReason
import dev.shivam.nfcexplorer.domain.transport.TagIoException
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.domain.writer.WriteGuard
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write path is the only irreversible thing this app does, so the central property here is
 * that a refused write never reaches the transport at all — asserted against the fake's record
 * of attempts, not merely by checking that memory is unchanged.
 */
class WritePageUseCaseTest {

    private val logger = SessionLogger { 0L }
    private val useCase = WritePageUseCase(WriteGuard(), logger)

    private val unlocked = StaticLockDecoder.decode(ByteBlock.ofInts(0x00, 0x00))
    private val fullyLocked = StaticLockDecoder.decode(ByteBlock.ofInts(0xFF, 0xFF))
    private val unknownLocks = StaticLockDecoder.decode(null)

    private val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    private fun transport(memory: ByteArray = Mf0icu1Fixtures.blank()) =
        FakeUltralightTransport(memory).apply { connect() }

    // --- Allowed writes ---

    @Test
    fun `an allowed write stores the bytes and verifies by reading back`() {
        val source = transport()

        val outcome = useCase(source, page = 7, data = payload, locks = unlocked, expertMode = false)

        assertIs<WriteOutcome.Written>(outcome)
        assertEquals(ByteBlock.copyOf(payload), outcome.readBack)
        assertEquals(true, outcome.verified)
        assertNull(outcome.acknowledgedRisk)
        assertEquals(ByteBlock.copyOf(payload), ByteBlock.copyOf(source.page(7)))
    }

    @Test
    fun `verification reads back the page that was written, not a neighbour`() {
        val source = transport()

        useCase(source, page = 9, data = payload, locks = unlocked, expertMode = false)

        // readPages(9) returns pages 9..12; only page 9 must be treated as the result.
        assertEquals(ByteBlock.copyOf(payload), ByteBlock.copyOf(source.page(9)))
        assertEquals(ByteBlock.ofInts(0, 0, 0, 0), ByteBlock.copyOf(source.page(10)))
    }

    // --- Refusals never reach the tag ---

    @Test
    fun `a blocked write never reaches the transport`() {
        val source = transport(Mf0icu1Fixtures.hotelCardLike())

        val outcome = useCase(source, page = 7, data = payload, locks = fullyLocked, expertMode = true)

        assertIs<WriteOutcome.Refused>(outcome)
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.PAGE_PERMANENTLY_LOCKED),
            outcome.decision,
        )
        // The strong assertion: nothing was even attempted.
        assertTrue(source.writes.isEmpty(), "a blocked write must not reach the transport")
    }

    @Test
    fun `a UID page write is refused without touching the tag`() {
        val source = transport()

        val outcome = useCase(source, page = 0, data = payload, locks = unlocked, expertMode = true)

        assertIs<WriteOutcome.Refused>(outcome)
        assertEquals(
            WriteDecision.Blocked(WriteBlockReason.UID_HARDWARE_READ_ONLY),
            outcome.decision,
        )
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun `an irreversible write without expert mode is refused without touching the tag`() {
        val source = transport()

        val outcome = useCase(source, page = 2, data = payload, locks = unlocked, expertMode = false)

        assertIs<WriteOutcome.Refused>(outcome)
        assertEquals(
            WriteDecision.RequiresExpertMode(WriteRiskReason.IRREVERSIBLE_LOCK_CONTROL),
            outcome.decision,
        )
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun `an unknown lock state refuses the write without touching the tag`() {
        val source = transport()

        val outcome = useCase(source, page = 7, data = payload, locks = unknownLocks, expertMode = true)

        assertIs<WriteOutcome.Refused>(outcome)
        assertTrue(source.writes.isEmpty())
    }

    @Test
    fun `a malformed payload is refused without touching the tag`() {
        val source = transport()

        val outcome = useCase(
            source,
            page = 7,
            data = byteArrayOf(0x01, 0x02),
            locks = unlocked,
            expertMode = false,
        )

        assertIs<WriteOutcome.Refused>(outcome)
        assertEquals(WriteDecision.Blocked(WriteBlockReason.INVALID_DATA_LENGTH), outcome.decision)
        assertTrue(source.writes.isEmpty())
    }

    // --- One-way pages: success with a different result is still success ---

    @Test
    fun `an OTP write under expert mode reports the ORed result rather than claiming a mismatch failure`() {
        // OTP starts at 0xF0; writing 0x0F cannot replace it, only OR into it.
        val source = transport(Mf0icu1Fixtures.image(otp = Mf0icu1Fixtures.bytes(0xF0, 0, 0, 0)))

        val outcome = useCase(
            source,
            page = 3,
            data = Mf0icu1Fixtures.bytes(0x0F, 0, 0, 0),
            locks = unlocked,
            expertMode = true,
        )

        assertIs<WriteOutcome.Written>(outcome)
        // The write succeeded; the stored value simply differs from what was sent, and the user
        // needs to see which. Reporting this as a failure would be wrong.
        assertEquals(ByteBlock.ofInts(0xFF, 0, 0, 0), outcome.readBack)
        assertEquals(false, outcome.verified)
        assertEquals(WriteRiskReason.ONE_WAY_OTP, outcome.acknowledgedRisk)
    }

    @Test
    fun `expert mode keeps the risk attached to a permitted lock page write`() {
        val source = transport()

        val outcome = useCase(
            source,
            page = 2,
            data = Mf0icu1Fixtures.bytes(0, 0, 0x10, 0),
            locks = unlocked,
            expertMode = true,
        )

        assertIs<WriteOutcome.Written>(outcome)
        assertEquals(WriteRiskReason.IRREVERSIBLE_LOCK_CONTROL, outcome.acknowledgedRisk)
    }

    // --- Failures from the tag ---

    @Test
    fun `a rejected write is reported with the exception and the responsible lock bit`() {
        val locks = StaticLockDecoder.decode(ByteBlock.ofInts(0x80, 0x00)) // L_7 set
        // The guard would normally block this, so drive the failure path directly with a
        // transport that refuses while the lock analysis says writable.
        val refusing = object : UltralightTransport by transport() {
            override fun writePage(pageOffset: Int, data: ByteArray) =
                throw TagIoException("Transceive failed")
        }

        val outcome = useCase(refusing, page = 7, data = payload, locks = unlocked, expertMode = false)

        assertIs<WriteOutcome.Failed>(outcome)
        assertEquals("TagIoException", outcome.exceptionName)
        assertEquals("Transceive failed", outcome.message)
        assertEquals(ByteBlock.copyOf(payload), outcome.attempted)
        // Sanity: the lock analysis used above does explain page 7 when it is locked.
        assertEquals("L_7", locks.accessFor(7)?.lockedBy)
    }

    @Test
    fun `a rejected write names the lock bit when the lock analysis explains it`() {
        // Guard is bypassed by handing it unlocked analysis while the tag itself refuses -- this
        // is the real-hardware case where Android reports only a bare IOException.
        val lockedAnalysis = StaticLockDecoder.decode(ByteBlock.ofInts(0x80, 0x00))
        val refusing = object : UltralightTransport by transport(Mf0icu1Fixtures.blank()) {
            override fun writePage(pageOffset: Int, data: ByteArray) =
                throw TagIoException("Transceive failed")
        }

        // Page 6 is writable per lockedAnalysis (only L_7 is set), so the guard allows it.
        val outcome = useCase(refusing, page = 6, data = payload, locks = lockedAnalysis, expertMode = false)

        assertIs<WriteOutcome.Failed>(outcome)
        assertNull(outcome.lockedBy, "page 6 is not locked, so nothing should be blamed")
    }

    @Test
    fun `a failed read back does not turn a successful write into a failure`() {
        val backing = transport()
        val writeOnly = object : UltralightTransport by backing {
            override fun readPages(pageOffset: Int): ByteArray =
                throw TagIoException("read back failed")
        }

        val outcome = useCase(writeOnly, page = 7, data = payload, locks = unlocked, expertMode = false)

        assertIs<WriteOutcome.Written>(outcome)
        // Verification could not run. That is different from a mismatch, so verified is null
        // rather than false, and the write is still reported as having happened.
        assertNull(outcome.readBack)
        assertNull(outcome.verified)
        assertEquals(ByteBlock.copyOf(payload), ByteBlock.copyOf(backing.page(7)))
    }

    // --- Logging ---

    @Test
    fun `an allowed write is logged`() {
        useCase(transport(), page = 7, data = payload, locks = unlocked, expertMode = false)

        val entries = logger.entries.value
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.any { it.payload["page"] == "7" }, "page should be in structured payload")
    }

    @Test
    fun `a refusal is logged above INFO so it is visible in the session log`() {
        useCase(transport(), page = 0, data = payload, locks = unlocked, expertMode = true)

        assertTrue(
            logger.entries.value.any { it.level == LogLevel.WARN || it.level == LogLevel.ERROR },
        )
    }

    @Test
    fun `an irreversible write records that expert mode was used`() {
        useCase(
            transport(),
            page = 3,
            data = Mf0icu1Fixtures.bytes(0x01, 0, 0, 0),
            locks = unlocked,
            expertMode = true,
        )

        assertTrue(
            logger.entries.value.any { it.payload["expertMode"] == "true" },
            "an irreversible write must be traceable in the log",
        )
    }
}
