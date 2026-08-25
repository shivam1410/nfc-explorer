package dev.shivam.nfcexplorer.domain.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The toggle's decision, swept.
 *
 * This is the part that can be wrong in a way nobody notices until a night's sleep recording is lost,
 * so it is a pure function with a fake probe rather than something only a device can exercise.
 */
class ActionResolverTest {

    private val start = TagAction.SendIntent("com.example.START")
    private val stop = TagAction.SendIntent("com.example.STOP")

    private val toggle = TagAction.WhileNotificationShowing(
        packageName = "com.example.app",
        channelId = "CHANNEL_RUNNING",
        showing = stop,
        absent = start,
    )

    private fun probe(state: NotificationState) = NotificationProbe { _, _ -> state }

    @Test
    fun `a running session resolves to the stop branch`() {
        val resolution = ActionResolver.resolve(toggle, probe(NotificationState.Showing))

        assertEquals(ActionResolver.Resolution.Perform(stop), resolution)
    }

    @Test
    fun `no running session resolves to the start branch`() {
        val resolution = ActionResolver.resolve(toggle, probe(NotificationState.Absent))

        assertEquals(ActionResolver.Resolution.Perform(start), resolution)
    }

    /**
     * The case that matters most.
     *
     * If an unreadable state collapsed to "not running", revoking notification access would silently
     * turn the stop half of the toggle into a second start — the app would begin a fresh recording on
     * top of the one already in progress, and nothing on screen would explain why.
     */
    @Test
    fun `an unreadable state refuses rather than guessing`() {
        val resolution = ActionResolver.resolve(
            toggle,
            probe(NotificationState.Unavailable("access not granted")),
        )

        val refused = assertIs<ActionResolver.Resolution.Refused>(resolution)
        assertTrue(refused.reason.contains("access not granted"), "got: ${refused.reason}")
    }

    @Test
    fun `the probe is asked about the package and channel the action names`() {
        var askedPackage: String? = null
        var askedChannel: String? = null

        ActionResolver.resolve(toggle) { pkg, channel ->
            askedPackage = pkg
            askedChannel = channel
            NotificationState.Absent
        }

        assertEquals("com.example.app", askedPackage)
        assertEquals("CHANNEL_RUNNING", askedChannel)
    }

    @Test
    fun `a leaf action resolves to itself and never consults the probe`() {
        var asked = false

        val resolution = ActionResolver.resolve(start) { _, _ ->
            asked = true
            NotificationState.Absent
        }

        assertEquals(ActionResolver.Resolution.Perform(start), resolution)
        assertTrue(!asked, "a leaf must not need to know what is on screen")
    }

    private inline fun <reified T> assertIs(value: Any?): T {
        assertTrue(value is T, "expected ${T::class.simpleName} but got $value")
        return value as T
    }
}
