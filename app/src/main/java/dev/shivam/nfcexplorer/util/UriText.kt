package dev.shivam.nfcexplorer.util

/**
 * Characters that may stand for themselves in a URI: the unreserved set plus every reserved
 * delimiter.
 *
 * The delimiters are deliberately left alone. A `&` in `?text=you & me` might be a separator the
 * user meant or data they did not, and nothing here can tell which — guessing would corrupt the
 * links that are already right in order to rescue the ones that are not. Only characters that can
 * never appear raw are touched. `%` is absent on purpose: it is handled separately, since whether it
 * is safe depends on what follows it.
 */
private const val SAFE_PUNCTUATION = "-._~:/?#[]@!$&'()*+,;="

private const val PERCENT = '%'.code

/**
 * Percent-encodes the characters that cannot appear raw in a URI, leaving everything else verbatim.
 *
 * The editor takes a link as free text, so a message typed as `?text=see you at 5` used to be stored
 * with its spaces intact and truncate on the way out. This fixes that at the point the draft becomes
 * a stored action, not on every keystroke — rewriting the field as the user types would move the
 * cursor out from under them.
 *
 * **Idempotent**, which is the whole difficulty. Someone who already typed `%20` must not end up
 * with `%2520`, so a well-formed escape is copied through untouched and only a `%` that does not
 * begin one is encoded. Encoding an encoded string returns it unchanged, so callers need not track
 * whether this has already run.
 *
 * Works on the UTF-8 bytes rather than the chars, so an astral-plane character — most emoji — comes
 * out as its whole byte sequence instead of two broken halves of a surrogate pair.
 */
fun String.percentEncodeUnsafe(): String {
    val bytes = toByteArray(Charsets.UTF_8)
    return buildString(bytes.size) {
        var index = 0
        while (index < bytes.size) {
            val byte = bytes[index].toInt() and 0xFF
            if (byte == PERCENT && bytes.hasEscapeAt(index)) {
                // Already encoded. Copied rather than re-encoded, which is what makes this safe to
                // apply more than once.
                append(byte.toChar())
                append((bytes[index + 1].toInt() and 0xFF).toChar())
                append((bytes[index + 2].toInt() and 0xFF).toChar())
                index += 3
                continue
            }
            if (byte.isSafeInUri()) append(byte.toChar()) else append(byte.toEscape())
            index++
        }
    }
}

/** Whether a well-formed `%XX` escape starts at [index]. */
private fun ByteArray.hasEscapeAt(index: Int): Boolean =
    index + 2 < size &&
        (this[index + 1].toInt() and 0xFF).toChar().isHexDigit() &&
        (this[index + 2].toInt() and 0xFF).toChar().isHexDigit()

/** ASCII only, so a multi-byte character can never be mistaken for a hex digit. */
private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * ASCII letters, digits and delimiters stand for themselves; everything else is escaped.
 *
 * Every byte at or above 0x80 is part of a multi-byte character and always escaped, which is what
 * makes the UTF-8 pass correct without knowing where characters begin.
 */
private fun Int.isSafeInUri(): Boolean {
    if (this >= 0x80) return false
    val character = toChar()
    return character in 'a'..'z' ||
        character in 'A'..'Z' ||
        character in '0'..'9' ||
        character in SAFE_PUNCTUATION
}

private fun Int.toEscape(): String = "%${toByte().toHex()}"
