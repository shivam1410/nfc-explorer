package dev.shivam.nfcexplorer.domain.transport

/**
 * Page-oriented access to a MIFARE Ultralight family tag.
 */
interface UltralightTransport : TagTransport {

    /**
     * Reads four pages starting at [pageOffset].
     *
     * Returns exactly [BYTES_PER_READ] bytes and **wraps around** past the last page, which
     * is the chip's documented `READ` behaviour rather than a quirk of this interface: on a
     * 16-page tag, `readPages(14)` yields pages 14, 15, 0, 1. Callers must clamp to the
     * chip's page count and discard the wrapped tail, or they will report early pages under
     * late indices. See `docs/mf0icu1-reference.md`.
     *
     * @throws java.io.IOException if the tag NAKs or leaves the field.
     */
    fun readPages(pageOffset: Int): ByteArray

    /**
     * Writes one page.
     *
     * [data] must be exactly [BYTES_PER_PAGE] long. On lock and OTP pages the chip ORs the
     * incoming bits into the stored value, so bits can be set but never cleared.
     *
     * @throws java.io.IOException if the page is locked, the tag NAKs, or it leaves the field.
     */
    fun writePage(pageOffset: Int, data: ByteArray)

    companion object {
        const val BYTES_PER_PAGE = 4

        /** `READ` always returns four pages. */
        const val PAGES_PER_READ = 4

        const val BYTES_PER_READ = BYTES_PER_PAGE * PAGES_PER_READ
    }
}
