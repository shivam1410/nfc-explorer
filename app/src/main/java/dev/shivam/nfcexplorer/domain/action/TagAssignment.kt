package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.model.ByteBlock

/**
 * One tag bound to one action.
 *
 * Keyed by **UID**, which is the whole reason this works on tags that can never hold NDEF: nothing is
 * written to the tag, and the UID is broadcast by every tag before any authentication.
 */
data class TagAssignment(
    val uid: ByteBlock,
    val label: String,
    val action: TagAction,
    /**
     * When this assignment was last changed, for merging two devices' stores.
     *
     * Defaults to zero so documents written before sync existed still decode — they simply lose
     * every merge against an assignment that carries a real timestamp, which is the correct
     * outcome: anything edited since is newer than something never edited at all.
     */
    val updatedAtMillis: Long = 0,
) {
    init {
        require(!uid.isEmpty) { "a tag always reports a UID" }
        require(label.isNotBlank()) { "label must not be blank; it is how the user identifies this tag" }
    }

    /**
     * Lowercase hex with no separators, e.g. `041c4e52ce7c80`.
     *
     * Used as the storage key and matches the convention already used for export filenames, so the
     * same identifier appears in a saved dump and in the assignment store.
     */
    val uidKey: String get() = uid.toString().replace(" ", "").lowercase()

    companion object {
        fun uidKeyOf(uid: ByteBlock): String = uid.toString().replace(" ", "").lowercase()
    }
}
