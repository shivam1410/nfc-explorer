package dev.shivam.nfcexplorer.domain.feedback

import dev.shivam.nfcexplorer.domain.action.TagActionDispatch
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.action.TagPresence

/**
 * Turns the state of a tap into the thing the user should be told about it.
 *
 * A pure restatement of the branch `TagActionActivity` already takes, and it takes the same three
 * parameters as [TagActionDispatch.shouldAct] in the same order for exactly that reason: the two
 * must not drift. An announcement that disagrees with the log is worse than no announcement, because
 * it is the log that gets believed.
 */
object TapFeedback {

    /**
     * What to say about a tap, at the moment the trigger decides what to do with it.
     *
     * Silent unless the launch was a genuine NFC dispatch **and** the tag is one the user has
     * assigned. Both halves matter, and for different reasons: an unassigned tag is none of this
     * app's business, and the trigger activity is exported, so announcing a refusal would hand any
     * app on the device a way to spam toasts — and to learn which UIDs are assigned by watching
     * which launches produce one.
     */
    fun onDispatch(
        intentAction: String?,
        presence: TagPresence.Answer,
        assignment: TagAssignment?,
    ): TapOutcome {
        if (assignment == null || intentAction !in TagActionDispatch.NFC_ACTIONS) {
            return TapOutcome.Ignored
        }
        return when (presence) {
            TagPresence.Answer.Live -> TapOutcome.Ran(assignment.label, assignment.uidKey)
            is TagPresence.Answer.Absent -> TapOutcome.Failed(
                label = assignment.label,
                uidKey = assignment.uidKey,
                // The distinction is the whole point of Absent carrying its cause. A fumbled tap is
                // fixed by tapping again; a card this app cannot open never will be, and telling
                // that user to hold it steadier sends them after the wrong problem forever.
                failure = when (presence.cause) {
                    null -> TapFailure.CARD_NOT_READABLE
                    else -> TapFailure.CARD_LEFT_FIELD
                },
            )
        }
    }

    /**
     * What to say about an action that failed after the trigger had already let it through.
     *
     * The exception is named here, unlike on the presence path, because it is the useful part: an
     * action that fails is a configuration the user has to go and fix, and "something went wrong"
     * would not tell them which of their tags to open.
     */
    fun onActionFailure(assignment: TagAssignment, failure: Throwable): TapOutcome.Failed {
        val name = failure::class.simpleName ?: "Throwable"
        return TapOutcome.Failed(
            label = assignment.label,
            uidKey = assignment.uidKey,
            failure = TapFailure.ACTION_FAILED,
            detail = failure.message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name,
        )
    }
}
