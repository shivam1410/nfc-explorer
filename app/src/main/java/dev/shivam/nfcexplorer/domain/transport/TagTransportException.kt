package dev.shivam.nfcexplorer.domain.transport

import java.io.IOException

/**
 * Failures a transport can report, in domain terms.
 *
 * The domain layer cannot reference `android.nfc.TagLostException`, but the read pipeline
 * still has to tell "the tag left the field" apart from "the tag refused this operation" —
 * they produce different per-page statuses and different advice to the user. Android
 * adapters translate framework exceptions into these; the fake transport raises them
 * directly, so both paths are exercised by the same pipeline code.
 *
 * Extends [IOException] so callers that only care that I/O failed still work.
 */
sealed class TagTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * The tag moved out of the field. Anything not yet read was never attempted.
 */
class TagFieldLostException(
    cause: Throwable? = null,
) : TagTransportException("tag left the field", cause)

/**
 * The tag answered NAK: it refused the operation. Typical causes are a locked page
 * rejecting a write, an out-of-range address, or a command the chip does not implement.
 */
class TagNakException(
    message: String,
    cause: Throwable? = null,
) : TagTransportException(message, cause)

/**
 * The transport was used out of order — an exchange before `connect()`, or after `close()`.
 * A bug in the caller rather than a tag condition.
 */
class TagNotConnectedException :
    TagTransportException("transport used before connect() or after close()")
