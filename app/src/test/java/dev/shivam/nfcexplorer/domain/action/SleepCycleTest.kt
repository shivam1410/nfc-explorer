package dev.shivam.nfcexplorer.domain.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the values that were measured off a real device.
 *
 * These are not arbitrary constants that a later edit may freely tune: each one was established by
 * testing against Sleep Cycle and getting it wrong first. The tests exist so a plausible-looking
 * "tidy up" -- shortening the drag, dropping the foreground guard, matching on notification text --
 * fails here instead of silently on someone's phone at 3am.
 */
class SleepCycleTest {

    @Test
    fun `starting sends the exported activity action`() {
        val start = SleepCycle.start()

        assertEquals(TagAction.SendIntent("com.northcube.sleepcycle.STARTUP_ALARM"), start)
    }

    @Test
    fun `the toggle watches the tracking channel rather than notification text`() {
        val toggle = SleepCycle.toggle() as TagAction.WhileNotificationShowing

        assertEquals("com.northcube.sleepcycle", toggle.packageName)
        // Channel ids are never translated; "Analysis in progress" only matches an English phone.
        assertEquals("CHANNEL_SLEEP_NOTIFICATION", toggle.channelId)
    }

    @Test
    fun `a running session takes the stop branch and no session takes the start branch`() {
        val toggle = SleepCycle.toggle() as TagAction.WhileNotificationShowing

        assertEquals(SleepCycle.stop(), toggle.showing)
        assertEquals(SleepCycle.start(), toggle.absent)
    }

    @Test
    fun `stopping raises the sleep screen before dragging on it`() {
        val steps = (SleepCycle.stop() as TagAction.Steps).steps

        // Order is the point: a drag with nothing under it lands on whatever app is in front.
        assertEquals(SleepCycle.start(), steps.first())
        assertTrue(steps.last() is TagAction.DragGesture, "the last step must be the drag")
        assertEquals(2, steps.size)
    }

    @Test
    fun `the stop drag is guarded on Sleep Cycle being in front`() {
        val drag = (SleepCycle.stop() as TagAction.Steps).steps
            .filterIsInstance<TagAction.DragGesture>()
            .single()

        assertEquals("com.northcube.sleepcycle", drag.requireForegroundPackage)
    }

    /**
     * A drag ending a third of the way up the screen was tested and did nothing; the same drag
     * continued further ended the session. Short travel is the failure mode this guards.
     */
    @Test
    fun `the stop drag travels far enough up the screen to trigger the slider`() {
        val drag = (SleepCycle.stop() as TagAction.Steps).steps
            .filterIsInstance<TagAction.DragGesture>()
            .single()

        val travelled = drag.startYRatio - drag.endYRatio
        assertTrue(travelled > 0.3f, "expected a long upward drag, travelled $travelled")
        assertEquals(drag.startXRatio, drag.endXRatio, "the slider is vertical")
    }

    @Test
    fun `the stop drag dwells before moving and moves in steps`() {
        val drag = (SleepCycle.stop() as TagAction.Steps).steps
            .filterIsInstance<TagAction.DragGesture>()
            .single()

        // A smooth swipe of the right length was tested twice and did nothing at all.
        assertTrue(drag.holdMillis > 0, "the press must dwell before it moves")
        assertTrue(drag.steps >= 5, "movement must be stepped, got ${drag.steps}")
    }
}
