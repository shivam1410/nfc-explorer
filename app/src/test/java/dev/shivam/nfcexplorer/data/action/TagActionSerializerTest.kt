package dev.shivam.nfcexplorer.data.action

import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip tests, which is the whole reason kotlinx.serialization was added here rather than
 * reusing the hand-written encoder from the export path: that one only ever writes, and hand-rolling
 * a *parser* is where silent corruption lives.
 *
 * A store that cannot be decoded must return an empty list, never throw. Throwing here would take out
 * the dispatch path on a tap, and a tap has no user watching to interpret a crash.
 */
class TagActionSerializerTest {

    private val uidA = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)
    private val uidB = ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81)

    private fun roundTrip(assignment: TagAssignment): TagAssignment {
        val decoded = TagActionSerializer.decode(TagActionSerializer.encode(listOf(assignment)))
        return decoded.single()
    }

    // --- Round trip, every action type ---

    @Test
    fun `launch app survives a round trip`() {
        val original = TagAssignment(uidA, "Desk", TagAction.LaunchApp("com.example.notes"))

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `open uri survives a round trip`() {
        val original = TagAssignment(
            uidA,
            "Music",
            TagAction.OpenUri("https://music.youtube.com/playlist?list=PLabc123"),
        )

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `send intent survives a round trip including extras`() {
        val original = TagAssignment(
            uidB,
            "Sleep",
            TagAction.SendIntent(
                action = "com.urbandroid.sleep.alarmclock.START_SLEEP_TRACK",
                uri = "myapp://start",
                extras = mapOf("source" to "nfc", "note" to """quotes " and \ backslash"""),
            ),
        )

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `media command survives a round trip`() {
        MediaKey.entries.forEach { key ->
            val original = TagAssignment(uidA, "Media $key", TagAction.MediaCommand(key))
            assertEquals(original, roundTrip(original), "media key $key")
        }
    }

    @Test
    fun `the UID itself survives byte for byte`() {
        // A UID mangled in storage would silently stop matching the tag, so the tap would do nothing
        // and look like a lost assignment rather than a corrupt one.
        val original = TagAssignment(uidB, "Card B", TagAction.LaunchApp("a.b"))

        assertEquals(uidB, roundTrip(original).uid)
        assertEquals("040e66a2f07b81", roundTrip(original).uidKey)
    }

    @Test
    fun `multiple assignments round trip together and keep their order`() {
        val list = listOf(
            TagAssignment(uidA, "One", TagAction.LaunchApp("a.b")),
            TagAssignment(uidB, "Two", TagAction.MediaCommand(MediaKey.NEXT)),
        )

        assertEquals(list, TagActionSerializer.decode(TagActionSerializer.encode(list)))
    }

    @Test
    fun `an empty list round trips to an empty list`() {
        assertEquals(emptyList(), TagActionSerializer.decode(TagActionSerializer.encode(emptyList())))
    }

    // --- Malformed input degrades, never throws ---

    @Test
    fun `null and blank documents decode to empty`() {
        assertEquals(emptyList(), TagActionSerializer.decode(null))
        assertEquals(emptyList(), TagActionSerializer.decode(""))
        assertEquals(emptyList(), TagActionSerializer.decode("   "))
    }

    @Test
    fun `malformed json decodes to empty rather than throwing`() {
        assertEquals(emptyList(), TagActionSerializer.decode("{not json"))
        assertEquals(emptyList(), TagActionSerializer.decode("[1,2,3]"))
        assertEquals(emptyList(), TagActionSerializer.decode("""{"version":"wrong shape"}"""))
    }

    @Test
    fun `an assignment with an unknown action type is skipped, not fatal`() {
        // Forward compatibility: a document written by a newer build must not render the whole store
        // unreadable, losing assignments the current build understands perfectly well.
        val document = TagActionSerializer.encode(
            listOf(TagAssignment(uidA, "Known", TagAction.LaunchApp("a.b"))),
        ).replace("]}", """,{"uidHex":"040e66a2f07b81","label":"Future","action":{"type":"quantum"}}]}""")

        val decoded = TagActionSerializer.decode(document)

        assertEquals(1, decoded.size, "the known assignment should survive: $document")
        assertEquals("Known", decoded.single().label)
    }

    @Test
    fun `an assignment that violates a domain invariant is skipped, not fatal`() {
        // A blank label is rejected by TagAssignment's own init. Hand-edited or corrupted storage
        // must not turn that into a crash on the dispatch path.
        val document = TagActionSerializer.encode(
            listOf(TagAssignment(uidA, "Fine", TagAction.LaunchApp("a.b"))),
        ).replace(""""label":"Fine"""", """"label":""""")

        assertTrue(TagActionSerializer.decode(document).isEmpty())
    }

    @Test
    fun `the document records a schema version`() {
        assertTrue(
            TagActionSerializer.encode(emptyList()).contains("version"),
            TagActionSerializer.encode(emptyList()),
        )
    }
}
