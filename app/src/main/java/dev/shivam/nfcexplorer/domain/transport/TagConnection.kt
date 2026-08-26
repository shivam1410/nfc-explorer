package dev.shivam.nfcexplorer.domain.transport

import java.io.Closeable

/**
 * A tag that can be asked to answer.
 *
 * Narrower than [TagTransport] on purpose. Proving a tag is live needs `connect` and nothing else,
 * and every NFC technology can do that -- while only the Ultralight family can be decoded page by
 * page. Tying the presence check to the full transport meant a tag had to be readable before it
 * could be proven live, so an assigned NfcB, NfcF or NfcV tag was refused on every tap: the trigger
 * launched, found nothing it could open, and treated a tag in the field as a tag that was not there.
 *
 * Implementations are not thread-safe and are valid only while the tag stays in the field.
 */
interface TagConnection : Closeable {

    /** Opens the connection. Throws [java.io.IOException] when the tag refuses or has gone. */
    fun connect()
}
