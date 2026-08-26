package dev.shivam.nfcexplorer.data.nfc

import android.nfc.TagLostException
import android.nfc.tech.TagTechnology
import dev.shivam.nfcexplorer.domain.transport.TagConnection
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagIoException
import java.io.IOException

/**
 * Adapter over any [TagTechnology], for proving a tag is live.
 *
 * `TagTechnology` is the one thing NfcA, NfcB, NfcF, NfcV and the MIFARE classes all are, and it
 * carries exactly what the presence check needs. Reading a tag still requires knowing what it is;
 * asking whether it answers does not, and pretending otherwise is what left whole tag families
 * unable to run an action they had been assigned.
 *
 * As thin as the transports beside it, and unit-tested the same way: not at all. It delegates and
 * translates framework exceptions, so a test could assert nothing a mock did not already decide.
 * See `docs/adr/0001-fakeable-tag-transport.md`.
 */
class AndroidTagConnection(
    private val delegate: TagTechnology,
) : TagConnection {

    override fun connect() =
        try {
            delegate.connect()
        } catch (lost: TagLostException) {
            throw TagFieldLostException(lost)
        } catch (failure: IOException) {
            throw TagIoException(message = failure.message ?: "connect failed", cause = failure)
        }

    /** Best-effort, for the same reason as [AndroidUltralightTransport.close]. */
    override fun close() {
        try {
            delegate.close()
        } catch (ignored: IOException) {
            // The connection is going away regardless; a failure here would mask the real outcome.
        }
    }
}
