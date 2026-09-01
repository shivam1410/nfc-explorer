package dev.shivam.nfcexplorer.domain.log

import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a phone still has after a month of use.
 *
 * Tests the policy directly rather than through the store, which needs a `Context` for its file:
 * the bounds and the renumbering are the part that decides whether history survives, and neither
 * needs a filesystem to be wrong.
 */
class LogRetentionTest {

    private fun entry(
        sequence: Long,
        at: Long,
        message: String,
        category: String = "action",
    ) = LogEntry(
        sequence = sequence,
        timestampMillis = at,
        level = LogLevel.INFO,
        category = category,
        message = message,
    )

    private fun append(existing: List<LogEntry>, vararg incoming: LogEntry) =
        LogRetention.append(existing, incoming.toList())

    // --- What is kept ---

    @Test
    fun `everything the log tab shows is kept`() {
        val shown = LogRetention.TAPS + LogRetention.SCANS
        shown.forEach { category ->
            assertTrue(LogRetention.retains(category), "$category is on screen, so it must be kept")
        }
    }

    @Test
    fun `chatter the user never sees is not kept`() {
        listOf("sync", "export").forEach { category ->
            assertTrue(!LogRetention.retains(category), "$category is not shown, so it must rotate")
        }
    }

    // --- What is copied to the cloud ---

    @Test
    fun `taps are copied to the cloud`() {
        LogRetention.TAPS.forEach { category ->
            assertTrue(LogRetention.syncs(category), "$category is the history a new phone wants")
        }
    }

    /**
     * Scan detail explains a card you are still holding, on the phone that read it. A copy in the
     * user's Drive answers a question nobody asked, and they asked for it not to be there.
     */
    @Test
    fun `scan detail stays on the phone`() {
        LogRetention.SCANS.forEach { category ->
            assertTrue(!LogRetention.syncs(category), "$category must not be uploaded")
            assertTrue(LogRetention.retains(category), "$category must still be kept on disk")
        }
    }

    // --- Numbering ---

    /** The crash this guards: a list keyed by sequence, with two entries numbered 0. */
    @Test
    fun `entries from a second session do not reuse the first session's numbers`() {
        var history = append(emptyList(), entry(1, 200, "first run b"), entry(0, 100, "first run a"))
        history = append(history, entry(1, 400, "second run b"), entry(0, 300, "second run a"))

        val sequences = history.map { it.sequence }
        assertEquals(sequences.size, sequences.toSet().size, "sequences must stay unique: $sequences")
    }

    @Test
    fun `newest stays first`() {
        var history = append(emptyList(), entry(0, 100, "older"))
        history = append(history, entry(0, 200, "newer"))

        assertEquals(listOf("newer", "older"), history.map { it.message })
    }

    @Test
    fun `renumbering runs oldest to newest so numbers follow time`() {
        val history = append(emptyList(), entry(1, 200, "later"), entry(0, 100, "earlier"))

        val byTime = history.sortedBy { it.timestampMillis }
        assertTrue(
            byTime[0].sequence < byTime[1].sequence,
            "expected numbers to increase with time, got ${byTime.map { it.sequence }}",
        )
    }

    @Test
    fun `appending nothing changes nothing`() {
        val history = append(emptyList(), entry(0, 100, "kept"))

        assertEquals(history, LogRetention.append(history, emptyList()))
    }

    // --- Bounds ---

    @Test
    fun `the history is bounded and drops the oldest`() {
        var history = emptyList<LogEntry>()
        repeat(5) { index ->
            val incoming = listOf(entry(0, index.toLong(), "entry $index"))
            history = LogRetention.append(history, incoming, tapLimit = 3)
        }

        assertEquals(listOf("entry 4", "entry 3", "entry 2"), history.map { it.message })
    }

    /**
     * The reason there are two bounds.
     *
     * One scan writes a line per page; one tap writes two. Under a single bound, dumping a card's
     * memory once would evict weeks of taps -- losing the history worth keeping to make room for
     * the history worth glancing at.
     */
    @Test
    fun `a burst of scan detail cannot evict the taps`() {
        val tap = entry(0, 1, "started a timer", category = "trigger")
        var history = LogRetention.append(emptyList(), listOf(tap), tapLimit = 2, scanLimit = 2)

        val burst = (0..20).map { entry(0, 100L + it, "page $it", category = "read") }
        history = LogRetention.append(history, burst.reversed(), tapLimit = 2, scanLimit = 2)

        assertEquals(
            listOf("started a timer"),
            history.filter { it.category == "trigger" }.map { it.message },
            "the tap must still be there",
        )
        assertEquals(2, history.count { it.category == "read" }, "scan detail stays bounded")
    }

    @Test
    fun `a category that is not kept is dropped rather than stored`() {
        val history = append(emptyList(), entry(0, 100, "merged assignments", category = "sync"))

        assertEquals(emptyList(), history)
    }


    /**
     * Taken from a real phone: a history carried forward from before the numbering rule existed
     * held two entries numbered 0. Numbering the arrivals never touched them, and only a compound
     * list key stood between that file and the crash the rule was written to stop.
     */
    @Test
    fun `a stored history with duplicate numbers is repaired`() {
        val stored = listOf(
            entry(1, 1_700_000_100, "later", category = "trigger"),
            entry(0, 1_700_000_050, "same number, newer", category = "trigger"),
            entry(0, 1_700_000_000, "same number, older", category = "trigger"),
        )

        val repaired = LogRetention.normalise(stored)

        val sequences = repaired.map { it.sequence }
        assertEquals(sequences.size, sequences.toSet().size, "duplicates must not survive: $sequences")
        assertEquals(
            listOf("later", "same number, newer", "same number, older"),
            repaired.map { it.message },
            "the newer of two entries sharing a number must still come first",
        )
    }

