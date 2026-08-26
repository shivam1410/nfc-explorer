package dev.shivam.nfcexplorer.data.action

import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Assignment repository over an [AssignmentDocumentStore].
 *
 * All the logic worth testing lives here rather than in the `DataStore` implementation: merging on
 * save, matching by UID **bytes** rather than object identity, and degrading to "no assignments" when
 * the document cannot be read. Driven by a fake document store in `TagActionStoreTest`.
 *
 * The UID key is what makes matching work at all. On the dispatch path the UID arrives from a live
 * tag, so it is never the same [ByteBlock] instance that was stored — the lowercase-hex key is the
 * stable identity.
 */
class TagActionStore(
    private val documents: AssignmentDocumentStore,
    private val logger: SessionLogger,
    /** Injected so tombstone timestamps are deterministic in tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : TagActionRepository {

    override fun observeAll(): Flow<List<TagAssignment>> =
        documents.observe().map { document ->
            TagActionSerializer.decode(document).filterNot { it.deleted }
        }

    override suspend fun snapshotForSync(): List<TagAssignment> = current()

    override suspend fun find(uid: ByteBlock): TagAssignment? {
        val key = TagAssignment.uidKeyOf(uid)
        // A tombstone must not answer a tap: the tag was deleted, so it does nothing.
        return current().firstOrNull { it.uidKey == key && !it.deleted }
    }

    override suspend fun save(assignment: TagAssignment) {
        // Replace any existing entry for this UID rather than appending: the UID is the key, so a tag
        // can only ever have one action.
        val merged = current().filterNot { it.uidKey == assignment.uidKey } + assignment
        documents.write(TagActionSerializer.encode(merged))
    }

    /**
     * Marks the assignment deleted rather than removing the row.
     *
     * The row is what carries the deletion to other devices. Dropping it instead would leave the
     * store looking exactly as it does on a device that has simply never seen the tag, and the next
     * sync would helpfully restore it.
     */
    override suspend fun delete(uid: ByteBlock) {
        val key = TagAssignment.uidKeyOf(uid)
        val existing = current()
        val target = existing.firstOrNull { it.uidKey == key } ?: return
        if (target.deleted) return

        val tombstoned = existing.map { assignment ->
            if (assignment.uidKey == key) {
                assignment.copy(deleted = true, updatedAtMillis = now())
            } else {
                assignment
            }
        }
        documents.write(TagActionSerializer.encode(tombstoned))
    }

    /**
     * The stored assignments, or an empty list if the document is absent or unreadable.
     *
     * Deliberately indistinguishable *outcomes*: a corrupt store must behave exactly like an empty one,
     * because the alternative on the dispatch path is a crash during a tap that nobody is watching.
     *
     * Not indistinguishable in the log, though. Degrading quietly means a user whose tags all stopped
     * working has nothing to look at, and because [save] merges onto what it can read, an unreadable
     * document also means the next save replaces whatever was there. Recovering the store is worth more
     * than refusing writes forever, but the replacement must not be invisible.
     */
    private suspend fun current(): List<TagAssignment> {
        val document = documents.read()
        val decoded = TagActionSerializer.decode(document)

        if (decoded.isEmpty() && !document.isNullOrBlank()) {
            logger.warn(
                category = CATEGORY,
                message = "stored assignments could not be read; treating the store as empty",
                payload = mapOf("documentLength" to document.length.toString()),
            )
        }
        return decoded
    }

    private companion object {
        const val CATEGORY = "actions"
    }
}
