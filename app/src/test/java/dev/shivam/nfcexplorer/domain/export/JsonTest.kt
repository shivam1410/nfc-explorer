package dev.shivam.nfcexplorer.domain.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The encoder is hand-written, so escaping and structure get tested hard.
 *
 * This is where a hand-rolled serialiser goes wrong: an unescaped quote or newline produces output
 * that looks fine in a log and fails to parse in whatever tool the user opens it with.
 */
class JsonTest {

    // --- Primitives ---

    @Test
    fun `null encodes as a JSON null, not the string "null"`() {
        assertEquals("null", Json.encode(null))
    }

    @Test
    fun `booleans and numbers encode bare`() {
        assertEquals("true", Json.encode(true))
        assertEquals("false", Json.encode(false))
        assertEquals("42", Json.encode(42))
        assertEquals("-7", Json.encode(-7))
        assertEquals("1234567890123", Json.encode(1234567890123L))
    }

    @Test
    fun `strings are quoted`() {
        assertEquals("\"hello\"", Json.encode("hello"))
        assertEquals("\"\"", Json.encode(""))
    }

    // --- Escaping ---

    @Test
    fun `quotes and backslashes are escaped`() {
        assertEquals("""a\"b""", Json.escape("""a"b"""))
        assertEquals("""a\\b""", Json.escape("""a\b"""))
    }

    @Test
    fun `control characters are escaped rather than emitted raw`() {
        assertEquals("""a\nb""", Json.escape("a\nb"))
        assertEquals("""a\rb""", Json.escape("a\rb"))
        assertEquals("""a\tb""", Json.escape("a\tb"))
        assertEquals("""a\bb""", Json.escape("a\bb"))
    }

    @Test
    fun `other control characters use a unicode escape`() {
        // 0x00 and 0x1F have no short JSON form and must never appear literally in output.
        assertEquals("\\u0000", Json.escape("\u0000"))
        assertEquals("\\u001f", Json.escape("\u001F"))
        assertEquals("a\\u0001b", Json.escape("a\u0001b"))
    }

    @Test
    fun `printable non ascii passes through`() {
        // The file is written as UTF-8, so there is no need to escape these.
        assertEquals("café", Json.escape("café"))
    }

    @Test
    fun `the non printable placeholder used by the ASCII renderer survives encoding`() {
        // The memory dump's ASCII column is full of these; mangling them would corrupt exports.
        assertEquals("\"··Uq\"", Json.encode("··Uq"))
    }

    // --- Structures ---

    @Test
    fun `maps encode as objects in insertion order`() {
        val encoded = Json.encode(linkedMapOf("b" to 1, "a" to 2))

        // Insertion order, not sorted: the export schema reads better in a deliberate order.
        assertEquals("""{"b":1,"a":2}""", encoded)
    }

    @Test
    fun `lists encode as arrays`() {
        assertEquals("[1,2,3]", Json.encode(listOf(1, 2, 3)))
        assertEquals("[]", Json.encode(emptyList<Int>()))
    }

    @Test
    fun `nested structures encode recursively`() {
        val encoded = Json.encode(
            linkedMapOf(
                "pages" to listOf(
                    linkedMapOf("index" to 0, "hex" to "04 0E"),
                    linkedMapOf("index" to 1, "hex" to null),
                ),
            ),
        )

        assertEquals(
            """{"pages":[{"index":0,"hex":"04 0E"},{"index":1,"hex":null}]}""",
            encoded,
        )
    }

    @Test
    fun `map keys are escaped too`() {
        assertEquals("""{"a\"b":1}""", Json.encode(mapOf("a\"b" to 1)))
    }

    @Test
    fun `a non string key is rejected rather than silently coerced`() {
        // JSON object keys must be strings; coercing would produce output that no longer matches
        // the schema the reader expects.
        assertFailsWith<IllegalArgumentException> { Json.encode(mapOf(1 to "one")) }
    }

    @Test
    fun `an unsupported value type is rejected rather than encoded as its toString`() {
        // toString would emit unquoted garbage and produce invalid JSON.
        assertFailsWith<IllegalArgumentException> { Json.encode(Any()) }
    }
}
