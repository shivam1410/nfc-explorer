package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The security guard for the exported trigger activity.
 *
 * `TagActionActivity` **must** be exported or NFC dispatch cannot reach it, which means any app on the
 * device can start it — and it runs stored intents. Without this predicate another app could invoke it
 * repeatedly to fire whatever the user configured.
 *
 * Written as a pure function precisely so it can be tested at all: there is no `androidTest` source
 * set in this project, and the previous review found the CRITICAL defect in exactly this kind of
 * activity-level plumbing that no unit test was covering.
 */
class TagActionDispatchTest {

    private val live = TagPresence.Answer.Live

    /** A tag that never answered — a forged parcel, or one that left the field. */
    private val absent = TagPresence.Answer.Absent(cause = null)

    private val assignment = TagAssignment(
        uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E),
        label = "Desk",
        action = TagAction.LaunchApp("com.example.notes"),
    )

    // --- The only case that acts ---

    @Test
    fun `acts for a genuine NFC dispatch with a tag and a matching assignment`() {
        TagActionDispatch.NFC_ACTIONS.forEach { action ->
            assertTrue(
                TagActionDispatch.shouldAct(action, presence = live, assignment = assignment),
                "should act for $action",
            )
        }
    }

    // --- The abuse case ---

    @Test
    fun `refuses a caller that is not an NFC dispatch even when the assignment matches`() {
        // The attack this guard exists for: another app starts the exported activity directly,
        // supplying whatever it likes, hoping the stored action fires.
        listOf(
            "android.intent.action.MAIN",
            "android.intent.action.VIEW",
            "com.example.MALICIOUS",
            "",
        ).forEach { action ->
            assertFalse(
                TagActionDispatch.shouldAct(action, presence = live, assignment = assignment),
                "must refuse action '$action'",
            )
        }
    }

    @Test
    fun `refuses a null intent action`() {
        assertFalse(TagActionDispatch.shouldAct(null, presence = live, assignment = assignment))
    }

    @Test
    fun `refuses an NFC action whose tag never answered`() {
        // An action string is trivially spoofable and so is a Tag parcel; answering a connection is not.
        TagActionDispatch.NFC_ACTIONS.forEach { action ->
            assertFalse(
                TagActionDispatch.shouldAct(action, presence = absent, assignment = assignment),
                "must refuse $action without a tag",
            )
        }
    }

    // --- Unassigned tags ---

    @Test
    fun `refuses a genuine dispatch when the tag has no assignment`() {
        TagActionDispatch.NFC_ACTIONS.forEach { action ->
            assertFalse(
                TagActionDispatch.shouldAct(action, presence = live, assignment = null),
                "must do nothing for an unassigned tag on $action",
            )
        }
    }

    @Test
    fun `refuses everything when nothing is assigned and the caller is hostile`() {
        assertFalse(TagActionDispatch.shouldAct("com.example.MALICIOUS", live, null))
        assertFalse(TagActionDispatch.shouldAct(null, absent, null))
    }

    // --- All three conditions are load-bearing ---

    @Test
    fun `every condition is necessary, none is sufficient`() {
        // Swept rather than sampled: exactly one of the eight combinations may act, and it is the one
        // where all three hold.
        var actedCount = 0
        listOf(true, false).forEach { nfcAction ->
            listOf(live, absent).forEach { answer ->
                listOf(assignment, null).forEach { found ->
                    val acted = TagActionDispatch.shouldAct(
                        intentAction = if (nfcAction) "android.nfc.action.TECH_DISCOVERED" else "x",
                        presence = answer,
                        assignment = found,
                    )
                    if (acted) actedCount++
                    assertTrue(
                        !acted || (nfcAction && answer == live && found != null),
                        "acted with nfc=$nfcAction tag=$answer assigned=${found != null}",
                    )
                }
            }
        }
        assertTrue(actedCount == 1, "exactly one combination should act, got $actedCount")
    }
}
