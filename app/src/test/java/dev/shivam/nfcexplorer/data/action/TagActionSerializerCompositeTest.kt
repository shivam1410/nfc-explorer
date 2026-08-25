package dev.shivam.nfcexplorer.data.action

import dev.shivam.nfcexplorer.domain.action.SleepCycle
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round trips for the composite actions.
 *
 * Kept apart from [TagActionSerializerTest] because these exercise a different property: not "one flat
 * DTO survives", but "a nested action survives, and a corrupt nesting degrades to one lost assignment
 * rather than a thrown exception on the dispatch path".
 */
class TagActionSerializerCompositeTest {

    private val uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)

    private fun roundTrip(assignment: TagAssignment): TagAssignment =
        TagActionSerializer.decode(TagActionSerializer.encode(listOf(assignment))).single()

    @Test
    fun `a drag gesture survives a round trip with its geometry intact`() {
        val original = TagAssignment(
            uid,
            "Stop",
            TagAction.DragGesture(
                startXRatio = 0.5f,
                startYRatio = 0.805f,
                endXRatio = 0.5f,
                endYRatio = 0.454f,
                holdMillis = 150,
                travelMillis = 1_000,
                steps = 10,
                requireForegroundPackage = "com.northcube.sleepcycle",
            ),
        )

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `a sequence survives a round trip in order`() {
        val original = TagAssignment(
            uid,
            "Two steps",
            TagAction.Steps(
                steps = listOf(
                    TagAction.SendIntent("com.example.FIRST"),
                    TagAction.SendIntent("com.example.SECOND"),
                ),
                gapMillis = 750,
            ),
        )

        assertEquals(original, roundTrip(original))
    }

    /** The whole Sleep Cycle preset, which is the shape that actually gets stored. */
    @Test
    fun `the sleep cycle toggle survives a round trip`() {
        val original = TagAssignment(uid, "Bed", SleepCycle.toggle())

        assertEquals(original, roundTrip(original))
    }

    @Test
    fun `a toggle missing its channel is dropped rather than throwing`() {
        val document = """
            {"version":1,"assignments":[
              {"uidHex":"041C4E52CE7C80","label":"Bed",
               "action":{"type":"whileNotificationShowing","packageName":"com.northcube.sleepcycle",
                         "showing":{"type":"sendIntent","intentAction":"A"},
                         "absent":{"type":"sendIntent","intentAction":"B"}}}
            ]}
        """.trimIndent()

        assertTrue(TagActionSerializer.decode(document).isEmpty())
    }

    /**
     * A hand-edited document could nest a sequence inside a sequence, which the domain forbids.
     * Decoding must lose that one assignment quietly, not take out the tap that reads the store.
     */
    @Test
    fun `a nested sequence is dropped rather than throwing`() {
        val document = """
            {"version":1,"assignments":[
              {"uidHex":"041C4E52CE7C80","label":"Bad",
               "action":{"type":"steps","steps":[
                  {"type":"steps","steps":[{"type":"sendIntent","intentAction":"A"}]}
               ]}}
            ]}
        """.trimIndent()

        assertTrue(TagActionSerializer.decode(document).isEmpty())
    }

    @Test
    fun `an unknown action type from a newer build costs only its own assignment`() {
        val document = """
            {"version":1,"assignments":[
              {"uidHex":"041C4E52CE7C80","label":"Future","action":{"type":"teleport"}},
              {"uidHex":"040E66A2F07B81","label":"Known","action":{"type":"sendIntent","intentAction":"A"}}
            ]}
        """.trimIndent()

        val decoded = TagActionSerializer.decode(document)

        assertEquals(1, decoded.size)
        assertEquals("Known", decoded.single().label)
    }
}
