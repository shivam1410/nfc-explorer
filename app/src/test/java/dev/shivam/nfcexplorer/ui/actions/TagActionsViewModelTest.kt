package dev.shivam.nfcexplorer.ui.actions

import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TagActionsViewModelTest {

    private class FakeRepository : TagActionRepository {
        private val stored = MutableStateFlow<List<TagAssignment>>(emptyList())
        var deleted = mutableListOf<String>()

        override fun observeAll(): Flow<List<TagAssignment>> = stored.asStateFlow()
        override suspend fun find(uid: ByteBlock) =
            stored.value.firstOrNull { it.uidKey == TagAssignment.uidKeyOf(uid) }

        override suspend fun save(assignment: TagAssignment) {
            stored.value = stored.value.filterNot { it.uidKey == assignment.uidKey } + assignment
        }

        override suspend fun delete(uid: ByteBlock) {
            deleted += TagAssignment.uidKeyOf(uid)
            stored.value = stored.value.filterNot { it.uidKey == TagAssignment.uidKeyOf(uid) }
        }
    }

    /** Every write fails, the way a full disk does. */
    private class FailingRepository : TagActionRepository {
        override fun observeAll(): Flow<List<TagAssignment>> = MutableStateFlow(emptyList())
        override suspend fun find(uid: ByteBlock): TagAssignment? = null
        override suspend fun save(assignment: TagAssignment): Unit = throw IOException("disk full")
        override suspend fun delete(uid: ByteBlock): Unit = throw IOException("disk full")
    }

    private class RecordingPerformer(private val result: Result<Unit> = Result.success(Unit)) :
        ActionPerformer {
        val performed = mutableListOf<TagAction>()
        override fun perform(action: TagAction): Result<Unit> {
            performed += action
            return result
        }
    }

    private class FakeCatalog(private val apps: List<InstalledApp>) : AppCatalog {
        var queryCount = 0
        override suspend fun launchable(): List<InstalledApp> {
            queryCount++
            return apps
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val performer = RecordingPerformer()
    private val uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val catalog = FakeCatalog(
        listOf(
            InstalledApp("com.google.android.apps.youtube.music", "YouTube Music"),
            InstalledApp("com.toggl.giskard", "Toggl Track"),
        ),
    )

    private fun viewModel() = TagActionsViewModel(repository, performer, catalog)

    // --- Draft lifecycle ---

    @Test
    fun `no editor is open initially`() {
        val model = viewModel()

        assertFalse(model.state.value.isEditing)
        assertFalse(model.state.value.canSave)
    }

    @Test
    fun `creating for a scanned UID opens an editor bound to that tag`() {
        val model = viewModel()

        model.onCreateFor(uid)

        assertEquals(uid, model.state.value.draft?.uid)
        assertFalse(model.state.value.draft?.isExisting == true)
    }

    @Test
    fun `creating with no scanned tag reports that a tag is needed`() {
        // The UID has to come from somewhere, and until a tag is scanned there is nothing to bind to.
        val model = viewModel()

        model.onCreateFor(null)

        assertEquals(DraftProblem.NO_TAG, model.state.value.problem)
        assertFalse(model.state.value.canSave)
    }

    @Test
    fun `cancelling closes the editor without saving`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onCancel()

        assertFalse(model.state.value.isEditing)
        assertNull(repository.find(uid))
    }

    // --- Validation, reusing the domain types rather than duplicating their rules ---

    @Test
    fun `a blank label cannot be saved`() {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onDraftChange(draft(model, label = "  ", packageName = "com.example.app"))

        assertEquals(DraftProblem.BLANK_LABEL, model.state.value.problem)
    }

    @Test
    fun `a launch action with no package cannot be saved`() {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onDraftChange(draft(model, label = "Desk", packageName = ""))

        assertEquals(DraftProblem.MISSING_TARGET, model.state.value.problem)
    }

    @Test
    fun `a URI without a scheme is rejected`() {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onDraftChange(
            draft(model, label = "Music", type = ActionType.OPEN_URI, uri = "music.youtube.com/x"),
        )

        assertEquals(DraftProblem.INVALID_URI, model.state.value.problem)
    }

    @Test
    fun `a valid draft can be saved`() {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onDraftChange(draft(model, label = "Desk", packageName = "com.example.notes"))

        assertNull(model.state.value.problem)
        assertTrue(model.state.value.canSave)
    }

    @Test
    fun `a media draft needs no target and is immediately valid`() {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onDraftChange(
            draft(model, label = "Play", type = ActionType.MEDIA, mediaKey = MediaKey.NEXT),
        )

        assertNull(model.state.value.problem)
    }

    // --- Persisting ---

    @Test
    fun `saving persists the assignment and closes the editor`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Desk", packageName = "com.example.notes"))

        model.onSave()
        testScheduler.advanceUntilIdle()

        assertEquals(TagAction.LaunchApp("com.example.notes"), repository.find(uid)?.action)
        assertEquals("Desk", repository.find(uid)?.label)
        assertFalse(model.state.value.isEditing)
    }

    @Test
    fun `saving an invalid draft does nothing`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "", packageName = ""))

        model.onSave()
        testScheduler.advanceUntilIdle()

        assertNull(repository.find(uid))
        assertTrue(model.state.value.isEditing, "the editor stays open so the problem can be fixed")
    }

    @Test
    fun `editing an existing assignment prefills the draft`() = runTest(dispatcher) {
        val existing = TagAssignment(uid, "Old", TagAction.OpenUri("https://example.com"))
        repository.save(existing)
        val model = viewModel()

        model.onEdit(existing)

        val draft = model.state.value.draft
        assertEquals("Old", draft?.label)
        assertEquals(ActionType.OPEN_URI, draft?.type)
        assertEquals("https://example.com", draft?.uri)
        assertTrue(draft?.isExisting == true)
    }

    @Test
    fun `deleting removes the assignment`() = runTest(dispatcher) {
        repository.save(TagAssignment(uid, "Desk", TagAction.LaunchApp("a.b")))
        val model = viewModel()

        model.onDelete(uid)
        testScheduler.advanceUntilIdle()

        assertNull(repository.find(uid))
        assertEquals(listOf("041c4e52ce7c80"), repository.deleted)
    }

    @Test
    fun `assignments are observed from the repository`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        repository.save(TagAssignment(uid, "Desk", TagAction.LaunchApp("a.b")))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Desk"), model.state.value.assignments.map { it.label })
    }

    // --- Test now ---

    @Test
    fun `test now performs the action without needing a tag`() {
        val model = viewModel()

        model.onTest(TagAction.MediaCommand(MediaKey.NEXT))

        assertEquals(listOf<TagAction>(TagAction.MediaCommand(MediaKey.NEXT)), performer.performed.toList())
    }

    @Test
    fun `a failing test reports a message rather than throwing`() {
        val failing = RecordingPerformer(Result.failure(IllegalStateException("no such app")))
        val model = TagActionsViewModel(repository, failing, catalog)

        model.onTest(TagAction.LaunchApp("com.absent"))

        val message = model.state.value.message
        assertTrue(message?.contains("no such app") == true, "got: $message")
    }

    @Test
    fun `the open draft can be tested before it is saved`() {
        // Trying an action before committing it is the more useful moment: a wrong package name is
        // obvious immediately rather than after a tap that silently does nothing.
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Music", type = ActionType.OPEN_URI, uri = "https://x.test"))

        model.onTestDraft()

        assertEquals(listOf<TagAction>(TagAction.OpenUri("https://x.test")), performer.performed.toList())
    }

    @Test
    fun `testing an invalid draft performs nothing`() {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "", packageName = ""))

        model.onTestDraft()

        assertTrue(performer.performed.isEmpty())
    }

    // --- A message describes one moment, and must not outlive it ---

    @Test
    fun `cancelling clears a message left over from a test run`() = runTest(dispatcher) {
        // "Action performed." still on screen after the editor closes describes something the user has
        // since walked away from, and there is no way to dismiss it.
        val model = viewModel()
        model.onTest(TagAction.MediaCommand(MediaKey.NEXT))

        model.onCancel()

        assertNull(model.state.value.message)
    }

    @Test
    fun `deleting clears a message left over from another assignment`() = runTest(dispatcher) {
        repository.save(TagAssignment(uid, "Desk", TagAction.LaunchApp("a.b")))
        val model = viewModel()
        model.onTest(TagAction.LaunchApp("a.b"))

        model.onDelete(uid)
        testScheduler.advanceUntilIdle()

        assertNull(model.state.value.message)
    }

    @Test
    fun `saving clears a message left over from a test run`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Desk", packageName = "com.example.notes"))
        model.onTestDraft()

        model.onSave()
        testScheduler.advanceUntilIdle()

        assertNull(model.state.value.message)
    }

    @Test
    fun `a save that fails is reported rather than taking the app down`() = runTest(dispatcher) {
        // DataStore writes can fail on a full or unreadable disk. Every other failure in this feature
        // reports itself; an unhandled one here would crash the app on a button press.
        val model = TagActionsViewModel(FailingRepository(), performer, catalog)
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Desk", packageName = "com.example.notes"))

        model.onSave()
        testScheduler.advanceUntilIdle()

        assertTrue(
            model.state.value.message?.contains("disk full") == true,
            "got: ${model.state.value.message}",
        )
        assertTrue(model.state.value.isEditing, "the editor stays open so the save can be retried")
    }

    // --- Choosing an app rather than typing its package name ---

    @Test
    fun `opening the editor offers the installed apps`() = runTest(dispatcher) {
        // A package name is not something anyone knows by heart, so the editor has to offer the list.
        val model = viewModel()

        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("YouTube Music", "Toggl Track"),
            model.state.value.apps.map { it.label },
        )
    }

    @Test
    fun `picking an app fills in its package`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        model.onPickApp(InstalledApp("com.toggl.giskard", "Toggl Track"))

        assertEquals("com.toggl.giskard", model.state.value.draft?.packageName)
    }

    @Test
    fun `picking an app names the assignment when the label is still empty`() = runTest(dispatcher) {
        // Having just told the app which app to open, being asked to type its name again is busywork.
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        model.onPickApp(InstalledApp("com.toggl.giskard", "Toggl Track"))

        assertEquals("Toggl Track", model.state.value.draft?.label)
        assertNull(model.state.value.problem, "a picked app should be immediately saveable")
    }

    @Test
    fun `picking an app leaves a label the user chose alone`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()
        model.onDraftChange(draft(model, label = "Desk card"))

        model.onPickApp(InstalledApp("com.toggl.giskard", "Toggl Track"))

        assertEquals("Desk card", model.state.value.draft?.label)
    }

    @Test
    fun `the app list is filtered by the search query`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        model.onAppQueryChange("toggl")

        assertEquals(listOf("Toggl Track"), model.state.value.visibleApps.map { it.label })
    }

    @Test
    fun `the app list is read once and reused`() = runTest(dispatcher) {
        // Enumerating installed apps hits PackageManager; reopening the editor should not repeat it.
        val model = viewModel()

        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()
        model.onCancel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        assertEquals(1, catalog.queryCount)
    }

    /** Copies the open draft with overrides, mirroring how the editor mutates it field by field. */
    private fun draft(
        model: TagActionsViewModel,
        label: String? = null,
        type: ActionType? = null,
        packageName: String? = null,
        uri: String? = null,
        mediaKey: MediaKey? = null,
    ): ActionDraft {
        val current = requireNotNull(model.state.value.draft) { "no draft open" }
        return current.copy(
            label = label ?: current.label,
            type = type ?: current.type,
            packageName = packageName ?: current.packageName,
            uri = uri ?: current.uri,
            mediaKey = mediaKey ?: current.mediaKey,
        )
    }
}
