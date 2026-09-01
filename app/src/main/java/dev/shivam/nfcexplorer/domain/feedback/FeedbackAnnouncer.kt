package dev.shivam.nfcexplorer.domain.feedback

/**
 * Tells the user what a tap did.
 *
 * A seam for the same reason as `TagTransport` and `IntentSpec`: everything on the other side of it
 * is `MediaPlayer` and `Toast`, neither of which exists on the JVM, and the decision about *what* to
 * say is already tested without them in [TapFeedback].
 */
fun interface FeedbackAnnouncer {

    /**
     * Announces [outcome], honouring [FeedbackSettings].
     *
     * Must never throw and must never block. It is called from the trigger's dispatch path, where a
     * tag may already be leaving the field, and a tone whose file was deleted must cost silence
     * rather than a tap.
     */
    fun announce(outcome: TapOutcome)
}
