package dev.shivam.nfcexplorer.domain.sync

import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Merge behaviour, swept.
 *
 * The failure this guards is not a crash: it is a tag quietly reverting to last week's action on a
 * device nobody was watching. Every case below is a way that could happen.
 */
class SyncMergeTest {

    private fun uid(last: Int) = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, last)

    private fun assignment(last: Int, label: String, at: Long) = TagAssignment(
        uid = uid(last),
        label = label,
        action = TagAction.SendIntent("com.example.$label"),
        updatedAtMillis = at,
    )

    @Test
    fun `an assignment only in the cloud is adopted`() {
        val result = SyncMerge.merge(local = emptyList(), cloud = listOf(assignment(1, "Bed", 100)))

        assertEquals(listOf("Bed"), result.merged.map { it.label })
        assertEquals(1, result.fromCloud.size)
        assertTrue(result.changed)
    }

    @Test
    fun `an assignment only on this device is kept and marked for upload`() {
        val result = SyncMerge.merge(local = listOf(assignment(1, "Desk", 100)), cloud = emptyList())

        assertEquals(listOf("Desk"), result.merged.map { it.label })
        assertEquals(1, result.fromLocal.size)
    }

    /** The case whole-document last-write-wins would get wrong. */
    @Test
    fun `edits to different tags on different devices both survive`() {
        val result = SyncMerge.merge(
            local = listOf(assignment(1, "PhoneEdit", 200), assignment(2, "Shared", 50)),
            cloud = listOf(assignment(2, "Shared", 50), assignment(3, "TabletEdit", 200)),
        )

        assertEquals(listOf("PhoneEdit", "Shared", "TabletEdit"), result.merged.map { it.label }.sorted())
    }

    @Test
    fun `the newer edit to the same tag wins whichever side it is on`() {
        val cloudWins = SyncMerge.merge(
            local = listOf(assignment(1, "Old", 100)),
            cloud = listOf(assignment(1, "New", 200)),
        )
        assertEquals(listOf("New"), cloudWins.merged.map { it.label })

        val localWins = SyncMerge.merge(
            local = listOf(assignment(1, "New", 200)),
            cloud = listOf(assignment(1, "Old", 100)),
        )
        assertEquals(listOf("New"), localWins.merged.map { it.label })
    }

    /**
     * Documents written before sync existed carry no timestamp. They must lose to anything edited
     * since, rather than winning by accident and reverting a real change.
     */
    @Test
    fun `an untimestamped assignment loses to a timestamped one`() {
        val result = SyncMerge.merge(
            local = listOf(assignment(1, "Legacy", 0)),
            cloud = listOf(assignment(1, "Edited", 1)),
        )

        assertEquals(listOf("Edited"), result.merged.map { it.label })
    }

    @Test
    fun `an identical round trip reports nothing to do`() {
        val same = listOf(assignment(1, "Bed", 100))

        val result = SyncMerge.merge(local = same, cloud = same)

        assertEquals(same, result.merged)
        assertFalse(result.changed, "an unchanged sync must not schedule a write")
    }

    /** Two edits in the same millisecond cannot be ordered; keep local rather than guess. */
    @Test
    fun `a tie keeps the local copy`() {
        val result = SyncMerge.merge(
            local = listOf(assignment(1, "Mine", 100)),
            cloud = listOf(assignment(1, "Theirs", 100)),
        )

        assertEquals(listOf("Mine"), result.merged.map { it.label })
        assertFalse(result.changed)
    }

    @Test
    fun `merging two empty stores is empty and quiet`() {
        val result = SyncMerge.merge(emptyList(), emptyList())

        assertTrue(result.merged.isEmpty())
        assertFalse(result.changed)
    }

    // --- Deletions ---

    private fun tombstone(last: Int, label: String, at: Long) =
        assignment(last, label, at).copy(deleted = true)

    /** The case that made tombstones necessary: sync used to restore whatever had been deleted. */
    @Test
    fun `a deletion beats the older copy still in the cloud`() {
        val result = SyncMerge.merge(
            local = listOf(tombstone(1, "Gone", 200)),
            cloud = listOf(assignment(1, "Gone", 100)),
        )

        val survivor = result.merged.single()
        assertTrue(survivor.deleted, "the deletion is newer, so it must win")
    }

    @Test
    fun `a deletion arriving from the cloud removes the local copy`() {
        val result = SyncMerge.merge(
            local = listOf(assignment(1, "Gone", 100)),
            cloud = listOf(tombstone(1, "Gone", 200)),
        )

        assertTrue(result.merged.single().deleted)
        assertEquals(1, result.fromCloud.size, "the tombstone has to be written locally")
    }

    /** Deleting is not permanent across devices: recreating a tag afterwards must stick. */
    @Test
    fun `an edit made after a deletion wins`() {
        val result = SyncMerge.merge(
            local = listOf(assignment(1, "Recreated", 300)),
            cloud = listOf(tombstone(1, "Gone", 200)),
        )

        val survivor = result.merged.single()
        assertFalse(survivor.deleted)
        assertEquals("Recreated", survivor.label)
    }

    @Test
    fun `a tombstone only this device has is pushed rather than forgotten`() {
        val result = SyncMerge.merge(
            local = listOf(tombstone(1, "Gone", 200)),
            cloud = emptyList(),
        )

        assertEquals(1, result.fromLocal.size)
        assertTrue(result.merged.single().deleted)
    }
}