    @Test
    fun `numbers stay unique once scan detail and taps are interleaved`() {
        var history = LogRetention.append(
            emptyList(),
            listOf(entry(1, 200, "tap", category = "trigger"), entry(0, 100, "scan", category = "read")),
        )
        history = LogRetention.append(
            history,
            listOf(
                entry(1, 400, "scan again", category = "read"),
                entry(0, 300, "tap again", category = "trigger"),
            ),
        )

        val sequences = history.map { it.sequence }
        assertEquals(sequences.size, sequences.toSet().size, "sequences must stay unique: $sequences")
        assertEquals(
            listOf("scan again", "tap again", "tap", "scan"),
            history.map { it.message },
            "both tiers must come back as one stream, newest first",
        )
    }


    // --- Coming back after a reinstall ---

    /** The whole point: a wiped phone finds the document it uploaded and takes its taps back. */
    @Test
    fun `a wiped phone recovers the history it uploaded`() {
        val uploaded = listOf(
            entry(1, 1_700_000_100, "started a timer", category = "trigger"),
            entry(0, 1_700_000_000, "sent intent"),
        )

        val restored = LogRetention.restore(local = emptyList(), recovered = uploaded)

        assertEquals(listOf("started a timer", "sent intent"), restored.map { it.message })
    }

    /**
     * The failure this is most likely to have: numbers are positional and assigned per device, so
     * the same tap holds a different one at each end. Matching on them would restore the entire
     * history again on every single sync.
     */
    @Test
    fun `restoring the same history twice adds nothing the second time`() {
        val uploaded = listOf(
            entry(7, 1_700_000_100, "started a timer", category = "trigger"),
            entry(6, 1_700_000_000, "sent intent"),
        )
        val once = LogRetention.restore(local = emptyList(), recovered = uploaded)

        // Renumbered on the way in, so the second pass sees numbers that match nothing it holds.
        val twice = LogRetention.restore(local = once, recovered = uploaded)

        assertEquals(once, twice, "a repeated restore must be a no-op")
    }

    @Test
    fun `an entry differing only in payload is not treated as already held`() {
        val held = entry(0, 1_700_000_000, "dump finished", category = "read")
            .copy(payload = mapOf("pagesRead" to "0"))
        val other = held.copy(payload = mapOf("pagesRead" to "44"))

        val restored = LogRetention.restore(local = listOf(held), recovered = listOf(other))

        assertEquals(2, restored.size, "different payloads are different entries")
    }

    /**
     * Recovered entries are usually older than everything held. Appending would number a tap from
     * last week above one from this morning, and the list is ordered by number, not by clock.
     */
    @Test
    fun `recovered entries are interleaved by time rather than stacked on top`() {
        val held = listOf(entry(0, 1_700_009_000, "this morning", category = "trigger"))
        val old = listOf(
            entry(0, 1_700_005_000, "last week", category = "trigger"),
            entry(1, 1_700_001_000, "the week before", category = "trigger"),
        )

        val restored = LogRetention.restore(local = held, recovered = old)

        assertEquals(
            listOf("this morning", "last week", "the week before"),
            restored.map { it.message },
        )
        val sequences = restored.map { it.sequence }
        assertEquals(sequences.sortedDescending(), sequences, "numbers must follow the order shown")
    }

    @Test
    fun `a restore cannot push the history past its bounds`() {
        val held = (0..4).map { entry(0, 2_000L + it, "held $it", category = "trigger") }
        val recovered = (0..9).map { entry(0, 1_000L + it, "old $it", category = "trigger") }

        val restored = LogRetention.restore(held, recovered, tapLimit = 6)

        assertEquals(6, restored.size)
        assertTrue(
            restored.take(5).map { it.message } ==
                held.sortedByDescending { it.timestampMillis }.map { it.message },
            "the newest entries must be the ones that survive, got ${restored.map { it.message }}",
        )
    }

    @Test
    fun `restoring nothing changes nothing`() {
        val held = listOf(entry(0, 100, "kept", category = "trigger"))

        assertEquals(held, LogRetention.restore(held, emptyList()))
    }

    // --- Documents ---

    @Test
    fun `each device owns one document`() {
        assertEquals("activity-abc123.json", LogRetention.activityDocument("abc123"))
    }

    @Test
    fun `the documents of both retired schemes are stale`() {
        val present = listOf(
            "log-abc123-1700000000000.json",
            "log-abc123-1700000900000.json",
            "diagnostic-abc123.json",
        )

        assertEquals(present, LogRetention.stale(present))
    }

    /**
     * The mistake this guards against cost another phone its history.
     *
     * Current documents are one per device and rewritten in place, so they never accumulate.
     * Deleting one merely because this device does not recognise the name would throw away a
     * history that device is still keeping.
     */
    @Test
    fun `another device's current documents are left alone`() {
        val present = listOf(
            "activity-otherdevice.json",
            "actions.json",
            "log-otherdevice-1700000000000.json",
        )

        assertEquals(listOf("log-otherdevice-1700000000000.json"), LogRetention.stale(present))
    }
}
