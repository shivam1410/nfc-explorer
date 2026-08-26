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

    /** Everything shown to the user, and so everything kept on disk. */
    val RETAINED: Set<String> = TAPS + SCANS

    /**
     * What is worth a copy in the user's Drive: the taps, and nothing else.
     *
     * Narrower than what is kept on disk, because the two answer different questions. On the phone,
     * scan detail explains why the card in your hand behaved oddly this morning. In the cloud the
     * question is what a new phone should be handed, and the answer is what your tags did -- not
     * page-level forensics of a card you were holding on a device you no longer have.
     */
    val SYNCED: Set<String> = TAPS

    fun syncs(category: String): Boolean = category in SYNCED

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
     */
    fun append(
        existing: List<LogEntry>,
        incoming: List<LogEntry>,
        tapLimit: Int = TAP_LIMIT,
        scanLimit: Int = SCAN_LIMIT,
    ): List<LogEntry> {
        if (incoming.isEmpty()) return existing

        // Lifted above everything already held, so the merge below can order the two against each
        // other. The session log restarts its sequence at zero in every process, so without this a
        // new entry and an old one both claim to be number 3.
        var next = (existing.maxOfOrNull { it.sequence } ?: -1L) + 1
        // Numbered oldest to newest, so the numbers run in the same direction as time.
        val renumbered = incoming.reversed().map { it.copy(sequence = next++) }.reversed()

        return normalise(renumbered + existing, tapLimit, scanLimit)
    }

    /**
     * Bounds each tier and gives every entry a number no other entry holds.
     *
     * Applied on the way out of storage as well as into it, because numbering the arrivals only
     * ever fixed arrivals: a history written before that rule existed keeps its duplicates forever,
     * and the sequence is the only identity an entry has. A list keyed on it crashed outright once
     * two entries claimed the same number, and today only a compound key stands between this file
     * and that crash. Renumbering the whole list repairs the file the first time it is touched.
     *
     * Numbers are therefore positional and not stable across appends. Nothing persists them: they
     * order the list and identify a row on screen, both of which are recomputed anyway.
     */
    fun normalise(
        entries: List<LogEntry>,
        tapLimit: Int = TAP_LIMIT,
        scanLimit: Int = SCAN_LIMIT,
    ): List<LogEntry> {
        val kept = entries.filter { it.category in TAPS }.take(tapLimit) +
            entries.filter { it.category in SCANS }.take(scanLimit)

        // Interleaved back into one stream. Sequence first, because entries inside one millisecond
        // still have to come out in the order they happened; time breaks the ties a duplicated
        // number would otherwise leave to chance.
        val ordered = kept.sortedWith(
            compareByDescending<LogEntry> { it.sequence }.thenByDescending { it.timestampMillis },
        )

        val oldest = ordered.size - 1L
        return ordered.mapIndexed { index, entry -> entry.copy(sequence = oldest - index) }
    }

    /** The document holding a device's kept history. One per device, rewritten in place. */
    fun activityDocument(deviceId: String): String = "$ACTIVITY_PREFIX$deviceId.json"

    /**
     * Which of [present] are dead weight.
     *
     * The schemes this app has retired: the session-per-file logs of the first, which wrote a new
     * document on every launch and removed none, and the diagnostic documents of the second, which
     * uploaded sync and export chatter that nothing ever read back and no one asked to keep.
     *
     * Deliberately blind to the documents still in use, including other devices'. Those are one per
     * device and rewritten in place, so they never accumulate; deleting one because this device does
     * not recognise the name would throw away another phone's history.
     *
     * A device's own taps survive this: they are held on its disk and re-uploaded on its next sync.
     */
    fun stale(present: List<String>): List<String> =
        present.filter { name -> RETIRED_PREFIXES.any(name::startsWith) }

    /** Session-per-file logs from the first scheme. Named only so they can be cleaned up. */
    private const val LEGACY_PREFIX = "log-"

    private const val ACTIVITY_PREFIX = "activity-"

    /** No longer written. Named so the ones already uploaded can be removed. */
    private const val DIAGNOSTIC_PREFIX = "diagnostic-"

    private val RETIRED_PREFIXES = listOf(LEGACY_PREFIX, DIAGNOSTIC_PREFIX)
}
