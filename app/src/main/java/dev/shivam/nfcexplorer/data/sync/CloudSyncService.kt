package dev.shivam.nfcexplorer.data.sync

import dev.shivam.nfcexplorer.data.action.TagActionSerializer
import dev.shivam.nfcexplorer.data.log.ActivityLogSerializer
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

        val logsRestored = restoreLogs()
        val logsUploaded = uploadLogs()
        pruneRetiredLogs()

        // Stamped only here, at the end of a run that threw nothing: a half-finished sync must not
        // be able to claim the data is current.
        syncState.recordSuccess(nowMillis)

        SyncReport(
            pulled = merged.fromCloud.size,
            pushed = merged.fromLocal.size,
            logsUploaded = logsUploaded,
            logsRestored = logsRestored,
        )
    }

    /**
     * Takes back any kept history in the folder that this device does not already hold.
     *
     * The point of the whole arrangement: uninstalling takes the phone's log with it but leaves the
     * document, and a fresh install is issued a new device id, so its own former history reads as
     * another device's and is recovered here.
     *
     * Every activity document is read, not only this device's. Two phones on one account therefore
     * converge on one history, which is the same behaviour assignments already have and the only
     * rule that also restores a wiped phone -- a phone that has forgotten its own id cannot pick
     * its own document out of the folder.
     *
     * A document that cannot be read is logged and skipped rather than failing the sync. One
     * unreadable file is a poor reason to abandon the others, or the assignments already merged.
     */
    private suspend fun restoreLogs(): Int {
        val documents = cloud.list(LogRetention.ACTIVITY_PREFIX)
            .getOrElse { failure ->
                logger.warn(CATEGORY, "could not list kept logs", mapOf("error" to describe(failure)))
                return 0
            }

        val recovered = documents.flatMap { name ->
            val body = cloud.read(name)
                .getOrElse { failure ->
                    logger.warn(
                        CATEGORY,
                        "could not read kept log",
                        mapOf("document" to name, "error" to describe(failure)),
                    )
                    return@flatMap emptyList()
                } ?: return@flatMap emptyList()

            runCatching { ActivityLogSerializer.decode(body) }
                .getOrElse { failure ->
                    logger.warn(
                        CATEGORY,
                        "could not decode kept log",
                        mapOf("document" to name, "error" to describe(failure)),
                    )
                    emptyList()
                }
        }

        val added = activityLog.restore(recovered)

        // Logged on every run, including the usual one where nothing is new. A step that reports
        // only when it finds something is indistinguishable from a step that never ran, which is
        // precisely the hole that made the last three syncs impossible to verify.
        logger.info(
            CATEGORY,
            "read kept logs",
            mapOf(
                "documents" to documents.size.toString(),
                "found" to recovered.size.toString(),
                "new" to added.toString(),
            ),
        )
        return added
    }

    /**
     * Uploads this device's taps.
     *
     * One document per device, rewritten in place, so a phone that is wiped and set up again finds
     * its history still in the folder and the folder never grows.
     *
     * Only the taps. Scan detail is kept on the phone, where it explains a card that behaved oddly
     * while you still have that card; a copy in the cloud answers a question nobody asked. The sync
     * and export chatter that briefly went up beside it is not written at all any more, and the
     * documents holding it are removed by [pruneRetiredLogs].
     */
    private suspend fun uploadLogs(): Int {
        val synced = activityLog.entries.value.filter { LogRetention.syncs(it.category) }
        if (synced.isEmpty()) return 0

        val document = LogRetention.activityDocument(deviceId.value)
        cloud.write(document, ActivityLogSerializer.encode(synced)).getOrThrow()

        // Logged because the upload was the one step of a sync that left no trace of itself: the
        // merge said what it reconciled and the prune said what it removed, but what actually went
        // into the user's Drive could only be inferred from the run not having thrown.
        logger.info(
            category = CATEGORY,
            message = "uploaded taps",
            payload = mapOf(
                "document" to document,
                "entries" to synced.size.toString(),
                "held" to activityLog.entries.value.size.toString(),
            ),
        )
        return 1
    }

    /**
     * Removes the log documents of schemes this app no longer writes.
     *
     * Failures are logged and swallowed rather than failing the sync. By the time this runs the
     * assignments are already reconciled and the logs already uploaded -- reporting that as a
     * failed sync because some old file could not be tidied would be a lie about the data.
     */
    private suspend fun pruneRetiredLogs() {
        // Listed unfiltered, because two different prefixes are being retired and the store filters
        // by only one. LogRetention decides what goes; this asks for everything and lets it choose.
        val present = cloud.list("")
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
