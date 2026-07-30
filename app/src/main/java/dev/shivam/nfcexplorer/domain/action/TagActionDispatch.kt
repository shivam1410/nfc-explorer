package dev.shivam.nfcexplorer.domain.action

/**
 * Decides whether a launch should run a stored action.
 *
 * This is the security guard for the exported trigger activity, and it is a pure function on purpose.
 * `TagActionActivity` **must** be exported or NFC dispatch cannot reach it, which means any app on the
 * device can start it — and it runs stored intents. Without this check another app could invoke it
 * repeatedly to fire whatever the user has configured.
 *
 * Keeping the decision here rather than inside the activity is what makes it testable: this project
 * has no `androidTest` source set, and the previous review found its CRITICAL defect in precisely this
 * kind of activity-level plumbing that no unit test covered.
 *
 * All three conditions are necessary and none is sufficient, swept in `TagActionDispatchTest`.
 */
object TagActionDispatch {

    /** The three NFC dispatch actions, spelled out so this stays in `domain/`. */
    val NFC_ACTIONS = setOf(
        "android.nfc.action.NDEF_DISCOVERED",
        "android.nfc.action.TECH_DISCOVERED",
        "android.nfc.action.TAG_DISCOVERED",
    )

    /**
     * @param intentAction the launching intent's action. Trivially spoofable on its own, which is why
     *   it is never sufficient by itself.
     * @param hasTagExtra whether the intent carries `NfcAdapter.EXTRA_TAG`. Only the platform supplies
     *   this, so it is the part a hostile caller cannot fake by writing a string.
     * @param assignment the stored assignment for the tag's UID, or null when the tag is unassigned.
     */
    fun shouldAct(
        intentAction: String?,
        hasTagExtra: Boolean,
        assignment: TagAssignment?,
    ): Boolean = intentAction in NFC_ACTIONS && hasTagExtra && assignment != null
}
