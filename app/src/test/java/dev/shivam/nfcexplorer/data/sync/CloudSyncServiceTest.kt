package dev.shivam.nfcexplorer.data.sync

import dev.shivam.nfcexplorer.data.log.ActivityLogSerializer
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.log.ActivityLog
import dev.shivam.nfcexplorer.domain.log.LogRetention
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One sync, end to end, against a map instead of Drive.
 *
 * Everything this orchestrates was previously checked by hand on a phone, because two of its
 * dependencies needed an Android `Context`. What that cost is on record: three rounds of "did it
 * work?" answered with "probably, the log does not say", and a filter that claimed technologies
 * nothing could open. These are the decisions the pure helpers cannot make on their own -- which
 * documents get written, what comes back, what is removed, and what is said about it afterwards.
 */
class CloudSyncServiceTest {

    private val uid = ByteBlock.ofInts(0x04, 0x9A, 0x2B, 0x11, 0xC0, 0x4D, 0x80)

    private fun tap(at: Long, message: String, category: String = "action") = LogEntry(
        sequence = 0,
        timestampMillis = at,
        level = LogLevel.INFO,
        category = category,
        message = message,
    )

    private fun scan(at: Long, message: String) = tap(at, message, category = "read")

    private fun service(
        cloud: FakeCloud = FakeCloud(),
        log: FakeActivityLog = FakeActivityLog(),
        repository: FakeAssignments = FakeAssignments(),
        logger: SessionLogger = SessionLogger { 0L },
        syncState: FakeSyncState = FakeSyncState(),
    ) = CloudSyncService(
        repository = repository,
        logger = logger,
        activityLog = log,
        cloud = cloud,
        deviceId = object : SyncDeviceId { override val value = DEVICE },
        syncState = syncState,
    )

    // --- What goes up ---

    @Test
    fun `only taps are uploaded, and to this device's document`() = runTest {
        val cloud = FakeCloud()
        val log = FakeActivityLog(listOf(tap(200, "sent intent"), scan(100, "dump finished")))

        service(cloud = cloud, log = log).sync(NOW).getOrThrow()

        val written = cloud.documents["activity-$DEVICE.json"]
        assertTrue(written != null, "this device's document must be written, got ${cloud.documents.keys}")
        assertEquals(
            listOf("sent intent"),
            ActivityLogSerializer.decode(written).map { it.message },
            "scan detail must stay on the phone",
        )
    }

    @Test
    fun `a history of nothing but scans uploads no document at all`() = runTest {
        val cloud = FakeCloud()
        val log = FakeActivityLog(listOf(scan(100, "dump finished")))

        val report = service(cloud = cloud, log = log).sync(NOW).getOrThrow()

        assertEquals(0, report.logsUploaded)
        assertTrue(cloud.documents.keys.none { it.startsWith("activity-") })
    }

    // --- What comes back ---

    @Test
    fun `a wiped phone takes back the history it no longer recognises`() = runTest {
        // The document it wrote before the reinstall, under the id it has since forgotten.
        val cloud = FakeCloud(
            "activity-beforewipe.json" to ActivityLogSerializer.encode(
                listOf(tap(300, "started a timer", category = "trigger"), tap(200, "sent intent")),
            ),
        )
        val log = FakeActivityLog()

        val report = service(cloud = cloud, log = log).sync(NOW).getOrThrow()

        assertEquals(2, report.logsRestored)
        assertEquals(
            listOf("started a timer", "sent intent"),
            log.entries.value.map { it.message },
        )
    }

    /** Numbers are per device, so matching on them would restore everything on every sync. */
    @Test
    fun `syncing twice restores nothing the second time`() = runTest {
        val cloud = FakeCloud(
            "activity-otherphone.json" to ActivityLogSerializer.encode(listOf(tap(200, "sent intent"))),
        )
        val log = FakeActivityLog()
        val service = service(cloud = cloud, log = log)

        assertEquals(1, service.sync(NOW).getOrThrow().logsRestored)
        assertEquals(0, service.sync(NOW).getOrThrow().logsRestored)
        assertEquals(1, log.entries.value.size)
    }

