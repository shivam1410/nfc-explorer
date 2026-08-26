package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlinx.coroutines.flow.Flow

/**
 * Stores tag-to-action assignments.
 *
 * [find] is the hot path: it runs on a tap, with the app possibly not otherwise running, so it must
 * answer from local storage without any UI or network involvement.
 *
 * A store that cannot be read reports **no assignments** rather than failing. On the dispatch path
 * that means an unreadable store causes a tap to do nothing, which is the same as an unassigned tag —
 * far better than a crash on a tap the user cannot see.
 */
interface TagActionRepository {

    /** Live assignments. Tombstones are filtered out; nothing above this layer should see one. */
    fun observeAll(): Flow<List<TagAssignment>>

    /**
     * Everything stored, tombstones included.
     *
     * Only sync needs this: a deletion cannot propagate if the thing that propagates never sees it.
     */
    suspend fun snapshotForSync(): List<TagAssignment>

    suspend fun find(uid: ByteBlock): TagAssignment?

    suspend fun save(assignment: TagAssignment)

    suspend fun delete(uid: ByteBlock)
}
