package dev.shivam.nfcexplorer.domain.export

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.model.TagPresentation
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.usecase.ReadTagUseCase
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionExporterTest {

    private val logger = SessionLogger { 1_700_000_000_000L }

    private fun report(
        memory: ByteArray = Mf0icu1Fixtures.unlockedHotelCard(),
        nakPages: Set<Int> = emptySet(),
    ): TagReport {
        val transport = FakeUltralightTransport(memory, nakPages = nakPages).apply { connect() }
        return ReadTagUseCase(logger)(
            transport,
            TagPresentation(
                uid = ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81),
                atqa = ByteBlock.ofInts(0x44, 0x00),
                sak = 0x00,
                chip = ChipProfile.MF0ICU1,
            ),
        )
    }

    private fun json(report: TagReport? = report()) =
        JsonSessionExporter.export(report, logger.entries.value, exportedAtMillis = 1_700_000_000_000L)

    private fun text(report: TagReport? = report()) =
        TextSessionExporter.export(report, logger.entries.value, exportedAtMillis = 1_700_000_000_000L)

    // --- JSON ---

    @Test
    fun `json carries a schema version so a future format change is detectable`() {
        assertTrue(json().contains("\"schemaVersion\":1"), json())
    }

    @Test
    fun `json carries the identity including both check bytes`() {
        val output = json()

        assertTrue(output.contains("\"uid\":\"04 0E 66 A2 F0 7B 81\""), output)
        assertTrue(output.contains("\"cascadeLevels\":2"))
        assertTrue(output.contains("\"NXP Semiconductors\""))
        assertTrue(output.contains("\"bcc0\""))
        assertTrue(output.contains("\"valid\":true"))
    }

    @Test
    fun `json carries chip geometry and whether it is confirmed`() {
        val output = json()

        assertTrue(output.contains("\"pageCount\":16"), output)
        assertTrue(output.contains("\"geometryConfirmed\":true"))
    }

    @Test
    fun `json carries every page with its hex`() {
        val output = json()

        assertTrue(output.contains("\"hex\":\"04 0E 66 E4\""), output)
        assertTrue(output.contains("\"hex\":\"E2 42 1B 5E\""))
        assertTrue(output.contains("\"pagesTotal\":16"))
    }

    @Test
    fun `an unreadable page exports a null hex rather than zeros`() {
        // The single most important property of the whole export: a page that never read must not
        // appear in a file as though it held real zeros.
        val output = json(report(nakPages = setOf(0)))

        // The refused page itself must carry a null hex, checked on its own object rather than by
        // looking for "null" anywhere in the file.
        assertTrue(
            output.contains("""{"index":0,"status":"NAK_REFUSED","hex":null"""),
            output,
        )
    }

    @Test
    fun `a genuinely zero page and an unreadable page are distinguishable in the export`() {
        // The property that matters, and the reason null is used instead of zeros: pages 0A-0F of
        // this card really are 00 00 00 00, while page 0 was refused. A reader of the file must be
        // able to tell those two apart -- otherwise the export loses information the session had.
        val output = json(report(nakPages = setOf(0)))

        assertTrue(
            output.contains("""{"index":0,"status":"NAK_REFUSED","hex":null"""),
            "refused page should have null hex",
        )
        assertTrue(
            output.contains("""{"index":10,"status":"OK","hex":"00 00 00 00""""),
            "a real zero page should keep its zeros",
        )
    }

    @Test
    fun `json carries the lock analysis`() {
        val output = json()

        assertTrue(output.contains("\"lockBytes\":\"00 00\""), output)
        assertTrue(output.contains("\"writablePages\":[4,5,6,7,8,9,10,11,12,13,14,15]"))
        assertTrue(output.contains("\"dynamicLockBits\""))
        assertTrue(output.contains("\"supported\":false"))
    }

    @Test
    fun `json carries the session log`() {
        val output = json()

        assertTrue(output.contains("\"log\":["), output)
        assertTrue(output.contains("\"level\":\"INFO\""))
        assertTrue(output.contains("\"sequence\":0"))
    }

    @Test
    fun `json escapes log content rather than emitting it raw`() {
        val escaping = SessionLogger { 0L }
        escaping.log(LogLevel.ERROR, "write", "he said \"no\"\nand stopped")

        val output = JsonSessionExporter.export(null, escaping.entries.value, 0L)

        assertTrue(output.contains("""he said \"no\"\nand stopped"""), output)
        // The raw newline must not survive into the file.
        assertFalse(output.contains("and stopped\n\""))
    }

    @Test
    fun `json is still valid with no tag scanned`() {
        val output = json(report = null)

        assertTrue(output.contains("\"tag\":null"), output)
        assertTrue(output.startsWith("{") && output.endsWith("}"))
    }

    // --- Text ---

    @Test
    fun `text contains a classic hex dump block`() {
        val output = text()

        assertTrue(output.contains("00  04 0E 66 E4"), output)
        assertTrue(output.contains("04  E2 42 1B 5E"))
    }

    @Test
    fun `text marks unreadable pages instead of printing zeros`() {
        val output = text(report(nakPages = setOf(0)))

        assertTrue(output.contains("??"), output)
        assertFalse(output.contains("00  00 00 00 00"))
    }

    @Test
    fun `text contains identity, lock summary and the log`() {
        val output = text()

        assertTrue(output.contains("04 0E 66 A2 F0 7B 81"), output)
        assertTrue(output.contains("LOCK"))
        assertTrue(output.contains("SESSION LOG"))
    }

    @Test
    fun `text is readable with no tag scanned`() {
        val output = text(report = null)

        assertTrue(output.contains("NFC Explorer"), output)
        assertTrue(output.isNotBlank())
    }

    @Test
    fun `text export does not leak kotlin data class toString`() {
        // The TXT format is the human-readable one. "Known(code=4, name=...)" and
        // "NotSupportedByChip(introducedIn=...)" are debug representations that happen to compile,
        // not output anyone wants to read in a file.
        val output = text()

        assertFalse(output.contains("Known("), output)
        assertFalse(output.contains("Unknown("), output)
        assertFalse(output.contains("NotSupportedByChip("), output)
        assertFalse(output.contains("Present("), output)
    }

    @Test
    fun `text export has no trailing whitespace on any line`() {
        // Column padding left ragged spaces at end of line, which shows up as soon as the file is
        // opened in an editor that highlights it, and breaks naive diffing between two exports.
        val offenders = text().lines().filter { it != it.trimEnd() }

        assertTrue(offenders.isEmpty(), "lines with trailing whitespace: $offenders")
    }

    @Test
    fun `text export names the manufacturer readably`() {
        assertTrue(text().contains("NXP Semiconductors (0x04)"), text())
    }

    // --- Format metadata ---

    @Test
    fun `formats declare their extension and mime type`() {
        assertEquals("json", JsonSessionExporter.format.extension)
        assertEquals("application/json", JsonSessionExporter.format.mimeType)
        assertEquals("txt", TextSessionExporter.format.extension)
        assertEquals("text/plain", TextSessionExporter.format.mimeType)
    }
}
