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

    /** Document names currently present, for finding logs left by earlier versions. */
    suspend fun list(prefix: String): Result<List<String>>

    /** Removes [name]. Succeeds whether or not it was there, so pruning can be run repeatedly. */
    suspend fun delete(name: String): Result<Unit>

    companion object {
        /** The single merged assignment document. */
        const val ACTIONS_DOCUMENT = "actions.json"
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

/**
 * Runs one sync.
 *
 * An interface so the settings view model can be tested against a fake rather than against Drive.
 */
fun interface CloudSync {
    suspend fun sync(nowMillis: Long): Result<SyncReport>
}
