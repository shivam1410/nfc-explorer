package dev.shivam.nfcexplorer.domain.feedback

/**
 * Why an assigned tap did not run its action.
 *
 * A code rather than a sentence, for the same reason [dev.shivam.nfcexplorer.domain.model.ReadStatus]
 * and `WriteVerdict` are codes: the domain stays free of translatable prose and of any resource
 * dependency, and `ui/labels/Labels.kt` is where the two meet. The announcer resolves these against
 * `strings.xml`.
 */
enum class TapFailure {

    /** The tag was discovered but had gone by the time it was asked to answer. Tapping again fixes it. */
    CARD_LEFT_FIELD,

    /** The tag speaks no technology this app can open. Tapping again will never fix it. */
    CARD_NOT_READABLE,

    /** The action itself threw, after the trigger had already let it through. */
    ACTION_FAILED,
}

/**
 * What a tap should tell the user.
 *
 * Deliberately separate from what a tap *does*. The trigger already decides that, and it already
 * logs it; this is the same three outcomes phrased for the person holding the phone rather than for
 * whoever reads `adb logcat` afterwards.
 *
 * Framework-free, so the whole decision is unit-tested on the JVM. Everything that makes a sound or
 * draws a toast lives behind [FeedbackAnnouncer] in `data/feedback`.
 */
sealed interface TapOutcome {

    /**
     * An assigned tag was accepted and its action dispatched.
     *
     * "Dispatched", not "succeeded": the trigger deliberately does not await the action, because
     * awaiting it inside an activity that is about to finish is what killed the sleep-cycle toggle.
     * A failure that arrives afterwards announces itself separately, via
     * [TapFeedback.onActionFailure].
     */
    data class Ran(val label: String, val uidKey: String) : TapOutcome

    /**
     * An assigned tag did not run its action.
     *
     * @param detail the exception summary, and only ever set for [TapFailure.ACTION_FAILED]. It is
     *   diagnostic text that is never translated — which is exactly why it is carried separately
     *   from [failure] rather than baked into it.
     */
    data class Failed(
        val label: String,
        val uidKey: String,
        val failure: TapFailure,
        val detail: String? = null,
    ) : TapOutcome

    /**
     * Nothing to say.
     *
     * An unassigned tag, or a launch that was not a genuine NFC dispatch. Both must stay completely
     * silent: the first because random tags encountered in the world must not make this app speak,
     * the second because the trigger is exported and a toast on demand is an annoyance vector.
     */
    data object Ignored : TapOutcome
}
