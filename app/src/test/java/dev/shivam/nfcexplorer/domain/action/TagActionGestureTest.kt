package dev.shivam.nfcexplorer.domain.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Construction-time invariants for the two action types added for the Sleep Cycle toggle.
 *
 * They validate in `init` for the same reason every other action does: these are stored once and then
 * fired months later by a tap with nobody watching, so an off-screen coordinate or an empty step list
 * has to fail where it is written rather than where it runs.
 */
class TagActionGestureTest {

    /**
     * A valid drag, with one field overridden per test.
     *
     * `LongParameterList` is suppressed rather than obeyed. The rule exists to catch functions whose
     * callers must remember an argument order; this has a default for every parameter and is called
     * as `drag(startX = 1080f)`. A parameter object would satisfy the rule by making every call site
     * construct one — which is the shape the builder exists to avoid.
     */
    @Suppress("LongParameterList")
    private fun drag(
        startX: Float = 0.5f,
        startY: Float = 0.8f,
        endX: Float = 0.5f,
        endY: Float = 0.4f,
        holdMillis: Long = 150,
        travelMillis: Long = 1_000,
        steps: Int = 10,
        requireForegroundPackage: String? = null,
    ) = TagAction.DragGesture(
        startXRatio = startX,
        startYRatio = startY,
        endXRatio = endX,
        endYRatio = endY,
        holdMillis = holdMillis,
        travelMillis = travelMillis,
        steps = steps,
        requireForegroundPackage = requireForegroundPackage,
    )

    @Test
    fun `a drag within the screen is accepted`() {
        val gesture = drag()

        assertEquals(0.8f, gesture.startYRatio)
    }

    @Test
    fun `coordinates outside the screen are refused`() {
        // Ratios, not pixels: 1080 is not "the right edge", it is nonsense.
        assertFailsWith<IllegalArgumentException> { drag(startX = 1080f) }
        assertFailsWith<IllegalArgumentException> { drag(endY = -0.1f) }
    }

    @Test
    fun `a drag must have somewhere to travel and something to travel in`() {
        assertFailsWith<IllegalArgumentException> { drag(travelMillis = 0) }
        assertFailsWith<IllegalArgumentException> { drag(holdMillis = -1) }
        // One step is a teleport, which is the smooth swipe that was proven not to work.
        assertFailsWith<IllegalArgumentException> { drag(steps = 1) }
    }

    @Test
    fun `a blank foreground guard is refused but no guard is allowed`() {
        assertFailsWith<IllegalArgumentException> { drag(requireForegroundPackage = " ") }

        assertEquals(null, drag(requireForegroundPackage = null).requireForegroundPackage)
    }

    @Test
    fun `steps run in the order given`() {
        val first = TagAction.SendIntent("com.example.FIRST")
        val second = drag()

        val sequence = TagAction.Steps(listOf(first, second))

        assertEquals(listOf<TagAction.Leaf>(first, second), sequence.steps)
    }

    @Test
    fun `an empty sequence is refused`() {
        assertFailsWith<IllegalArgumentException> { TagAction.Steps(emptyList()) }
    }

    /**
     * Flat by construction. Nesting would make the total run time of a tap-triggered action
     * unpredictable, and it buys nothing that a longer flat list does not.
     */
    @Test
    fun `sequences cannot nest`() {
        val inner = TagAction.Steps(listOf(TagAction.SendIntent("com.example.A")))

        assertFailsWith<IllegalArgumentException> { TagAction.Steps(listOf(inner)) }
    }

    @Test
    fun `a toggle needs both a package and a channel`() {
        val leaf = TagAction.SendIntent("com.example.A")

        assertFailsWith<IllegalArgumentException> {
            TagAction.WhileNotificationShowing(" ", "CHANNEL", leaf, leaf)
        }
        assertFailsWith<IllegalArgumentException> {
            TagAction.WhileNotificationShowing("com.example", " ", leaf, leaf)
        }
    }

    @Test
    fun `gesture and sequence actions are leaves so the mapper can accept them`() {
        // The Leaf split is what lets IntentSpecMapper stay total; if these stopped being leaves the
        // mapper would silently need a failure path.
        assertTrue(drag() is TagAction.Leaf)
        assertTrue(TagAction.Steps(listOf(drag())) is TagAction.Leaf)
    }
}
