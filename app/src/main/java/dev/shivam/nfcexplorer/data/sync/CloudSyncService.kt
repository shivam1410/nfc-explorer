package dev.shivam.nfcexplorer.data.sync

import dev.shivam.nfcexplorer.data.action.TagActionSerializer
import dev.shivam.nfcexplorer.data.log.ActivityLogStore
import dev.shivam.nfcexplorer.domain.log.LogRetention
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.sync.SyncMerge
import dev.shivam.nfcexplorer.domain.sync.SyncReport
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one sync: reconcile assignments, then upload this session's log.
 *
 * Orchestration only. The decision that can actually corrupt someone's data — which copy of a tag
 * wins — is [SyncMerge], which is pure and swept by tests; everything here is reading, writing and
 * reporting what happened.
 *
 * Assignments and logs are treated completely differently on purpose. Assignments are mutable and
 * shared, so they need a merge. Logs belong to one device, so each device owns its own documents and
 * no two can ever contend for one.
 *
 * Which logs are worth uploading at all is [LogRetention]'s decision: what the user is shown is kept,
 * and the rest rotates.
 */
@Singleton
class CloudSyncService @Inject constructor(
    private val repository: TagActionRepository,
    private val logger: SessionLogger,
    private val activityLog: ActivityLogStore,
    private val cloud: CloudStore,
    private val deviceId: SyncDeviceId,
    private val syncState: SyncState,
) : CloudSync {

    override suspend fun sync(nowMillis: Long): Result<SyncReport> = runSync(nowMillis)
        .onFailure { failure ->
            // The first thing a sync does is read the remote document, and until now a failure
            // there produced no log line at all: the run threw before reaching the first one. A
            // sync that fails silently is indistinguishable on the log from a sync never started,
            // and those need completely different fixes.
            logger.error(
                category = CATEGORY,
                message = "sync failed",
                payload = mapOf(
                    "exception" to (failure::class.simpleName ?: "Throwable"),
                    "message" to (failure.message ?: ""),
                ),
            )
        }

    private suspend fun runSync(nowMillis: Long): Result<SyncReport> = runCatching {
        // Raw, so tombstones are included: a deletion that sync cannot see cannot propagate.
        val local = repository.snapshotForSync()

        val remoteDocument = cloud.read(CloudStore.ACTIONS_DOCUMENT).getOrThrow()
        val remote = TagActionSerializer.decode(remoteDocument)

        val merged = SyncMerge.merge(local = local, cloud = remote)

        // Logged because a sync that reports "nothing to do" is indistinguishable from a sync that
        // read nothing at all, and the two need completely different fixes.
        logger.info(
            category = CATEGORY,
            message = "merged assignments",
            payload = mapOf(
                "local" to local.size.toString(),
                "remoteDocument" to if (remoteDocument == null) "absent" else "${remoteDocument.length} chars",
                "remote" to remote.size.toString(),
                "pull" to merged.fromCloud.size.toString(),
                "push" to merged.fromLocal.size.toString(),
            ),
        )

        // Only the ones the cloud was ahead on need writing locally; saving the rest would churn
        // the store and, worse, bump timestamps that the next merge depends on.
        merged.fromCloud.forEach { repository.save(it) }

        // Push whenever this device holds anything the cloud lacks or is behind on. An unchanged
        // sync writes nothing at all, which keeps a repeated tap on "Sync now" free.
        if (merged.fromLocal.isNotEmpty() || remoteDocument == null) {
            cloud.write(
                CloudStore.ACTIONS_DOCUMENT,
                TagActionSerializer.encode(merged.merged),
            ).getOrThrow()
        }

        val logsUploaded = uploadLogs()
        pruneLegacyLogs()

        // Stamped only here, at the end of a run that threw nothing: a half-finished sync must not
        // be able to claim the data is current.
        syncState.recordSuccess(nowMillis)

        SyncReport(
            pulled = merged.fromCloud.size,
            pushed = merged.fromLocal.size,
            logsUploaded = logsUploaded,
        )
    }

    /**
     * Uploads this device's logs, in two documents with two different lifetimes.
     *
     * The kept history is the whole persisted store, rewritten in place -- so a phone that is wiped
     * and set up again finds its taps and scans still in the folder. The diagnostics are only this
     * session's, overwritten each time the app runs, so the previous session's are dumped rather
     * than piling up: they explain a failure while it is happening and nothing reads them later.
     *
     * Both are one document per device. Rewriting in place is what stops the folder growing without
     * bound, which is exactly what the earlier one-file-per-session scheme did.
     */
    private suspend fun uploadLogs(): Int {
        var uploaded = 0

        val retained = activityLog.entries.value
        if (retained.isNotEmpty()) {
            cloud.write(
                LogRetention.activityDocument(deviceId.value),
                encode(retained),
            ).getOrThrow()
            uploaded++
        }

        val diagnostics = logger.entries.value.filterNot { LogRetention.retains(it.category) }
        if (diagnostics.isNotEmpty()) {
            cloud.write(
                LogRetention.diagnosticDocument(deviceId.value),
                encode(diagnostics),
            ).getOrThrow()
            uploaded++
        }

        return uploaded
    }

    /**
     * Removes the per-session log documents left by earlier versions.
     *
     * Failures are logged and swallowed rather than failing the sync. By the time this runs the
     * assignments are already reconciled and the logs already uploaded -- reporting that as a
     * failed sync because some old file could not be tidied would be a lie about the data.
     */
    private suspend fun pruneLegacyLogs() {
        val present = cloud.list(LogRetention.LEGACY_PREFIX)
            .getOrElse { failure ->
                logger.warn(CATEGORY, "could not list old logs", mapOf("error" to describe(failure)))
                return
            }

        val stale = LogRetention.stale(present)
        if (stale.isEmpty()) return

        var removed = 0
        stale.forEach { name ->
            cloud.delete(name)
                .onSuccess { removed++ }
                .onFailure { failure ->
                    logger.warn(CATEGORY, "could not remove old log", mapOf("name" to name, "error" to describe(failure)))
                }
        }
        logger.info(CATEGORY, "removed old logs", mapOf("count" to removed.toString()))
    }

    private fun describe(failure: Throwable): String = failure.message ?: failure::class.java.simpleName

    private fun encode(entries: List<LogEntry>): String = json.encodeToString(
        ListSerializer(LogEntryDto.serializer()),
        entries.map(::toDto),
    )

    private fun toDto(entry: LogEntry) = LogEntryDto(
        sequence = entry.sequence,
        timestampMillis = entry.timestampMillis,
        level = entry.level.name,
        category = entry.category,
        message = entry.message,
        payload = entry.payload,
    )

    @Serializable
    private data class LogEntryDto(
        @SerialName("sequence") val sequence: Long,
        @SerialName("timestampMillis") val timestampMillis: Long,
        @SerialName("level") val level: String,
        @SerialName("category") val category: String,
        @SerialName("message") val message: String,
        @SerialName("payload") val payload: Map<String, String>,
    )

    private companion object {
        const val CATEGORY = "sync"
        val json = Json { encodeDefaults = true }
    }
}