    @Test
    fun `an unreadable document does not cost the others`() = runTest {
        val cloud = FakeCloud(
            "activity-broken.json" to "{ this is not a log",
            "activity-otherphone.json" to ActivityLogSerializer.encode(listOf(tap(200, "sent intent"))),
        )
        val log = FakeActivityLog()

        val report = service(cloud = cloud, log = log).sync(NOW).getOrThrow()

        assertEquals(1, report.logsRestored, "the readable document must still be taken on")
    }

    // --- What is removed ---

    @Test
    fun `the documents of retired schemes are deleted and current ones are not`() = runTest {
        val cloud = FakeCloud(
            "log-$DEVICE-1700000000000.json" to "[]",
            "diagnostic-$DEVICE.json" to "[]",
            "activity-otherphone.json" to "[]",
            CloudStore.ACTIONS_DOCUMENT to "[]",
        )

        service(cloud = cloud).sync(NOW).getOrThrow()

        assertEquals(
            listOf("diagnostic-$DEVICE.json", "log-$DEVICE-1700000000000.json"),
            cloud.deleted.sorted(),
        )
        assertTrue(
            "activity-otherphone.json" in cloud.documents,
            "another phone's history must survive a sync it did not run",
        )
    }

    @Test
    fun `a sync still counts as done when the tidying fails`() = runTest {
        val cloud = FakeCloud("log-old.json" to "[]").apply { failDeletes = true }
        val syncState = FakeSyncState()

        val report = service(cloud = cloud, syncState = syncState).sync(NOW)

        assertTrue(report.isSuccess, "the assignments were reconciled; the sync did not fail")
        assertEquals(NOW, syncState.recorded)
    }

    // --- What is said about it ---

    @Test
    fun `every step of a sync leaves a record`() = runTest {
        val logger = SessionLogger { 0L }
        val cloud = FakeCloud(
            "activity-otherphone.json" to ActivityLogSerializer.encode(listOf(tap(200, "sent intent"))),
        )

        service(cloud = cloud, log = FakeActivityLog(), logger = logger).sync(NOW).getOrThrow()

        val said = logger.entries.value.filter { it.category == "sync" }.map { it.message }
        assertEquals(
            listOf("merged assignments", "read kept logs", "uploaded taps"),
            said,
            "a step that reports nothing is indistinguishable from a step that never ran",
        )
    }

    @Test
    fun `a sync that fails says why`() = runTest {
        val logger = SessionLogger { 0L }
        val cloud = FakeCloud().apply { readFailure = IllegalStateException("Not signed in") }

        val outcome = service(cloud = cloud, logger = logger).sync(NOW)

        assertTrue(outcome.isFailure)
        val failure = logger.entries.value.single { it.level == LogLevel.ERROR }
        assertEquals("sync failed", failure.message)
        assertEquals("Not signed in", failure.payload["message"])
    }

    /**
     * The read failing is the easy case: it throws before anything else happens. This is the case
     * that caught the test suite out -- the assignments are already reconciled and written when the
     * upload fails, so the run is half-done, and half-done must not read as current.
     */
    @Test
    fun `a sync that fails while uploading does not claim the data is current`() = runTest {
        val syncState = FakeSyncState()
        // Only the log document refuses. The assignments have to get all the way through first,
        // or the run never reaches the step being tested.
        val cloud = FakeCloud().apply {
            writeFailure = IllegalStateException("Drive returned HTTP 503")
            writeFailureFor = LogRetention.ACTIVITY_PREFIX
        }
        val log = FakeActivityLog(listOf(tap(200, "sent intent")))

        val outcome = service(cloud = cloud, log = log, syncState = syncState).sync(NOW)

        assertTrue(outcome.isFailure)
        assertEquals(null, syncState.recorded, "a half-finished sync must not stamp success")
    }

