package dev.shivam.nfcexplorer.data.sync

import dev.shivam.nfcexplorer.data.action.TagActionSerializer
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.sync.SyncMerge
import dev.shivam.nfcexplorer.domain.sync.SyncReport
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.first
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
 * shared, so they need a merge. Logs are append-only and belong to one session on one device, so each
 * becomes its own file and no two devices can ever contend for it.
 */
@Singleton
class CloudSyncService @Inject constructor(
    private val repository: TagActionRepository,
    private val logger: SessionLogger,
    private val cloud: CloudStore,
    private val deviceId: SyncDeviceId,
) : CloudSync {

    override suspend fun sync(nowMillis: Long): Result<SyncReport> = runCatching {
        val local = repository.observeAll().first()

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

        val logsUploaded = uploadSessionLog(nowMillis)

        SyncReport(
            pulled = merged.fromCloud.size,
            pushed = merged.fromLocal.size,
            logsUploaded = logsUploaded,
        )
    }

    /**
     * Writes this session's log under a name unique to the device and session.
     *
     * Overwrites the same file for the life of the session rather than appending a new one per sync,
     * so syncing twice in a session does not leave two partial copies of the same log.
     */
    private suspend fun uploadSessionLog(nowMillis: Long): Int {
        val entries = logger.entries.value
        if (entries.isEmpty()) return 0

        val name = "${CloudStore.LOG_PREFIX}${deviceId.value}-${deviceId.sessionStartedAt}.json"
        val payload = json.encodeToString(
            ListSerializer(LogEntryDto.serializer()),
            entries.map(::toDto),
        )
        cloud.write(name, payload).getOrThrow()
        return 1
    }

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
