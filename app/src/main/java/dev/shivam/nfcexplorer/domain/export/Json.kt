package dev.shivam.nfcexplorer.domain.export

/**
 * Minimal JSON encoder over plain Kotlin values.
 *
 * Hand-written rather than pulling in a serialisation library and its Gradle plugin. The export
 * schema is small, fixed, and assembled here in one place, so a dependency plus DTO layer plus
 * annotations on domain models would be more moving parts than the problem needs — and the domain
 * layer stays free of framework annotations.
 *
 * The trade is that escaping correctness is on us, which is why [JsonTest] hammers it: an unescaped
 * quote or a raw control byte produces output that reads fine in a log and fails to parse in
 * whatever tool the user opens the file with. Tag memory is arbitrary binary, so control characters
 * in an ASCII column are routine rather than exotic.
 *
 * Unsupported types and non-string keys throw instead of falling back to `toString`, which would
 * emit unquoted text and produce invalid JSON that only shows up downstream.
 */
object Json {

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is String -> "\"${escape(value)}\""
        is Map<*, *> -> encodeObject(value)
        is Iterable<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") {
            encode(it)
        }
        else -> throw IllegalArgumentException(
            "cannot encode ${value::class.simpleName} as JSON; convert it explicitly first",
        )
    }

    private fun encodeObject(map: Map<*, *>): String =
        map.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            require(key is String) {
                "JSON object keys must be strings, got ${key?.let { it::class.simpleName }}"
            }
            "\"${escape(key)}\":${encode(value)}"
        }

    /**
     * Escapes per RFC 8259.
     *
     * Printable non-ASCII is left alone because the file is written as UTF-8; escaping it would only
     * make the output harder to read. Anything below 0x20 without a short form gets `\uXXXX`.
     */
    fun escape(text: String): String = buildString(text.length) {
        for (character in text) {
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character == '\b' -> append("\\b")
                character == '\u000C' -> append("\\f")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }
}
