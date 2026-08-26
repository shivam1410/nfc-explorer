package dev.shivam.nfcexplorer.domain.log

import dev.shivam.nfcexplorer.logging.LogEntry

/**
 * What is worth keeping, and for how long.
 *
 * One place, because the answer has to agree in three: what gets written to disk, what the Log tab
 * can still show after a restart, and what is uploaded rather than rotated away. When those three
 * drifted, the tab offered a filter for entries nothing had kept.
 *
 * The rule is the user's: keep what is shown to them, rotate the rest. A category on screen is a
 * category someone may come back to; [DIAGNOSTIC] entries exist to explain a failure while it is
 * happening and are noise by the next morning.
 *
 * Pure, and kept out of the store that uses it, so the bounds can be swept by tests without a
 * filesystem -- these decide what history a phone still has after a month of use.
 */
object LogRetention {

    /** A tap and what it performed. The reason the Log tab exists. */
    val TAPS = setOf("trigger", "action")

    /** Reading the card itself: identity, memory, lock bits. */
    val SCANS = setOf("session", "read", "write")

    /** Everything shown to the user, and so everything kept. */
    val RETAINED: Set<String> = TAPS + SCANS

    /**
     * Bounds, per tier.
     *
     * Two bounds rather than one, because a single scan writes a line per page and a tap writes
     * two. Under one shared bound, dumping a card's memory once would evict weeks of taps -- the
     * history worth keeping, evicted by the history worth glancing at.
     */
    const val TAP_LIMIT = 500
    const val SCAN_LIMIT = 300

    fun retains(category: String): Boolean = category in RETAINED

    /**
     * Adds [incoming] to [existing], renumbering and bounding each tier.
     *
     * Both lists are newest-first, as is the result.
     *
     * Renumbered because the session log restarts its sequence at zero in every process, so a
     * history spanning runs holds many entries numbered 0. The sequence is the only identity an
     * entry has, and a list keyed by it crashed outright once a second session existed.
     */
    fun append(
        existing: List<LogEntry>,
        incoming: List<LogEntry>,
        tapLimit: Int = TAP_LIMIT,
        scanLimit: Int = SCAN_LIMIT,
    ): List<LogEntry> {
        if (incoming.isEmpty()) return existing

        var next = (existing.maxOfOrNull { it.sequence } ?: -1L) + 1
        // Numbered oldest to newest, so the numbers run in the same direction as time.
        val renumbered = incoming.reversed().map { it.copy(sequence = next++) }.reversed()

        val all = renumbered + existing
        val kept = all.filter { it.category in TAPS }.take(tapLimit) +
            all.filter { it.category in SCANS }.take(scanLimit)

        // Interleaved back into one stream. Sequence, not timestamp: entries inside one millisecond
        // still have to come out in the order they happened.
        return kept.sortedByDescending { it.sequence }
    }

    /** The document holding a device's kept history. One per device, rewritten in place. */
    fun activityDocument(deviceId: String): String = "$ACTIVITY_PREFIX$deviceId.json"

    /** The document holding a device's diagnostics. One per device, overwritten each session. */
    fun diagnosticDocument(deviceId: String): String = "$DIAGNOSTIC_PREFIX$deviceId.json"

    /**
     * Which of [present] are dead weight.
     *
     * Only the session-per-file logs from the first scheme. That scheme wrote a new document every
     * time the app was launched and never removed one, so the folder grows without bound -- and
     * nothing reads them, which is what makes them safe to drop.
     *
     * Deliberately blind to the current documents, including other devices'. Those are one per
     * device and rewritten in place, so they never accumulate; deleting one because this device
     * does not recognise the name would throw away another phone's history.
     *
     * A device's own taps survive this: they are held on its disk and re-uploaded on its next sync.
     */
    fun stale(present: List<String>): List<String> =
        present.filter { it.startsWith(LEGACY_PREFIX) }

    /** Session-per-file logs from the first scheme. Named only so they can be cleaned up. */
    const val LEGACY_PREFIX = "log-"

    private const val ACTIVITY_PREFIX = "activity-"
    private const val DIAGNOSTIC_PREFIX = "diagnostic-"
}
