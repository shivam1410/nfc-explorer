package dev.shivam.nfcexplorer.domain.writer

import dev.shivam.nfcexplorer.domain.transport.UltralightTransport.Companion.BYTES_PER_PAGE
import dev.shivam.nfcexplorer.util.parseHexBytes

/**
 * Turns user input into whole pages.
 *
 * Every function returns null on input that will not fit rather than truncating it. Silently
 * dropping the tail would write something the user did not ask for, onto memory that in some cases
 * cannot be rewritten — so an over-long payload is an error to report, not a value to trim.
 *
 * Short input *is* padded with zeros, because a page is the smallest writable unit: there is no way
 * to write two bytes and leave the other two alone.
 */
object PageEncoder {

    fun capacityBytes(pageCount: Int): Int = pageCount * BYTES_PER_PAGE

    /**
     * Encodes [text] as UTF-8 across [pageCount] pages.
     *
     * Capacity is measured in bytes, not characters — a multi-byte character consumes more than one
     * byte of a 48-byte tag, and pretending otherwise would reject or accept the wrong inputs.
     */
    fun fromText(text: String, pageCount: Int): List<ByteArray>? =
        pack(text.toByteArray(Charsets.UTF_8), pageCount)

    /**
     * Parses [hex] across [pageCount] pages, tolerating spaces and colons between bytes.
     *
     * An odd digit count is rejected rather than guessed: `ABC` could mean `0A BC` or `AB C0`, and
     * choosing one would be inventing user intent.
     */
    fun fromHex(hex: String, pageCount: Int): List<ByteArray>? {
        if (hex.isBlank()) return zeros(pageCount)
        val bytes = hex.parseHexBytes() ?: return null
        return pack(bytes, pageCount)
    }

    /** [pageCount] blank pages, each independently allocated. */
    fun zeros(pageCount: Int): List<ByteArray> =
        List(pageCount) { ByteArray(BYTES_PER_PAGE) }

    private fun pack(bytes: ByteArray, pageCount: Int): List<ByteArray>? {
        if (bytes.size > capacityBytes(pageCount)) return null
        // Fresh array per page, so a caller mutating one page cannot reach another.
        return List(pageCount) { pageIndex ->
            val page = ByteArray(BYTES_PER_PAGE)
            val offset = pageIndex * BYTES_PER_PAGE
            for (byteIndex in 0 until BYTES_PER_PAGE) {
                val source = offset + byteIndex
                if (source < bytes.size) page[byteIndex] = bytes[source]
            }
            page
        }
    }
}
