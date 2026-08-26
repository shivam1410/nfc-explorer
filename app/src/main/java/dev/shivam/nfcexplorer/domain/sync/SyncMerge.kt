package dev.shivam.nfcexplorer.domain.sync

import dev.shivam.nfcexplorer.domain.action.TagAssignment

/**
 * Reconciles this device's assignments with the copy in the cloud.
 *
 * Pure, and tested hard, because this is where a sync quietly eats someone's work. The failure that
 * matters is not a crash — it is a tag silently reverting to what it pointed at last week, on a
 * device the user was not looking at.
 *
 * The rule is per-assignment, not per-document. Whole-document last-write-wins would mean editing one
 * tag on the phone and a different tag on a tablet loses one of the two edits outright, even though
 * they never touched the same thing.
 *
 * **Deletions do not propagate.** Union-by-newest cannot distinguish "deleted here" from "not yet
 * created here", so a tag deleted on one device is restored by the next sync from another. Fixing
 * that needs tombstones, which is a schema change; until then this errs toward keeping data, because
 * a resurrected assignment is an annoyance and a silently deleted one is a loss.
 */
object SyncMerge {

    /** One side of a merge, kept so callers can report what actually changed. */
    data class Result(
        val merged: List<TagAssignment>,
        val fromCloud: List<TagAssignment>,
        val fromLocal: List<TagAssignment>,
    ) {
        val changed: Boolean get() = fromCloud.isNotEmpty() || fromLocal.isNotEmpty()
    }

    /**
     * Merges [local] and [cloud], newest wins per tag.
     *
     * Ties go to [local]: when both sides carry the same timestamp they are almost certainly the same
     * edit that round-tripped, and preferring the copy already on this device avoids a pointless
     * write-back.
     */
    fun merge(local: List<TagAssignment>, cloud: List<TagAssignment>): Result {
        val localByUid = local.associateBy { it.uidKey }
        val cloudByUid = cloud.associateBy { it.uidKey }

        val takenFromCloud = mutableListOf<TagAssignment>()
        val takenFromLocal = mutableListOf<TagAssignment>()

        val merged = (localByUid.keys + cloudByUid.keys).sorted().map { uid ->
            val mine = localByUid[uid]
            val theirs = cloudByUid[uid]
            when {
                mine == null -> theirs.also { takenFromCloud += requireNotNull(it) }
                theirs == null -> mine.also { takenFromLocal += it }
                theirs.updatedAtMillis > mine.updatedAtMillis -> theirs.also { takenFromCloud += it }
                mine.updatedAtMillis > theirs.updatedAtMillis -> mine.also { takenFromLocal += it }
                // Same timestamp but different content means two edits in the same millisecond on
                // two devices. Unresolvable from the data, so keep local and say nothing changed.
                else -> mine
            }
        }.filterNotNull()

        return Result(merged = merged, fromCloud = takenFromCloud, fromLocal = takenFromLocal)
    }
}
