package dev.shivam.nfcexplorer.ui.actions

import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.SystemGrantState
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.toggl.TogglAccount
import dev.shivam.nfcexplorer.domain.toggl.TogglOutcome
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * What the editor makes of a draft: the round trip through [ActionDraft] and back, and the warning
 * auto-send owes the user when the permission it depends on is gone.
 *
 * Separate from `TagActionsViewModelTest`, which covers the editor's lifecycle and its lists. These
 * tests need no timing and no recording, so they are driven by inert stubs rather than that file's
 * deliberately slow fakes — and splitting them keeps either class a readable size.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagActionsDraftEditingTest {

    /** Stores nothing and answers nothing: these tests never save. */
    private class InertRepository : TagActionRepository {
        override fun observeAll(): Flow<List<TagAssignment>> = MutableStateFlow(emptyList())
        override fun observeDeleted(): Flow<List<TagAssignment>> = MutableStateFlow(emptyList())
        override suspend fun snapshotForSync(): List<TagAssignment> = emptyList()
        override suspend fun restore(uid: ByteBlock) = Unit
        override suspend fun find(uid: ByteBlock): TagAssignment? = null
        override suspend fun save(assignment: TagAssignment) = Unit
        override suspend fun delete(uid: ByteBlock) = Unit
    }

    private class InertPerformer : ActionPerformer {
        override suspend fun perform(action: TagAction): Result<Unit> = Result.success(Unit)
    }

    private class InertCatalog : AppCatalog {
        override suspend fun launchable(): List<InstalledApp> = emptyList()
    }

    private class InertToggl : TogglSession {
        override suspend fun toggle(description: String, tags: List<String>, projectId: Long?) =
            Result.success<TogglOutcome>(TogglOutcome.Started(description))
        override suspend fun account() = Result.success(TogglAccount("Ada", 1))
        override suspend fun tags(): Result<List<String>> = Result.success(emptyList())
    }

    /** The accessibility grant, flippable, because the point is what happens when it is withdrawn. */
    private class FakeGrants(var gestureService: Boolean = true) : SystemGrants {
        override fun current() = SystemGrantState(gestureService = gestureService)
    }

    private val dispatcher = StandardTestDispatcher()
    private val grants = FakeGrants()
    private val uid = ByteBlock.ofInts(0x04, 0x1C, 0x4E, 0x52, 0xCE, 0x7C, 0x80)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = TagActionsViewModel(
        InertRepository(),
        InertPerformer(),
        InertCatalog(),
        InertToggl(),
        grants,
    )

    /** The open draft, with overrides, mirroring how the editor mutates it field by field. */
    private fun TagActionsViewModel.edit(change: ActionDraft.() -> ActionDraft) {
        onDraftChange(requireNotNull(state.value.draft) { "no draft open" }.change())
    }

    private fun TagActionsViewModel.action(): TagAction? =
        draftAction(requireNotNull(state.value.draft) { "no draft open" })

    private fun ActionDraft.asSendIntent(vararg extras: ExtraField) = copy(
        label = "Desk",
        type = ActionType.SEND_INTENT,
        intentAction = "com.example.DO_THING",
        extras = extras.toList(),
    )

    // --- Intent extras round-trip ---

    @Test
    fun `extras survive being opened in the editor and saved again`() = runTest(dispatcher) {
        // The bug this covers: toDraft never read extras back and draftAction rebuilt SendIntent with
        // the default empty map, so opening a stored action and saving it erased them silently.
        val model = viewModel()

        model.onEdit(
            TagAssignment(
                uid = uid,
                label = "Desk",
                action = TagAction.SendIntent(
                    action = "com.example.DO_THING",
                    extras = mapOf("source" to "nfc", "id" to "42"),
                ),
            ),
        )

        assertEquals(
            mapOf("source" to "nfc", "id" to "42"),
            (model.action() as TagAction.SendIntent).extras,
        )
    }

    @Test
    fun `extras typed into the editor reach the action`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.edit { asSendIntent() }

        model.onAddExtra()
        model.onExtraChange(0, ExtraField("source", "nfc"))

        assertEquals(mapOf("source" to "nfc"), (model.action() as TagAction.SendIntent).extras)
        assertNull(model.state.value.problem)
    }

    @Test
    fun `a row left completely empty is dropped rather than refused`() = runTest(dispatcher) {
        // Adding a row and changing your mind is not an error, so it must not block saving.
        val model = viewModel()
        model.onCreateFor(uid)
        model.edit { asSendIntent() }

        model.onAddExtra()

        assertEquals(emptyMap(), (model.action() as TagAction.SendIntent).extras)
        assertNull(model.state.value.problem)
    }

    @Test
    fun `a value with no key is reported rather than silently dropped`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit { asSendIntent(ExtraField("", "orphaned")) }

        assertEquals(DraftProblem.BLANK_EXTRA_KEY, model.state.value.problem)
        assertFalse(model.state.value.canSave)
    }

    @Test
    fun `two extras sharing a key are reported`() = runTest(dispatcher) {
        // associate would keep the last silently, losing a row the user can see on screen.
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit { asSendIntent(ExtraField("k", "one"), ExtraField("k", "two")) }

        assertEquals(DraftProblem.DUPLICATE_EXTRA_KEY, model.state.value.problem)
    }

    @Test
    fun `a key is trimmed before it becomes an extra`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit { asSendIntent(ExtraField("  source  ", "nfc")) }

        assertEquals(mapOf("source" to "nfc"), (model.action() as TagAction.SendIntent).extras)
    }

    @Test
    fun `removing a row leaves the others in order`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)
        model.edit {
            asSendIntent(ExtraField("a", "1"), ExtraField("b", "2"), ExtraField("c", "3"))
        }

        model.onRemoveExtra(1)

        assertEquals(listOf("a", "c"), model.state.value.draft?.extras?.map { it.key })
    }

    // --- Links are encoded on the way out ---

    @Test
    fun `a link is percent-encoded when the draft becomes an action`() = runTest(dispatcher) {
        // Typed with real spaces, as anyone would; unencoded it truncates on the way out.
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit {
            copy(
                label = "Chat",
                type = ActionType.OPEN_URI,
                uri = "https://wa.me/91?text=see you at 5",
            )
        }

        assertEquals(
            "https://wa.me/91?text=see%20you%20at%205",
            (model.action() as TagAction.OpenUri).uri,
        )
    }

    @Test
    fun `a link that is already encoded is left alone`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit {
            copy(
                label = "Chat",
                type = ActionType.OPEN_URI,
                uri = "https://wa.me/91?text=Hi%20there",
            )
        }

        assertEquals("https://wa.me/91?text=Hi%20there", (model.action() as TagAction.OpenUri).uri)
    }

    @Test
    fun `a send intent's optional uri is encoded too`() = runTest(dispatcher) {
        val model = viewModel()
        model.onCreateFor(uid)

        model.edit { asSendIntent().copy(uri = "myapp://open?q=two words") }

        assertEquals(
            "myapp://open?q=two%20words",
            (model.action() as TagAction.SendIntent).uri,
        )
    }

    // --- Auto-send says when it cannot work ---

    private fun ActionDraft.asWhatsApp() = copy(
        label = "Mum",
        type = ActionType.WHATSAPP,
        phoneNumber = "919999900000",
        messageText = "on my way",
    )

    @Test
    fun `turning on auto-send warns when the accessibility grant is missing`() =
        runTest(dispatcher) {
            // Android revokes this on every reinstall, including the app's own in-app update. Without
            // the warning the chat just opens, nothing is sent, and nothing on screen says why.
            grants.gestureService = false
            val model = viewModel()
            model.onCreateFor(uid)
            model.edit { asWhatsApp() }

            model.onAutoSendChange(true)

            assertTrue(model.state.value.autoSendNeedsAccessibility)
        }

    @Test
    fun `auto-send says nothing when the grant is in place`() = runTest(dispatcher) {
        grants.gestureService = true
        val model = viewModel()
        model.onCreateFor(uid)
        model.edit { asWhatsApp() }

        model.onAutoSendChange(true)

        assertFalse(model.state.value.autoSendNeedsAccessibility)
    }

    @Test
    fun `the grant warning is not shown against an action that does not auto-send`() =
        runTest(dispatcher) {
            // Shown against everything it would become noise, and get ignored where it matters.
            grants.gestureService = false
            val model = viewModel()
            model.onCreateFor(uid)
            model.edit { asWhatsApp() }

            model.onAutoSendChange(false)

            assertFalse(model.state.value.autoSendNeedsAccessibility)
        }

    @Test
    fun `opening a stored auto-send assignment re-reads the grant`() = runTest(dispatcher) {
        // The grant can be withdrawn between saving a tag and opening it again, so the editor has to
        // read it on open rather than trust whatever was true last time.
        grants.gestureService = false
        val model = viewModel()

        model.onEdit(
            TagAssignment(
                uid = uid,
                label = "Mum",
                action = TagAction.WhatsAppMessage(
                    phoneNumber = "919999900000",
                    message = "on my way",
                    autoSend = true,
                ),
            ),
        )

        assertTrue(model.state.value.autoSendNeedsAccessibility)
    }
}
