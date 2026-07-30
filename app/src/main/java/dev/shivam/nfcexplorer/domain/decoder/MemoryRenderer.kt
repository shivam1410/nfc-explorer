package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.PageSnapshot
import dev.shivam.nfcexplorer.util.toBinary
import dev.shivam.nfcexplorer.util.toHex

/**
 * Renders page bytes as hex, binary, decimal or ASCII.
 *
 * The [PageSnapshot] overloads return null for a page that could not be read. That is the
 * point of having them: an unread page must be impossible to render as `00 00 00 00`, which
 * would be indistinguishable from a page of real zeros. The caller supplies the wording for
 * the failure, because user-facing text lives in `strings.xml` and the domain layer has no
 * access to resources.
 */
object MemoryRenderer {

    const val NON_PRINTABLE_PLACEHOLDER: Char = '·'

    private const val FIRST_PRINTABLE_ASCII = 0x20
    private const val LAST_PRINTABLE_ASCII = 0x7E

    /** Uppercase, space-separated: `04 A2 55 71`. */
    fun hex(bytes: ByteBlock): String = bytes.toByteArray().toHex()

    /** Eight digits per byte, most significant first: `00000100 10100010`. */
    fun binary(bytes: ByteBlock): String =
        bytes.toByteArray().joinToString(" ") { it.toBinary() }

    /** Unsigned decimal per byte: `4 162 85 113`. */
    fun decimal(bytes: ByteBlock): String =
        (0 until bytes.size).joinToString(" ") { bytes.unsignedAt(it).toString() }

    /**
     * One character per byte, so the column stays aligned with the hex column beside it.
     *
     * Only `0x20`..`0x7E` is treated as printable. High bytes are substituted rather than
     * decoded as Latin-1, since tag memory is arbitrary binary and inventing accented
     * characters from it would suggest structure that is not there.
     */
    fun ascii(bytes: ByteBlock, placeholder: Char = NON_PRINTABLE_PLACEHOLDER): String =
        buildString(bytes.size) {
            for (index in 0 until bytes.size) {
                val value = bytes.unsignedAt(index)
                append(
                    if (value in FIRST_PRINTABLE_ASCII..LAST_PRINTABLE_ASCII) {
                        value.toChar()
                    } else {
                        placeholder
                    },
                )
            }
        }

    fun hex(page: PageSnapshot): String? = page.bytes?.let(::hex)

    fun binary(page: PageSnapshot): String? = page.bytes?.let(::binary)

    fun decimal(page: PageSnapshot): String? = page.bytes?.let(::decimal)

    fun ascii(page: PageSnapshot): String? = page.bytes?.let { ascii(it) }
}
