package dev.shivam.nfcexplorer.data.nfc

import android.nfc.TagLostException
import android.nfc.tech.MifareUltralight
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagIoException
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import java.io.IOException

/**
 * Adapter over [MifareUltralight].
 *
 * Deliberately thin: it holds no decode logic, because everything worth testing lives above
 * the transport seam where a fake can drive it. All it does is delegate and translate
 * framework exceptions into domain ones. See `docs/adr/0001-fakeable-tag-transport.md` for why
 * this class is not unit-tested — mocking a final framework class would only assert that the
 * mock was called.
 */
class AndroidUltralightTransport(
    private val delegate: MifareUltralight,
) : UltralightTransport {

    override val maxTransceiveLength: Int
        get() = delegate.maxTransceiveLength

    override fun connect() = translate("connect") { delegate.connect() }

    override fun transceive(command: ByteArray): ByteArray =
        translate("transceive") { delegate.transceive(command) }

    override fun readPages(pageOffset: Int): ByteArray =
        translate("readPages($pageOffset)") { delegate.readPages(pageOffset) }

    override fun writePage(pageOffset: Int, data: ByteArray) =
        translate("writePage($pageOffset)") { delegate.writePage(pageOffset, data) }

    /**
     * Closing is best-effort. The tag may already be gone, and a failure here would mask the
     * real outcome of the operation the caller actually cared about.
     */
    override fun close() {
        try {
            delegate.close()
        } catch (ignored: IOException) {
            // Nothing useful to do: the connection is being torn down either way. Swallowed
            // deliberately rather than propagated over the caller's real result.
        }
    }

    /**
     * [TagLostException] is a subclass of [IOException], so it must be caught first or it would
     * be flattened into a generic I/O error and the pipeline could no longer tell "the tag left
     * the field" from "the tag refused".
     */
    private inline fun <T> translate(operation: String, block: () -> T): T =
        try {
            block()
        } catch (lost: TagLostException) {
            throw TagFieldLostException(lost)
        } catch (failure: IOException) {
            throw TagIoException(
                message = failure.message ?: "$operation failed",
                cause = failure,
            )
        }
}
