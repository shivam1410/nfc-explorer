package dev.shivam.nfcexplorer.util

private const val HEX_DIGITS = "0123456789ABCDEF"

/** Two uppercase hex digits, e.g. `0A`. */
fun Byte.toHex(): String {
    val value = toInt() and 0xFF
    return "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0x0F]}"
}

/** Uppercase hex, joined with [separator]. */
fun ByteArray.toHex(separator: String = " "): String =
    joinToString(separator) { it.toHex() }

/** Eight binary digits, most significant first, e.g. `00001010`. */
fun Byte.toBinary(): String {
    val value = toInt() and 0xFF
    return buildString(Byte.SIZE_BITS) {
        for (bit in Byte.SIZE_BITS - 1 downTo 0) {
            append(if ((value shr bit) and 1 == 1) '1' else '0')
        }
    }
}

/**
 * Parses exactly one byte from one or two hex digits.
 *
 * Returns null for anything else — empty input, over-long input, or non-hex characters.
 * Callers validating user entry rely on null rather than an exception, since a
 * half-typed field is an expected state, not a failure.
 */
fun String.parseHexByte(): Byte? {
    val text = trim()
    if (text.isEmpty() || text.length > 2) return null
    var value = 0
    for (character in text) {
        val digit = Character.digit(character, 16)
        if (digit < 0) return null
        value = (value shl 4) or digit
    }
    return value.toByte()
}

/**
 * Parses a whole hex string, tolerating spaces and colons between bytes.
 *
 * Returns null if any byte is malformed or the digit count is odd, so a partial parse can
 * never be mistaken for a complete one.
 */
fun String.parseHexBytes(): ByteArray? {
    val compact = filterNot { it.isWhitespace() || it == ':' }
    if (compact.isEmpty() || compact.length % 2 != 0) return null
    val output = ByteArray(compact.length / 2)
    for (index in output.indices) {
        val byte = compact.substring(index * 2, index * 2 + 2).parseHexByte() ?: return null
        output[index] = byte
    }
    return output
}
