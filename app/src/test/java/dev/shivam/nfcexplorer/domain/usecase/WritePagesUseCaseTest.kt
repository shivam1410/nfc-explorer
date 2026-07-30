package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.decoder.StaticLockDecoder
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.writer.PageEncoder
import dev.shivam.nfcexplorer.domain.writer.WriteGuard
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import dev.shivam.nfcexplorer.logging.SessionLogger
import dev.shivam.nfcexplorer.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WritePagesUseCaseTest {

    private val logger = SessionLogger { 0L }
    private val guard = WriteGuard()
    private val useCase = WritePagesUseCase(WritePageUseCase(guard, logger), logger)

    private val unlocked = StaticLockDecoder.decode(ByteBlock.ofInts(0x00, 0x00))
    private val lockedFrom7 = StaticLockDecoder.decode(ByteBlock.ofInts(0x80, 0x00)) // L_7

    private fun transport(memory: ByteArray = Mf0icu1Fixtures.blank()) =
        FakeUltralightTransport(memory).apply { connect() }

    // --- Happy path ---

    @Test
    fun `every page in the run is written at the right address`() {
        val source = transport()
        val pages = requireNotNull(PageEncoder.fromText("HELLO WORLD!", pageCount = 3))

        val result = useCase(source, startPage = 4, pages = pages, locks = unlocked, expertMode = false)

        assertTrue(result.allSucceeded)
        assertEquals(3, result.writtenCount)
        assertEquals("48 45 4C 4C", source.page(4).toHex()) // "HELL"
        assertEquals("4F 20 57 4F", source.page(5).toHex()) // "O WO"
        assertEquals("52 4C 44 21", source.page(6).toHex()) // "RLD!"
        // The page after the run is untouched.
        assertEquals("00 00 00 00", source.page(7).toHex())
    }

    @Test
    fun `a wipe zeroes the requested run and nothing else`() {
        // The real card: unlocked, but carrying a payload. hotelCardLike() would be wrong here --
        // its lock bytes are set, which cannot coexist with the unlocked analysis passed below.
        val source = transport(Mf0icu1Fixtures.unlockedHotelCard())
        val before3 = source.page(3).toHex()
        assertEquals("E2 42 1B 5E", source.page(4).toHex(), "fixture should start with payload")

        val result = useCase(
            source,
            startPage = 4,
            pages = PageEncoder.zeros(12),
            locks = unlocked,
            expertMode = false,
        )

        assertTrue(result.allSucceeded)
        (4..15).forEach { page ->
            assertEquals("00 00 00 00", source.page(page).toHex(), "page $page")
        }
        // OTP is outside the run and must be untouched -- it could not be undone anyway.
        assertEquals(before3, source.page(3).toHex())
    }

    @Test
    fun `an empty run writes nothing`() {
        val source = transport()

        val result = useCase(source, startPage = 4, pages = emptyList(), locks = unlocked, expertMode = false)

        assertEquals(0, result.pagesRequested)
        assertTrue(source.writes.isEmpty())
        assertFalse(result.allSucceeded)
    }

    // --- Stopping behaviour ---

    @Test
    fun `the batch stops at the first refused page`() {
        val source = transport()
        // Pages 4-6 are writable, page 7 is locked by L_7.
        val result = useCase(
            source,
            startPage = 4,
            pages = PageEncoder.zeros(6),
            locks = lockedFrom7,
            expertMode = false,
        )

        assertFalse(result.allSucceeded)
        assertTrue(result.isPartial)
        assertEquals(3, result.writtenCount)
        // Four outcomes: three written plus the refusal that stopped it. Pages 8 and 9 were never
        // attempted, because whatever refused page 7 would very likely refuse them too.
        assertEquals(4, result.outcomes.size)
        assertIs<WriteOutcome.Refused>(result.stoppedBy)
    }

    @Test
    fun `pages after the stopping point are never attempted`() {
        val source = transport()

        useCase(source, startPage = 4, pages = PageEncoder.zeros(6), locks = lockedFrom7, expertMode = false)

        val attemptedPages = source.writes.map { it.page }
        assertEquals(listOf(4, 5, 6), attemptedPages)
    }

    @Test
    fun `a run reaching the UID pages is refused immediately`() {
        val source = transport()

        val result = useCase(
            source,
            startPage = 0,
            pages = PageEncoder.zeros(2),
            locks = unlocked,
            expertMode = true,
        )

        assertEquals(0, result.writtenCount)
        assertIs<WriteOutcome.Refused>(result.stoppedBy)
        assertTrue(source.writes.isEmpty(), "the UID pages must never be attempted")
    }

    @Test
    fun `a run crossing the lock page stops there without expert mode`() {
        val source = transport()

        // Starting at 2 means the very first page is the lock-control page.
        val result = useCase(
            source,
            startPage = 2,
            pages = PageEncoder.zeros(3),
            locks = unlocked,
            expertMode = false,
        )

        assertEquals(0, result.writtenCount)
        assertIs<WriteOutcome.Refused>(result.stoppedBy)
        assertTrue(source.writes.isEmpty())
    }

    // --- Results carry enough to explain themselves ---

    @Test
    fun `each outcome names its own page`() {
        val source = transport()

        val result = useCase(source, startPage = 9, pages = PageEncoder.zeros(3), locks = unlocked, expertMode = false)

        assertEquals(listOf(9, 10, 11), result.outcomes.map { outcome ->
            when (outcome) {
                is WriteOutcome.Written -> outcome.page
                is WriteOutcome.Refused -> outcome.page
                is WriteOutcome.Failed -> outcome.page
            }
        })
        assertEquals(9, result.startPage)
    }

    @Test
    fun `a fully successful batch has nothing that stopped it`() {
        val source = transport()

        val result = useCase(source, startPage = 4, pages = PageEncoder.zeros(2), locks = unlocked, expertMode = false)

        assertNull(result.stoppedBy)
        assertFalse(result.isPartial)
    }

    @Test
    fun `written outcomes carry the read back so success is demonstrated`() {
        val source = transport()
        val pages = requireNotNull(PageEncoder.fromHex("DEADBEEF", pageCount = 1))

        val result = useCase(source, startPage = 5, pages = pages, locks = unlocked, expertMode = false)

        val written = result.outcomes.single()
        assertIs<WriteOutcome.Written>(written)
        assertEquals(ByteBlock.ofInts(0xDE, 0xAD, 0xBE, 0xEF), written.readBack)
        assertEquals(true, written.verified)
    }

    // --- Logging ---

    @Test
    fun `the batch is summarised in the session log`() {
        useCase(transport(), startPage = 4, pages = PageEncoder.zeros(3), locks = unlocked, expertMode = false)

        assertTrue(
            logger.entries.value.any { it.payload.containsKey("pagesWritten") },
            "a batch summary should be logged",
        )
    }
}
