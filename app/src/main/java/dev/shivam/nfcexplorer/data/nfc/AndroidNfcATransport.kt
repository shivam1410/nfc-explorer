package dev.shivam.nfcexplorer.data.nfc

import android.nfc.TagLostException
import android.nfc.tech.NfcA
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagIoException
import dev.shivam.nfcexplorer.domain.transport.TagTransport
import java.io.IOException

/**
 * Adapter over [NfcA].
 *
 * Exists so [dev.shivam.nfcexplorer.domain.action.TagPresence] can prove a tag is live even when it is
 * not an Ultralight. The trigger's tech filter admits plain `NfcA`, and without this an assignment on
 * such a tag would fail the presence check and silently stop working.
 *
 * Only the [TagTransport] half is implemented — presence needs `connect` and nothing more. Page access
 * stays on [AndroidUltralightTransport], which is the only chip family this app decodes.
 *
 * As thin as its sibling, and unit-tested the same way: not at all. It only delegates and translates
 * framework exceptions, so a test could assert nothing a mock did not already decide. See
 * `docs/adr/0001-fakeable-tag-transport.md`.
 */
class AndroidNfcATransport(
    private val delegate: NfcA,
) : TagTransport {

    override val maxTransceiveLength: Int
        get() = delegate.maxTransceiveLength

    override fun connect() = translate("connect") { delegate.connect() }

    override fun transceive(command: ByteArray): ByteArray =
        translate("transceive") { delegate.transceive(command) }

    /** Best-effort, for the same reason as [AndroidUltralightTransport.close]. */
    override fun close() {
        try {
            delegate.close()
        } catch (ignored: IOException) {
            // The connection is going away regardless; a failure here would mask the real outcome.
        }
    }

    /** [TagLostException] first: it extends [IOException] and must not be flattened into one. */
    private inline fun <T> translate(operation: String, block: () -> T): T =
        try {
            block()
        } catch (lost: TagLostException) {
            throw TagFieldLostException(lost)
        } catch (failure: IOException) {
            throw TagIoException(message = failure.message ?: "$operation failed", cause = failure)
        }
}
