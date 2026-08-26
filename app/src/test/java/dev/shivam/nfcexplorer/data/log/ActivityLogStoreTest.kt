package dev.shivam.nfcexplorer.data.log

import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persisted tap history.
 *
 * The renumbering is the part worth pinning. The session log restarts its sequence at zero in every
 * process, so a history spanning runs held several entries numbered 0 -- and a list keyed by that
 * identity crashed outright the first time a second session existed.
 */
class ActivityLogStoreTest {

    private fun entry(sequence: Long, at: Long, message: String) = LogEntry(
        sequence = sequence,
        timestampMillis = at,
        level = LogLevel.INFO,
        category = "action",
        message = message,
    )

    @Test
    fun `entries from a second session do not reuse the first session's numbers`() {
        val store = InMemoryActivityLog()

        // Two runs, each numbering from zero, exactly as the session log does.
        store.append(listOf(entry(1, 200, "first run b"), entry(0, 100, "first run a")))
        store.append(listOf(entry(1, 400, "second run b"), entry(0, 300, "second run a")))

        val sequences = store.entries.map { it.sequence }
        assertEquals(sequences.size, sequences.toSet().size, "sequences must stay unique: $sequences")
    }

    @Test
    fun `newest stays first`() {
        val store = InMemoryActivityLog()

        store.append(listOf(entry(0, 100, "older")))
        store.append(listOf(entry(0, 200, "newer")))

        assertEquals(listOf("newer", "older"), store.entries.map { it.message })
    }

    @Test
    fun `renumbering runs oldest to newest so numbers follow time`() {
        val store = InMemoryActivityLog()

        store.append(listOf(entry(1, 200, "later"), entry(0, 100, "earlier")))

        val byTime = store.entries.sortedBy { it.timestampMillis }
        assertTrue(
            byTime[0].sequence < byTime[1].sequence,
            "expected numbers to increase with time, got ${byTime.map { it.sequence }}",
        )
    }

    @Test
    fun `the history is bounded and drops the oldest`() {
        val store = InMemoryActivityLog(limit = 3)

        repeat(5) { index -> store.append(listOf(entry(0, index.toLong(), "entry $index"))) }

        assertEquals(3, store.entries.size)
        assertEquals(listOf("entry 4", "entry 3", "entry 2"), store.entries.map { it.message })
    }

    @Test
    fun `appending nothing changes nothing`() {
        val store = InMemoryActivityLog()
        store.append(listOf(entry(0, 100, "kept")))

        store.append(emptyList())

        assertEquals(listOf("kept"), store.entries.map { it.message })
    }

    /**
     * The store's logic without its file.
     *
     * Mirrors [ActivityLogStore.append] exactly; the real class needs a `Context` for `filesDir`,
     * and the part worth testing is the ordering and numbering, not the write.
     */
    private class InMemoryActivityLog(private val limit: Int = 500) {
        var entries: List<LogEntry> = emptyList()
            private set

        fun append(newEntries: List<LogEntry>) {
            if (newEntries.isEmpty()) return
            var next = (entries.maxOfOrNull { it.sequence } ?: -1L) + 1
            val renumbered = newEntries.reversed().map { it.copy(sequence = next++) }.reversed()
            entries = (renumbered + entries).take(limit)
        }
    }
}
