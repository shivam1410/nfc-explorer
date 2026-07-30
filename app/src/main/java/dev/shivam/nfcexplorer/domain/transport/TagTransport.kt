package dev.shivam.nfcexplorer.domain.transport

import java.io.Closeable

/**
 * The seam between decode logic and NFC hardware.
 *
 * Everything above this interface — decoders, the read pipeline, lock analysis, the write
 * guard — is pure logic that can be exercised on the JVM against a fake. That matters
 * because NFC hardware does not exist in the Android emulator, so without this boundary
 * none of it would be testable without a phone in hand. See
 * `docs/adr/0001-fakeable-tag-transport.md`.
 *
 * Implementations are not thread-safe and are valid only while the tag stays in the field.
 * Every method throws [java.io.IOException] when the tag refuses or disappears.
 */
interface TagTransport : Closeable {

    /** Largest frame this technology accepts, in bytes. */
    val maxTransceiveLength: Int

    /** Opens the connection. Must be called before any exchange. */
    fun connect()

    /** Sends a raw command and returns the raw response. */
    fun transceive(command: ByteArray): ByteArray
}
