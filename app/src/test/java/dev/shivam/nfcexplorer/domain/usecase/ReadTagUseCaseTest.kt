package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.model.PageSnapshot
import dev.shivam.nfcexplorer.domain.model.ReadStatus
import dev.shivam.nfcexplorer.domain.model.TagPresentation
import dev.shivam.nfcexplorer.domain.model.WriteVerdict
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where invariant I2 is won or lost: a page index reported by the app must equal the physical
 * page on the tag.
 *
 * `READ` returns four pages and wraps past the end of memory, so a pipeline that appends
 * blindly reports early pages under late indices — a silently scrambled dump, which is the
 * worst possible failure for a tool whose entire purpose is reporting memory accurately.
 */
class ReadTagUseCaseTest {

    private val logger = SessionLogger { 0L }
    private val useCase = ReadTagUseCase(logger)

    private fun presentation(chip: ChipProfile = ChipProfile.MF0ICU1) = TagPresentation(
        uid = ByteBlock.copyOf(Mf0icu1Fixtures.SAMPLE_UID),
        atqa = ByteBlock.ofInts(0x44, 0x00),
        sak = 0x00,
        chip = chip,
    )

    private fun transport(
        memory: ByteArray = Mf0icu1Fixtures.blank(),
        failFromPage: Int? = null,
        nakPages: Set<Int> = emptySet(),
    ) = FakeUltralightTransport(memory, failFromPage, nakPages).apply { connect() }

    private fun snapshot(report: dev.shivam.nfcexplorer.domain.model.TagReport, page: Int) =
        requireNotNull(report.memory.page(page)) { "page $page missing from dump" }

    // --- Happy path ---

    @Test
    fun `a healthy tag yields every page in order`() {
        val report = useCase(transport(Mf0icu1Fixtures.hotelCardLike()), presentation())

        assertEquals((0..15).toList(), report.memory.pages.map { it.index })
        assertTrue(report.memory.isComplete)
        assertEquals(16, report.memory.readableCount)
    }

    @Test
    fun `page bytes match the physical page, not a shifted window`() {
        val source = transport(Mf0icu1Fixtures.hotelCardLike())
        val report = useCase(source, presentation())

        (0..15).forEach { page ->
            assertEquals(
                ByteBlock.copyOf(source.page(page)),
                snapshot(report, page).bytes,
                "page $page content",
            )
        }
    }

    @Test
    fun `the dump reads in four page strides rather than one page at a time`() {
        val source = transport()

        useCase(source, presentation())

        // 16 pages / 4 pages per READ. A page-at-a-time loop would issue 16 commands and
        // spend four times as long with the tag in the field.
        assertEquals(4, source.readCount)
    }

    // --- I2: wrap-around must be discarded, not appended ---

    @Test
    fun `the wrapped tail of the final read is discarded`() {
        // A 14-page chip over 16 bytes-worth of pages: the last READ at offset 12 returns
        // pages 12, 13, 14, 15, but only 12 and 13 exist as far as this chip is concerned.
        val chip = ChipProfile.MF0ICU1.copy(pageCount = 14, totalBytes = 56)
        val source = transport(Mf0icu1Fixtures.hotelCardLike())

        val report = useCase(source, presentation(chip))

        assertEquals((0..13).toList(), report.memory.pages.map { it.index })
        assertNull(report.memory.page(14), "page 14 must not appear for a 14-page chip")
        assertNull(report.memory.page(15))
        // And the pages that do exist must hold their own bytes, not shifted ones.
        assertEquals(ByteBlock.copyOf(source.page(12)), snapshot(report, 12).bytes)
        assertEquals(ByteBlock.copyOf(source.page(13)), snapshot(report, 13).bytes)
    }

    @Test
    fun `an odd page count still reports correct content in the final partial stride`() {
        // 15 pages: the final READ at offset 12 covers 12, 13, 14 plus one wrapped page.
        val chip = ChipProfile.MF0ICU1.copy(pageCount = 15, totalBytes = 60)
        val source = transport(Mf0icu1Fixtures.hotelCardLike())

        val report = useCase(source, presentation(chip))

        assertEquals(15, report.memory.pages.size)
        assertEquals(14, report.memory.pages.last().index)
        assertEquals(ByteBlock.copyOf(source.page(14)), snapshot(report, 14).bytes)
        // The wrapped page 0 must not have been recorded as page 15.
        assertNull(report.memory.page(15))
    }

    // --- Partial dumps ---

    @Test
    fun `a tag pulled away mid dump keeps what was read and marks the rest`() {
        val report = useCase(transport(failFromPage = 8), presentation())

        (0..7).forEach { page ->
            assertEquals(ReadStatus.OK, snapshot(report, page).status, "page $page")
        }
        // The stride that was in flight was attempted and lost...
        (8..11).forEach { page ->
            assertEquals(ReadStatus.TAG_LOST, snapshot(report, page).status, "page $page")
        }
        // ...and everything after it was never asked for. Those are different facts.
        (12..15).forEach { page ->
            assertEquals(ReadStatus.NOT_ATTEMPTED, snapshot(report, page).status, "page $page")
        }
        assertFalse(report.memory.isComplete)
    }

    @Test
    fun `tag loss stops the dump instead of hammering a tag that has gone`() {
        val source = transport(failFromPage = 8)

        useCase(source, presentation())

        // Strides at 0 and 4 succeeded, the stride at 8 threw. No further attempts.
        assertEquals(2, source.readCount)
    }

