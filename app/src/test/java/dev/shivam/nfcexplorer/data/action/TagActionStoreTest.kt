package dev.shivam.nfcexplorer.data.action

import app.cash.turbine.test
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
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

    // --- Save and find ---

    @Test
    fun `a saved assignment can be found by its UID`() = runTest {
        val store = TagActionStore(FakeDocumentStore())

        store.save(deskCard())

        assertEquals(deskCard(), store.find(uidA))
    }

    @Test
    fun `an unassigned UID is not found`() = runTest {
        val store = TagActionStore(FakeDocumentStore())
        store.save(deskCard())

        assertNull(store.find(uidB))
    }

    @Test
    fun `find matches on UID bytes, not on object identity`() = runTest {
        val store = TagActionStore(FakeDocumentStore())
        store.save(deskCard())

        // A freshly constructed ByteBlock with the same bytes must match — on the dispatch path the
        // UID comes from a live tag, never from the same instance that was stored.
        val sameBytes = ByteBlock.copyOf(uidA.toByteArray())
        assertEquals("Desk", store.find(sameBytes)?.label)
    }

    @Test
    fun `saving the same UID replaces rather than duplicates`() = runTest {
        val store = TagActionStore(FakeDocumentStore())

        store.save(deskCard(label = "Old"))
        store.save(deskCard(label = "New"))

        val all = store.observeAll().first()
        assertEquals(1, all.size, "UID is the key, so there can only be one entry per tag")
        assertEquals("New", all.single().label)
    }

    @Test
    fun `saving one assignment leaves the others intact`() = runTest {
        val store = TagActionStore(FakeDocumentStore())

        store.save(deskCard())
        store.save(bedsideCard())

        assertEquals(2, store.observeAll().first().size)
        assertEquals("Desk", store.find(uidA)?.label)
        assertEquals("Bedside", store.find(uidB)?.label)
    }

    // --- Delete ---

    @Test
    fun `delete removes only the named assignment`() = runTest {
        val store = TagActionStore(FakeDocumentStore())
        store.save(deskCard())
        store.save(bedsideCard())

        store.delete(uidA)

        assertNull(store.find(uidA))
        assertEquals("Bedside", store.find(uidB)?.label)
    }

    @Test
    fun `deleting an unknown UID is a no-op, not an error`() = runTest {
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents)
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
        val store = TagActionStore(FakeDocumentStore())

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
        val store = TagActionStore(FakeDocumentStore())

        assertTrue(store.observeAll().first().isEmpty())
        assertNull(store.find(uidA))
    }

    @Test
    fun `a corrupt document behaves as though nothing were assigned`() = runTest {
        val documents = FakeDocumentStore()
        val store = TagActionStore(documents)
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
        val store = TagActionStore(documents)
        documents.corrupt()

        store.save(deskCard())

        assertEquals("Desk", store.find(uidA)?.label)
    }
}

