package dev.shivam.nfcexplorer.domain.feedback

/**
 * What the phone does when a tag with an action is tapped.
 *
 * Read synchronously, on the dispatch path, before the action fires. That rules out a suspending
 * store: the trigger has milliseconds and a tag that may already be leaving the field, and nothing
 * observes these as a flow.
 *
 * Not synced to Drive, unlike assignments. A tone is a content URI belonging to one device's media
 * store and a volume is a property of one phone's speaker, so pushing either to another device would
 * be wrong rather than merely useless.
 */
interface FeedbackSettings {

    /** Content URI of the tone played when an action runs, or null for silent. */
    fun ranTone(): String?

    fun setRanTone(uri: String?)

    /** Content URI of the tone played when an assigned tap fails, or null for silent. */
    fun failedTone(): String?

    fun setFailedTone(uri: String?)

    /** 0..100. Applies to whichever tone plays; the platform beep is beyond this app's reach. */
    fun volumePercent(): Int

    fun setVolumePercent(percent: Int)

    /** Whether a tap that did something names it on screen. */
    fun toastsEnabled(): Boolean

    fun setToastsEnabled(enabled: Boolean)
}
