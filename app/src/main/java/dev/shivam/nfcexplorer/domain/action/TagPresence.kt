package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.transport.TagConnection
import java.io.IOException

/**
 * Proves a tag is physically in the field, rather than merely described by an intent.
 *
 * ## Why this exists
 *
 * [TagActionDispatch] guards an activity that **must** be exported for NFC dispatch to reach it, so any
 * app on the device can start it. Two of the guard's inputs are weak: the intent action is a string
 * anyone can copy, and the UID identifies the tag but does not authenticate the caller. The third input
 * used to be "the intent carried a tag", which the activity supplied by null-checking the tag and then
 * passing `true` — a tautology that proved nothing.
 *
 * `android.nfc.Tag` is a `Parcelable` with a public `CREATOR`, so a hostile caller can construct one
 * carrying any UID. What it cannot construct is a tag that *answers*: the handle inside a `Tag` is
 * issued by the NFC service for a live discovery session, and a handle that was never issued is not in
 * that table, so opening a connection fails. Connecting is therefore the one signal in the intent that
 * cannot be forged in software.
 *
 * ## What this does not defend against
 *
 * A cloned UID. UID-rewritable tags are commodity items, and a clone is a genuinely live tag, so it
 * passes this check exactly as the original does. That is inherent to identifying tags by UID — the
 * same property MacroDroid and Tasker rely on — not a defect here. The bound on the damage is that an
 * attacker can only replay an action the user configured themselves; they cannot introduce a new one.
 * See `docs/tag-actions.md`.
 *
 * ## Cost
 *
 * A connection attempt on every tap, including taps that do nothing. It is a single exchange, but a tag
 * yanked away in the same instant it was discovered will fail it and the action will not run. That
 * trade is deliberate: the failure is "nothing happened", it is logged, and a second tap fixes it —
 * whereas the alternative is a guard that cannot tell a tap from a hostile intent.
 */
object TagPresence {

    /**
     * What happened when the tag was asked to answer.
     *
     * Carries the cause rather than collapsing to a boolean, because "the tap did nothing" has two very
     * different explanations — the tag was whipped away, or the caller was not holding a tag at all —
     * and the trigger has no UI in which to show either. The distinction has to reach the log.
     */
    sealed interface Answer {

        /** The tag is in the field and answered. */
        data object Live : Answer

        /**
         * No tag answered.
         *
         * @param cause the refusal, or null when the tag speaks no technology this app can open, in
         *   which case nothing was ever attempted.
         */
        data class Absent(val cause: IOException?) : Answer
    }

    /**
     * Opens and immediately releases [connection], reporting whether the tag answered.
     *
     * A null transport means the tag's technology is not one this app can talk to, so nothing can be
     * proven about it.
     *
     * Releases the connection on both paths: this runs on every tap, and a tag left connected blocks
     * the next reader.
     */
    fun check(connection: TagConnection?): Answer {
        if (connection == null) return Answer.Absent(cause = null)
        return try {
            connection.use { it.connect() }
            Answer.Live
        } catch (failure: IOException) {
            Answer.Absent(cause = failure)
        }
    }
}
