package dev.shivam.nfcexplorer.ui.actions

import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.toggl.TogglAccount
import dev.shivam.nfcexplorer.domain.toggl.TogglOutcome
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

        /**
         * Emits only after a delay, because `DataStore` reads a file before it can produce anything.
         *
         * The earlier version returned a `StateFlow` that emitted synchronously on collect. That was
         * more prompt than the real thing, and being more prompt hid a race: anything else in the
         * ViewModel that captured state before suspending could not lose to it.
         */
        override suspend fun snapshotForSync(): List<TagAssignment> = stored.value
        override fun observeDeleted(): Flow<List<TagAssignment>> = MutableStateFlow(emptyList())
        override suspend fun restore(uid: ByteBlock) = Unit

        override fun observeAll(): Flow<List<TagAssignment>> = flow {
            delay(READ_DELAY_MILLIS)
            emitAll(stored.asStateFlow())
        }
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
        override suspend fun snapshotForSync(): List<TagAssignment> = emptyList()
        override fun observeDeleted(): Flow<List<TagAssignment>> = MutableStateFlow(emptyList())
        override suspend fun restore(uid: ByteBlock) = Unit
        override suspend fun find(uid: ByteBlock): TagAssignment? = null
        override suspend fun save(assignment: TagAssignment): Unit = throw IOException("disk full")
        override suspend fun delete(uid: ByteBlock): Unit = throw IOException("disk full")
    }

    private class RecordingPerformer(private val result: Result<Unit> = Result.success(Unit)) :
        ActionPerformer {
        val performed = mutableListOf<TagAction>()
        override suspend fun perform(action: TagAction): Result<Unit> {
            performed += action
            return result
        }
    }

    /** Enumerating installed apps is slow on a real device, so the fake is slow too. */
    private class FakeCatalog(private val apps: List<InstalledApp>) : AppCatalog {
        var queryCount = 0
        override suspend fun launchable(): List<InstalledApp> {
            queryCount++
            delay(CATALOG_DELAY_MILLIS)
            return apps
        }
    }


    // --- Toggl tag picker ---

    @Test
    fun `choosing Toggl loads the workspace tags once`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.TOGGL)
        advanceUntilIdle()
        model.onTypeChange(ActionType.MEDIA)
        model.onTypeChange(ActionType.TOGGL)
        advanceUntilIdle()

        assertEquals(listOf("deep work", "email"), model.state.value.togglTagOptions)
        assertEquals(1, toggl.tagCalls, "the list should be fetched once, not per selection")
    }

    /** The picker is a convenience over a field that still works, so offline must not block saving. */
    @Test
    fun `a failed tag fetch leaves the editor usable`() = runTest {
        toggl.tags = Result.failure(IllegalStateException("offline"))
        val model = viewModel()
        model.onCreateFor(uid)
        // Labelled, so the only thing that could still object is the failed fetch.
        model.onDraftChange(draft(model, label = "Focus", type = ActionType.TOGGL))

        model.onTypeChange(ActionType.TOGGL)
        advanceUntilIdle()

        assertTrue(model.state.value.togglTagOptions.isEmpty())
        assertNull(model.state.value.problem, "an unreachable tag list is not a draft problem")
    }

    @Test
    fun `choosing a tag sets it and choosing the same one clears it`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Focus", type = ActionType.TOGGL))

        model.onSelectTogglTag("deep work")
        assertEquals("deep work", model.state.value.draft?.togglTag)

        // Choosing a different one replaces rather than accumulates: this is a single select.
        model.onSelectTogglTag("email")
        assertEquals("email", model.state.value.draft?.togglTag)

        // Picking the chosen one again is how you get back to no tag at all.
        model.onSelectTogglTag("email")
        assertEquals("", model.state.value.draft?.togglTag)
    }

    @Test
    fun `a typed tag is kept even when the workspace has never seen it`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(
            draft(model, label = "Focus", type = ActionType.TOGGL).copy(togglTag = "brand new"),
        )

        val action = model.draftAction(model.state.value.draft!!) as TagAction.TogglToggle
        assertEquals(listOf("brand new"), action.tags)
    }

    @Test
    fun `no tag chosen means no tags on the entry`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Focus", type = ActionType.TOGGL))

        val action = model.draftAction(model.state.value.draft!!) as TagAction.TogglToggle
        assertTrue(action.tags.isEmpty())
    }


    // --- Suggested labels ---

    @Test
    fun `choosing a type names the tag after it`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.TOGGL)

        assertEquals("Toggl", model.state.value.draft?.label)
    }

    @Test
    fun `switching type renames while the label is still a suggestion`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.TOGGL)
        model.onTypeChange(ActionType.WHATSAPP)

        assertEquals("WhatsApp", model.state.value.draft?.label)
    }

    /** Once it is the user's words, changing type must not overwrite them. */
    @Test
    fun `a typed label survives a change of type`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onTypeChange(ActionType.TOGGL)
        model.onDraftChange(model.state.value.draft!!.copy(label = "Deep work"))

        model.onTypeChange(ActionType.WHATSAPP)

        assertEquals("Deep work", model.state.value.draft?.label)
    }

    /** Suggesting a name is not the user filling the form in, so no errors yet. */
    @Test
    fun `choosing a type does not start showing errors`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.TOGGL)

        assertFalse(model.state.value.draft?.touched == true)
    }


    // --- WhatsApp requires both halves ---

    @Test
    fun `a WhatsApp action needs a message as well as a number`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(
            draft(model, label = "Tell her", type = ActionType.WHATSAPP)
                .copy(phoneNumber = "917982242069"),
        )

        // A chat opened with nothing in it is a tap that did not do the thing it was made for.
        assertEquals(DraftProblem.MISSING_TARGET, model.state.value.problem)

        model.onDraftChange(model.state.value.draft!!.copy(messageText = "on my way"))

        assertNull(model.state.value.problem)
    }

    @Test
    fun `a message without a number is still incomplete`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(
            draft(model, label = "Tell her", type = ActionType.WHATSAPP)
                .copy(messageText = "on my way"),
        )

        assertEquals(DraftProblem.MISSING_TARGET, model.state.value.problem)
    }


    // --- Scanning a second card ---

    /** Reported from a real phone: the page kept describing the first card. */
    @Test
    fun `scanning another card while one is reported as taken switches to it`() = runTest {
        val taken = TagAssignment(uid, "Desk", TagAction.LaunchApp("com.example"))
        repository.save(taken)
        val other = ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81)
        val model = viewModel()
        model.onStartAddFlow()

        model.onTagScanned(uid)
        advanceUntilIdle()
        assertTrue(model.state.value.addTag is AddTagState.AlreadyAssigned)

        model.onTagScanned(other)
        advanceUntilIdle()

        // The second card is unassigned, so the flow moves on to naming it.
        assertNull(model.state.value.addTag)
        assertEquals(other, model.state.value.draft?.uid)
    }

    @Test
    fun `scanning a second assigned card reports that one instead`() = runTest {
        val first = TagAssignment(uid, "Desk", TagAction.LaunchApp("com.example"))
        val secondUid = ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81)
        val second = TagAssignment(secondUid, "Shelf", TagAction.LaunchApp("com.other"))
        repository.save(first)
        repository.save(second)
        val model = viewModel()
        model.onStartAddFlow()

        model.onTagScanned(uid)
        advanceUntilIdle()
        model.onTagScanned(secondUid)
        advanceUntilIdle()

        val state = model.state.value.addTag
        assertTrue(state is AddTagState.AlreadyAssigned)
        assertEquals("Shelf", state.assignment.label)
    }

    /** The guard still earns its place: a stray tap must not discard a half-typed form. */
    @Test
    fun `a tap while editing does not disturb the open draft`() = runTest {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Half typed", type = ActionType.MEDIA))

        model.onTagScanned(ByteBlock.ofInts(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81))
        advanceUntilIdle()

        assertEquals("Half typed", model.state.value.draft?.label)
        assertEquals(uid, model.state.value.draft?.uid)
    }

    private companion object {
        const val READ_DELAY_MILLIS = 10L
        const val CATALOG_DELAY_MILLIS = 200L
    }

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeRepository()
    private val performer = RecordingPerformer()
    /** Answers as Toggl would, without a network. */
    private class FakeToggl(
        var tags: Result<List<String>> = Result.success(listOf("deep work", "email")),
    ) : TogglSession {
        var tagCalls = 0
        override suspend fun toggle(description: String, tags: List<String>, projectId: Long?) =
            Result.success<TogglOutcome>(TogglOutcome.Started(description))
        override suspend fun account() = Result.success(TogglAccount("Ada", 1))
        override suspend fun tags(): Result<List<String>> {
            tagCalls++
            return tags
        }
    }

    private val toggl = FakeToggl()

    private val uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val catalog = FakeCatalog(
        listOf(
            InstalledApp("com.google.android.apps.youtube.music", "YouTube Music"),
            InstalledApp("com.toggl.giskard", "Toggl Track"),
        ),
    )

    private fun viewModel() = TagActionsViewModel(repository, performer, catalog, toggl)

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
    fun `test now performs the action without needing a tag`() = runTest {
        val model = viewModel()

        model.onTest(TagAction.MediaCommand(MediaKey.NEXT))
        // Performing suspends now, so the launched work has to drain before it can be observed.
        advanceUntilIdle()

        assertEquals(listOf<TagAction>(TagAction.MediaCommand(MediaKey.NEXT)), performer.performed.toList())
    }

    @Test
    fun `a failing test reports a message rather than throwing`() = runTest {
        val failing = RecordingPerformer(Result.failure(IllegalStateException("no such app")))
        val model = TagActionsViewModel(repository, failing, catalog, toggl)

        model.onTest(TagAction.LaunchApp("com.absent"))
        advanceUntilIdle()

        val message = model.state.value.message
        assertTrue(message?.contains("no such app") == true, "got: $message")
    }

    @Test
    fun `the open draft can be tested before it is saved`() = runTest {
        // Trying an action before committing it is the more useful moment: a wrong package name is
        // obvious immediately rather than after a tap that silently does nothing.
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, label = "Music", type = ActionType.OPEN_URI, uri = "https://x.test"))

        model.onTestDraft()
        advanceUntilIdle()

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

    // --- A link needs a scheme, so the editor supplies one ---

    @Test
    fun `choosing open link starts the URI with https`() = runTest(dispatcher) {
        // Every link needs a scheme and https is the right default, so requiring it to be typed only
        // creates the "include a scheme" error the user then has to read and fix.
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.OPEN_URI)

        assertEquals("https://", model.state.value.draft?.uri)
    }

    @Test
    fun `choosing open link leaves a URI that is already there alone`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, type = ActionType.OPEN_URI, uri = "myapp://thing"))

        model.onTypeChange(ActionType.OPEN_URI)

        assertEquals("myapp://thing", model.state.value.draft?.uri)
    }

    @Test
    fun `choosing launch app does not invent a URI`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.onTypeChange(ActionType.LAUNCH_APP)

        assertEquals("", model.state.value.draft?.uri)
    }

    @Test
    fun `switching the scheme rewrites it and keeps the rest of the link`() = runTest(dispatcher) {
        // The wa.me lesson: http bounces through a browser redirect that can drop the ?text= payload,
        // and retyping the whole link to change four characters is busywork.
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(
            draft(model, type = ActionType.OPEN_URI, uri = "https://wa.me/91?text=Hi%20there"),
        )

        model.onSchemeChange("http://")

        assertEquals("http://wa.me/91?text=Hi%20there", model.state.value.draft?.uri)
    }

    @Test
    fun `switching the scheme adds one to a link that has none`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.onDraftChange(draft(model, type = ActionType.OPEN_URI, uri = "wa.me/91"))

        model.onSchemeChange("https://")

        assertEquals("https://wa.me/91", model.state.value.draft?.uri)
    }

    // --- The chosen app is named, not spelled out as a package ---

    @Test
    fun `the chosen app is described by its name`() = runTest(dispatcher) {
        // A package name is what gets stored, not what should be read back to the user.
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        model.onPickApp(InstalledApp("com.toggl.giskard", "Toggl Track"))

        assertEquals("Toggl Track", model.state.value.labelFor("com.toggl.giskard"))
    }

    @Test
    fun `loading the app list does not wipe the assignments`() = runTest(dispatcher) {
        // Both arrive asynchronously and both write to the same state. The app list takes far longer
        // to read, so if it captures state before suspending it writes a snapshot from before the
        // assignments existed - and every assignment vanishes from the screen while staying on disk.
        repository.save(TagAssignment(uid, "Desk", TagAction.LaunchApp("a.b")))

        val model = viewModel()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("Desk"), model.state.value.assignments.map { it.label })
        assertEquals(2, model.state.value.apps.size, "the app list should still have loaded")
    }

    @Test
    fun `the app list is ready before any editor is opened`() = runTest(dispatcher) {
        // The assignment cards name the app too, and they are on screen before anything is edited. If
        // the list only loaded with the editor, every card would read as a raw package until the user
        // happened to open one.
        val model = viewModel()

        testScheduler.advanceUntilIdle()

        assertEquals("Toggl Track", model.state.value.labelFor("com.toggl.giskard"))
    }

    @Test
    fun `an app that is no longer installed falls back to its package`() = runTest(dispatcher) {
        // An assignment outlives an uninstall, and showing nothing would be worse than showing the
        // package it still points at.
        val model = viewModel()
        model.onCreateFor(uid)
        testScheduler.advanceUntilIdle()

        assertEquals("com.gone.app", model.state.value.labelFor("com.gone.app"))
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
        val model = TagActionsViewModel(FailingRepository(), performer, catalog, toggl)
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
