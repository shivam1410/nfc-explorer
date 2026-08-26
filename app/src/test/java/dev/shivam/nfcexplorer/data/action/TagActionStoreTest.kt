package dev.shivam.nfcexplorer.data.action

import app.cash.turbine.test
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagActionStoreTest {

    /** In-memory stand-in for the DataStore-backed document store. */
    private class FakeDocumentStore(initial: String? = null) : AssignmentDocumentStore {
        private val document = MutableStateFlow(initial)
        var writeCount = 0
            private set

        override fun observe(): Flow<String?> = document.asStateFlow()
        override suspend fun read(): String? = document.value
        override suspend fun write(document: String) {
            writeCount++
            this.document.value = document
        }

        fun corrupt() {
            document.value = "{this is not the document you are looking for"
        }
    }

    private val uidA = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)
    private val uidB = ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81)

    private fun deskCard(label: String = "Desk") =
        TagAssignment(uidA, label, TagAction.LaunchApp("com.example.notes"))

    private fun bedsideCard() =
        TagAssignment(uidB, "Bedside", TagAction.MediaCommand(MediaKey.PLAY_PAUSE))

    private val logger = SessionLogger { 0L }

    private fun warnings() = logger.entries.value.filter { it.level == LogLevel.WARN }

    // --- Save and find ---

    @Test
    fun `a saved assignment can be found by its UID`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)

        store.save(deskCard())

        assertEquals(deskCard(), store.find(uidA))
    }

    @Test
    fun `an unassigned UID is not found`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)
        store.save(deskCard())

        assertNull(store.find(uidB))
    }

    @Test
    fun `find matches on UID bytes, not on object identity`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)
        store.save(deskCard())

        // A freshly constructed ByteBlock with the same bytes must match — on the dispatch path the
        // UID comes from a live tag, never from the same instance that was stored.
        val sameBytes = ByteBlock.copyOf(uidA.toByteArray())
        assertEquals("Desk", store.find(sameBytes)?.label)
    }

    @Test
    fun `saving the same UID replaces rather than duplicates`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)

        store.save(deskCard(label = "Old"))
        store.save(deskCard(label = "New"))

        val all = store.observeAll().first()
        assertEquals(1, all.size, "UID is the key, so there can only be one entry per tag")
        assertEquals("New", all.single().label)
    }

    @Test
    fun `saving one assignment leaves the others intact`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)

        store.save(deskCard())
        store.save(bedsideCard())

        assertEquals(2, store.observeAll().first().size)
        assertEquals("Desk", store.find(uidA)?.label)
        assertEquals("Bedside", store.find(uidB)?.label)
    }

    // --- Delete ---

    @Test
    fun `delete removes only the named assignment`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)
        store.save(deskCard())
        store.save(bedsideCard())

        store.delete(uidA)

        assertNull(store.find(uidA))
        assertEquals("Bedside", store.find(uidB)?.label)
    }

    @Test
    fun `deleting an unknown UID is a no-op, not an error`() = runTest {
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents, logger)
        store.save(deskCard())
        val writesAfterSave = documents.writeCount

        store.delete(uidB)

        assertEquals("Desk", store.find(uidA)?.label)
        // No pointless rewrite of an unchanged document.
        assertEquals(writesAfterSave, documents.writeCount)
    }

    // --- Observation ---

    @Test
    fun `observeAll emits the current list and then each change`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)

        store.observeAll().test {
            assertTrue(awaitItem().isEmpty())

            store.save(deskCard())
            assertEquals(listOf("Desk"), awaitItem().map { it.label })

            store.save(bedsideCard())
            assertEquals(listOf("Desk", "Bedside"), awaitItem().map { it.label })

            store.delete(uidA)
            assertEquals(listOf("Bedside"), awaitItem().map { it.label })

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Degradation ---

    @Test
    fun `an empty store reports no assignments`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger)

        assertTrue(store.observeAll().first().isEmpty())
        assertNull(store.find(uidA))
    }

    @Test
    fun `a corrupt document behaves as though nothing were assigned`() = runTest {
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents, logger)
        store.save(deskCard())

        documents.corrupt()

        // A crash here would take out the dispatch path on a tap, with no user watching to
        // interpret it. Behaving as unassigned is the only useful option.
        assertNull(store.find(uidA))
        assertTrue(store.observeAll().first().isEmpty())
    }

    @Test
    fun `saving over a corrupt document recovers the store`() = runTest {
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents, logger)
        documents.corrupt()

        store.save(deskCard())

        assertEquals("Desk", store.find(uidA)?.label)
    }

    @Test
    fun `an unreadable document is reported rather than passing quietly as empty`() = runTest {
        // Degrading to "nothing assigned" is the right behaviour on the dispatch path, but doing it
        // silently means a user whose tags all stopped working has nothing to look at. Every other
        // failure in this app reaches the session log; this one has to as well.
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents, logger)
        store.save(deskCard())
        documents.corrupt()

        store.find(uidA)

        assertTrue(
            warnings().any { it.message.contains("could not be read", ignoreCase = true) },
            "expected a warning, got ${logger.entries.value.map { it.message }}",
        )
    }

    @Test
    fun `overwriting an unreadable document records that something was replaced`() = runTest {
        // save() merges onto what it can read, so an unreadable document means the new assignment
        // replaces whatever was there. Recovering the store is worth more than refusing forever, but
        // the replacement must not be invisible.
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents, logger)
        documents.corrupt()

        store.save(deskCard())

        assertTrue(
            warnings().any { it.message.contains("could not be read", ignoreCase = true) },
            "expected a warning, got ${logger.entries.value.map { it.message }}",
        )
    }

    @Test
    fun `an absent document is not reported as a failure`() = runTest {
        // Nothing assigned yet is the normal state on first run, not a problem to warn about.
        val store = TagActionStore(FakeDocumentStore(), logger)

        store.find(uidA)

        assertTrue(warnings().isEmpty(), "got ${warnings().map { it.message }}")
    }

    // --- Deletion leaves a tombstone ---

    /**
     * Deleting must leave evidence. A row that is merely gone looks exactly like one this device has
     * never seen, and sync restores it -- which it did, on a real phone, within an hour.
     */
    @Test
    fun `deleting keeps a tombstone that sync can see`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger) { 500 }
        store.save(TagAssignment(uidA, "Desk", TagAction.LaunchApp("com.example")))

        store.delete(uidA)

        val raw = store.snapshotForSync().single()
        assertTrue(raw.deleted, "the row must survive as a tombstone")
        assertEquals(500, raw.updatedAtMillis, "stamped so a merge can order it")
    }

    @Test
    fun `a deleted assignment is invisible above the repository`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger) { 500 }
        store.save(TagAssignment(uidA, "Desk", TagAction.LaunchApp("com.example")))

        store.delete(uidA)

        assertTrue(store.observeAll().first().isEmpty(), "the list must not show it")
        assertNull(store.find(uidA), "a tap on a deleted tag must do nothing")
    }

    @Test
    fun `recreating a deleted tag replaces its tombstone`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger) { 500 }
        store.save(TagAssignment(uidA, "Desk", TagAction.LaunchApp("com.example")))
        store.delete(uidA)

        store.save(TagAssignment(uidA, "Desk again", TagAction.LaunchApp("com.other")))

        assertEquals(listOf("Desk again"), store.observeAll().first().map { it.label })
        assertEquals(1, store.snapshotForSync().size, "one row per tag, not a pile of tombstones")
    }

    @Test
    fun `deleting an unknown tag writes nothing`() = runTest {
        val store = TagActionStore(FakeDocumentStore(), logger) { 500 }

        store.delete(uidA)

        assertTrue(store.snapshotForSync().isEmpty())
    }
}