    @Test
    fun `a failed sync does not claim the data is current`() = runTest {
        val syncState = FakeSyncState()
        val cloud = FakeCloud().apply { readFailure = IllegalStateException("Not signed in") }

        service(cloud = cloud, syncState = syncState).sync(NOW)

        assertEquals(null, syncState.recorded, "a half-finished sync must not stamp success")
    }

    // --- Assignments ---

    @Test
    fun `an assignment the cloud has not seen is pushed`() = runTest {
        val cloud = FakeCloud()
        val repository = FakeAssignments(
            listOf(TagAssignment(uid, "Desk", TagAction.LaunchApp("com.example"), updatedAtMillis = 5)),
        )

        val report = service(cloud = cloud, repository = repository).sync(NOW).getOrThrow()

        assertEquals(1, report.pushed)
        assertTrue(CloudStore.ACTIONS_DOCUMENT in cloud.documents)
    }

    // --- Fakes ---

    private class FakeCloud(vararg initial: Pair<String, String>) : CloudStore {
        val documents = linkedMapOf(*initial)
        val deleted = mutableListOf<String>()
        var readFailure: Throwable? = null
        var writeFailure: Throwable? = null

        /** Which document the write should refuse. Null refuses every one. */
        var writeFailureFor: String? = null
        var failDeletes = false

        override suspend fun read(name: String): Result<String?> =
            readFailure?.let { Result.failure(it) } ?: Result.success(documents[name])

        override suspend fun write(name: String, content: String): Result<Unit> {
            writeFailure?.let { failure ->
                if (writeFailureFor == null || name.startsWith(writeFailureFor!!)) {
                    return Result.failure(failure)
                }
            }
            documents[name] = content
            return Result.success(Unit)
        }

        override suspend fun list(prefix: String): Result<List<String>> =
            Result.success(documents.keys.filter { it.startsWith(prefix) })

        override suspend fun delete(name: String): Result<Unit> {
            if (failDeletes) return Result.failure(IllegalStateException("Drive returned HTTP 500"))
            documents.remove(name)
            deleted += name
            return Result.success(Unit)
        }
    }

    private class FakeActivityLog(initial: List<LogEntry> = emptyList()) : ActivityLog {
        private val backing = MutableStateFlow(initial)
        override val entries: StateFlow<List<LogEntry>> = backing.asStateFlow()

        override fun append(newEntries: List<LogEntry>) {
            backing.value = LogRetention.append(backing.value, newEntries)
        }

        override fun restore(recovered: List<LogEntry>): Int {
            val before = backing.value
            backing.value = LogRetention.restore(before, recovered)
            return backing.value.size - before.size
        }

        override fun clear() {
            backing.value = emptyList()
        }
    }

    private class FakeAssignments(
        private val stored: List<TagAssignment> = emptyList(),
    ) : TagActionRepository {
        val saved = mutableListOf<TagAssignment>()

        override fun observeAll(): Flow<List<TagAssignment>> = flowOf(stored)
        override fun observeDeleted(): Flow<List<TagAssignment>> = flowOf(emptyList())
        override suspend fun snapshotForSync(): List<TagAssignment> = stored
        override suspend fun restore(uid: ByteBlock) = Unit
        override suspend fun find(uid: ByteBlock): TagAssignment? = stored.firstOrNull { it.uid == uid }
        override suspend fun save(assignment: TagAssignment) { saved += assignment }
        override suspend fun delete(uid: ByteBlock) = Unit
    }

    private class FakeSyncState : SyncState {
        var recorded: Long? = null
        override fun lastSyncedAtMillis(): Long? = recorded
        override fun recordSuccess(atMillis: Long) { recorded = atMillis }
    }

    private companion object {
        const val DEVICE = "abc12345"
        const val NOW = 1_700_000_000_000L
    }
}
