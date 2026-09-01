package dev.shivam.nfcexplorer.domain.feedback

import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.action.TagPresence
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a tap says, decided away from the framework.
 *
 * The cases mirror `TagActionDispatchTest` on purpose: the guard and the announcement read the same
 * three inputs, and the pair only stays honest if both are swept the same way.
 */
class TapFeedbackTest {

    private val assignment = TagAssignment(
        uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E),
        label = "Desk card",
        action = TagAction.LaunchApp("com.example.notes"),
    )

    private val dispatch = "android.nfc.action.TECH_DISCOVERED"
    private val live = TagPresence.Answer.Live

    // --- The tap that worked ---

    @Test
    fun `a genuine dispatch of an assigned tag names the assignment and the uid`() {
        val outcome = TapFeedback.onDispatch(dispatch, presence = live, assignment = assignment)

        assertEquals(TapOutcome.Ran(label = "Desk card", uidKey = "041c4e"), outcome)
    }

    // --- The taps that failed ---

    @Test
    fun `a card pulled away is reported as having left the field`() {
        val yanked = TagPresence.Answer.Absent(cause = IOException("tag was lost"))

        val outcome = TapFeedback.onDispatch(dispatch, presence = yanked, assignment = assignment)

        assertEquals(
            TapOutcome.Failed(
                label = "Desk card",
                uidKey = "041c4e",
                failure = TapFailure.CARD_LEFT_FIELD,
            ),
            outcome,
        )
    }

    @Test
    fun `a card this app cannot open is distinguished from a fumbled tap`() {
        // Absent with no cause means nothing was ever attempted: the tag speaks no technology this
        // app can open. Telling the user to hold the card steadier would send them after the wrong
        // problem forever.
        val unreadable = TagPresence.Answer.Absent(cause = null)

        val outcome = TapFeedback.onDispatch(dispatch, presence = unreadable, assignment = assignment)

        assertEquals(
            TapOutcome.Failed(
                label = "Desk card",
                uidKey = "041c4e",
                failure = TapFailure.CARD_NOT_READABLE,
            ),
            outcome,
        )
    }

    @Test
    fun `an action that fails afterwards names the exception, because that is the fixable part`() {
        val outcome = TapFeedback.onActionFailure(
            assignment,
            failure = IllegalStateException("no activity found to handle Intent"),
        )

        assertEquals("Desk card", outcome.label)
        assertEquals("041c4e", outcome.uidKey)
        assertEquals(TapFailure.ACTION_FAILED, outcome.failure)
        assertEquals(
            "IllegalStateException: no activity found to handle Intent",
            outcome.detail,
        )
    }

    @Test
    fun `an exception with no message still names its type rather than trailing a bare colon`() {
        val outcome = TapFeedback.onActionFailure(assignment, failure = IllegalStateException())

        assertEquals("IllegalStateException", outcome.detail)
    }

    // --- The taps that must stay silent ---

    @Test
    fun `an unassigned tag says nothing`() {
        val outcome = TapFeedback.onDispatch(dispatch, presence = live, assignment = null)

        assertEquals(TapOutcome.Ignored, outcome)
    }

    @Test
    fun `a launch that is not an NFC dispatch says nothing, even for an assigned tag`() {
        // The trigger is exported, so any app can start it. If a refusal produced a toast, a hostile
        // caller could spam the screen with them -- and confirm which UIDs are assigned while doing
        // it. Silence here is the same reasoning that makes TagActionDispatch refuse the launch.
        val outcome = TapFeedback.onDispatch(
            intentAction = "android.intent.action.MAIN",
            presence = live,
            assignment = assignment,
        )

        assertEquals(TapOutcome.Ignored, outcome)
    }

    @Test
    fun `a null intent action says nothing`() {
        val outcome = TapFeedback.onDispatch(null, presence = live, assignment = assignment)

        assertTrue(outcome is TapOutcome.Ignored)
    }
}
