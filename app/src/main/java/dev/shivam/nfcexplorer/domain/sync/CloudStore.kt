package dev.shivam.nfcexplorer.domain.sync

/**
 * A small remote key-value store of text documents, scoped to this app.
 *
 * Deliberately not "a Drive client". The sync logic above it should not know or care that the
 * documents live in a Google Drive appData folder, which keeps that logic testable against a map and
 * leaves the door open to a different backing store without touching it.
 */
interface CloudStore {

    /** The document's contents, or null when it has never been written. */
    suspend fun read(name: String): Result<String?>

    /** Creates or replaces [name]. */
    suspend fun write(name: String, content: String): Result<Unit>

    /** Document names currently present, for finding session logs written by other devices. */
    suspend fun list(prefix: String): Result<List<String>>

    companion object {
        /** The single merged assignment document. */
        const val ACTIONS_DOCUMENT = "actions.json"

        /** Session logs are append-only, so each gets its own file and none can ever conflict. */
        const val LOG_PREFIX = "log-"
    }
}

/** Whether the user has granted this app access to its Drive folder. */
sealed interface CloudAccess {
    data object Granted : CloudAccess
    data object NotGranted : CloudAccess
    data class Failed(val reason: String) : CloudAccess
}

/** What a sync did, so the UI can say something specific rather than "done". */
data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val logsUploaded: Int,
) {
    val quiet: Boolean get() = pulled == 0 && pushed == 0 && logsUploaded == 0
}
