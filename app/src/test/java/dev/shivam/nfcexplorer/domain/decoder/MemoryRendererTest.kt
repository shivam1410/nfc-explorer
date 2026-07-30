package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.PageSnapshot
import dev.shivam.nfcexplorer.domain.model.ReadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryRendererTest {

    private val page = ByteBlock.ofInts(0x04, 0xA2, 0x55, 0x71)

    // --- Hex ---

    @Test
    fun `hex is uppercase and space separated`() {
        assertEquals("04 A2 55 71", MemoryRenderer.hex(page))
    }

    @Test
    fun `hex pads single digit bytes`() {
        assertEquals("00 01 0F 10", MemoryRenderer.hex(ByteBlock.ofInts(0x00, 0x01, 0x0F, 0x10)))
    }

    @Test
    fun `hex renders high bytes unsigned`() {
        // Kotlin's Byte is signed; 0xFF must not appear as -1 or FFFFFFFF.
        assertEquals("80 FF", MemoryRenderer.hex(ByteBlock.ofInts(0x80, 0xFF)))
    }

    // --- Binary ---

    @Test
    fun `binary is eight digits per byte, most significant first`() {
        assertEquals("00000100 10100010 01010101 01110001", MemoryRenderer.binary(page))
    }

    @Test
    fun `binary keeps leading zeros`() {
        assertEquals("00000001", MemoryRenderer.binary(ByteBlock.ofInts(0x01)))
        assertEquals("11111111", MemoryRenderer.binary(ByteBlock.ofInts(0xFF)))
    }

    // --- Decimal ---

    @Test
    fun `decimal renders unsigned values`() {
        assertEquals("4 162 85 113", MemoryRenderer.decimal(page))
        assertEquals("255", MemoryRenderer.decimal(ByteBlock.ofInts(0xFF)))
    }

    // --- ASCII ---

    @Test
    fun `ascii renders printable characters`() {
        val text = ByteBlock.copyOf("Test".toByteArray(Charsets.US_ASCII))

        assertEquals("Test", MemoryRenderer.ascii(text))
    }

    @Test
    fun `ascii substitutes non printable bytes`() {
        assertEquals("····", MemoryRenderer.ascii(ByteBlock.ofInts(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `ascii treats space and tilde as the printable boundaries`() {
        // 0x20..0x7E is printable; 0x1F and 0x7F are not.
        assertEquals(" ~", MemoryRenderer.ascii(ByteBlock.ofInts(0x20, 0x7E)))
        assertEquals("··", MemoryRenderer.ascii(ByteBlock.ofInts(0x1F, 0x7F)))
    }

    @Test
    fun `ascii substitutes high bytes rather than mangling them into latin1`() {
        assertEquals("··", MemoryRenderer.ascii(ByteBlock.ofInts(0x80, 0xFF)))
    }

    @Test
    fun `ascii placeholder is configurable`() {
        assertEquals("..", MemoryRenderer.ascii(ByteBlock.ofInts(0x00, 0x01), placeholder = '.'))
    }

    @Test
    fun `ascii keeps a mix of printable and non printable aligned one char per byte`() {
        val mixed = ByteBlock.ofInts(0x41, 0x00, 0x42, 0xFF)

        // Alignment matters: the ASCII column sits beside the hex column in the UI.
        assertEquals("A·B·", MemoryRenderer.ascii(mixed))
        assertEquals(4, MemoryRenderer.ascii(mixed).length)
    }

    // --- Empty input ---

    @Test
    fun `an empty block renders as an empty string in every format`() {
        assertEquals("", MemoryRenderer.hex(ByteBlock.EMPTY))
        assertEquals("", MemoryRenderer.binary(ByteBlock.EMPTY))
        assertEquals("", MemoryRenderer.decimal(ByteBlock.EMPTY))
        assertEquals("", MemoryRenderer.ascii(ByteBlock.EMPTY))
    }

    // --- Page overloads: an unread page must never look like zeros ---

    @Test
    fun `a readable page renders in every format`() {
        val snapshot = PageSnapshot.ok(4, page)

        assertEquals("04 A2 55 71", MemoryRenderer.hex(snapshot))
        assertEquals("00000100 10100010 01010101 01110001", MemoryRenderer.binary(snapshot))
        assertEquals("4 162 85 113", MemoryRenderer.decimal(snapshot))
        // 0x04 and 0xA2 are non-printable; 0x55 is 'U' and 0x71 is 'q'.
        assertEquals("··Uq", MemoryRenderer.ascii(snapshot))
    }

    @Test
    fun `an unread page renders as null in every format, never as zeros`() {
        ReadStatus.entries.filter { it != ReadStatus.OK }.forEach { status ->
            val snapshot = PageSnapshot.failed(9, status)

            assertNull(MemoryRenderer.hex(snapshot), "hex for $status")
            assertNull(MemoryRenderer.binary(snapshot), "binary for $status")
            assertNull(MemoryRenderer.decimal(snapshot), "decimal for $status")
            assertNull(MemoryRenderer.ascii(snapshot), "ascii for $status")
        }
    }

    @Test
    fun `the renderer emits no status text of its own`() {
        // User-facing wording belongs in strings.xml. The domain layer has no resource
        // access, so returning null forces the UI to supply the label.
        val snapshot = PageSnapshot.failed(5, ReadStatus.NAK_REFUSED, detail = "IOException")

        assertNull(MemoryRenderer.hex(snapshot))
    }
}