    @Test
    fun `a refused page marks its window and the dump continues`() {
        val report = useCase(transport(nakPages = setOf(6)), presentation())

        // The chip NAKs the whole four-page command, so 4..7 are all refused.
        (4..7).forEach { page ->
            assertEquals(ReadStatus.NAK_REFUSED, snapshot(report, page).status, "page $page")
        }
        // But the dump carries on past it rather than giving up.
        (8..15).forEach { page ->
            assertEquals(ReadStatus.OK, snapshot(report, page).status, "page $page")
        }
        assertEquals(12, report.memory.readableCount)
    }

    @Test
    fun `an unreadable page carries no bytes so it cannot render as zeros`() {
        val report = useCase(transport(nakPages = setOf(6)), presentation())

        (4..7).forEach { page ->
            assertNull(snapshot(report, page).bytes, "page $page")
        }
    }

    // --- Decoding on top of the dump ---

    @Test
    fun `identity is decoded from the dumped check bytes`() {
        val report = useCase(transport(), presentation())

        assertEquals(true, report.identity.bcc0?.isValid)
        assertEquals(true, report.identity.bcc1?.isValid)
        assertEquals(2, report.identity.cascadeLevels)
        assertEquals(ByteBlock.ofInts(0x44, 0x00), report.identity.atqa)
    }

    @Test
    fun `lock analysis comes from the dumped lock page`() {
        val report = useCase(transport(Mf0icu1Fixtures.hotelCardLike()), presentation())

        assertEquals(ByteBlock.ofInts(0xF8, 0xFF), report.locks.staticLockBytes)
        assertEquals((3..15).toList(), report.locks.lockedPages)
        assertTrue(report.locks.writablePages.isEmpty())
    }

    @Test
    fun `an unreadable lock page yields unknown verdicts rather than writable ones`() {
        // Page 2 sits in the first stride, so refusing page 0 takes the lock bytes with it.
        val report = useCase(transport(nakPages = setOf(0)), presentation())

        assertNull(report.locks.staticLockBytes)
        (2..15).forEach { page ->
            assertEquals(
                WriteVerdict.UNKNOWN_LOCK_STATE,
                requireNotNull(report.locks.accessFor(page)).verdict,
                "page $page",
            )
        }
    }

    @Test
    fun `identity check bytes are null when their pages could not be read`() {
        val report = useCase(transport(nakPages = setOf(0)), presentation())

        assertNull(report.identity.bcc0)
        assertNull(report.identity.bcc1)
    }

    // --- Geometry guards ---

    @Test
    fun `an unidentified chip produces an empty dump rather than reading blind`() {
        val source = transport()

        val report = useCase(source, presentation(ChipProfile.UNIDENTIFIED))

        assertTrue(report.memory.pages.isEmpty())
        assertEquals(0, source.readCount)
    }

    // --- Logging ---

    @Test
    fun `the session log records the read and its failures`() {
        val report = useCase(transport(nakPages = setOf(6)), presentation())

        val entries = logger.entries.value
        assertTrue(entries.isNotEmpty(), "read produced no log entries")
        assertTrue(
            entries.any { it.level == LogLevel.WARN || it.level == LogLevel.ERROR },
            "a refused read should be logged above INFO",
        )
        assertTrue(
            entries.any { it.payload.containsKey("page") || it.payload.containsKey("pageOffset") },
            "failure entries should carry the page in structured payload",
        )
        assertEquals(12, report.memory.readableCount)
    }

    @Test
    fun `the log carries the full memory image so a dump is recoverable from logcat`() {
        // A debugging tool whose dump only exists on screen is much less useful: evidence has to
        // survive as text that can be captured, diffed and attached to a report.
        val source = transport(Mf0icu1Fixtures.hotelCardLike())

        useCase(source, presentation())

        val image = logger.entries.value
            .firstNotNullOfOrNull { it.payload["image"] }
            ?: error("no log entry carried the memory image")

        // Every readable page's bytes must appear, in order.
        assertTrue(image.contains("5A 11 03 7C"), "page 4 content missing from image: $image")
        assertTrue(image.contains("21 08 14 06"), "page 6 content missing from image: $image")
    }

    @Test
    fun `the log carries the decoded lock state`() {
        useCase(transport(Mf0icu1Fixtures.hotelCardLike()), presentation())

        val entry = logger.entries.value.firstOrNull { it.payload.containsKey("lockBytes") }
            ?: error("no log entry carried the lock state")

        assertEquals("F8 FF", entry.payload["lockBytes"])
        assertEquals((3..15).joinToString(), entry.payload["lockedPages"])
    }

    @Test
    fun `an unreadable page is marked in the logged image rather than shown as zeros`() {
        useCase(transport(nakPages = setOf(6)), presentation())

        val image = logger.entries.value
            .firstNotNullOfOrNull { it.payload["image"] }
            ?: error("no log entry carried the memory image")

        // Pages 4-7 refused; they must not appear as plausible data.
        assertTrue(image.contains("??"), "unreadable pages should be marked, got: $image")
    }

    @Test
    fun `pages are reported in ascending order with no duplicates`() {
        val report = useCase(transport(nakPages = setOf(6), failFromPage = 12), presentation())

        val indices = report.memory.pages.map(PageSnapshot::index)
        assertEquals(indices.sorted(), indices)
        assertEquals(indices.size, indices.toSet().size)
    }
}
