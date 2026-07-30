package dev.shivam.nfcexplorer.ui.write

import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.util.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WriteViewModelTest {

    private object StubHandle : TagHandle

    private class FakeTagRepository : TagRepository {
        var writeCount = 0
        var lastStartPage: Int? = null
        var lastPages: List<ByteArray>? = null
        var lastExpertMode: Boolean? = null
        var result: Result<WriteBatchResult> = Result.success(
            WriteBatchResult(startPage = 4, pagesRequested = 0, outcomes = emptyList()),
        )

        override suspend fun read(handle: TagHandle): Result<TagReport> =
            Result.failure(NotImplementedError("not under test"))

        override suspend fun writePages(
            handle: TagHandle,
            startPage: Int,
            pages: List<ByteArray>,
            expertMode: Boolean,
        ): Result<WriteBatchResult> {
            writeCount++
            lastStartPage = startPage
            lastPages = pages
            lastExpertMode = expertMode
            return result
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeTagRepository()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = WriteViewModel(repository)

    // --- Defaults ---

    @Test
    fun `defaults target the user pages with expert mode off`() {
        val state = viewModel().state.value

        assertEquals(4, state.startPage)
        assertEquals(15, state.endPage)
        assertEquals(12, state.pageCount)
        assertEquals(48, state.capacityBytes)
        // Expert mode must never persist across launches; it gates irreversible writes.
        assertFalse(state.expertMode)
        assertFalse(state.isArmed)
    }

    @Test
    fun `the byte preview is available before any edit`() {
        // The preview is the review step that arming confirms. If it only appears after the user
        // happens to touch a control, the screen can be armed with nothing shown.
        assertNotNull(viewModel().encodedPreview.value)
    }

    @Test
    fun `an empty text payload cannot be armed`() {
        // PageEncoder.fromText("", 12) is 12 pages of zeros, so without this an untouched Text
        // field would arm a silent 48-byte wipe. Erasing is what Wipe mode is for, explicitly.
        val model = viewModel()

        assertFalse(model.state.value.canArm, "empty text must not be armable")

        model.onArm()
        assertFalse(model.state.value.isArmed)
    }

    @Test
    fun `an empty hex payload cannot be armed`() {
        val model = viewModel()
        model.onModeChange(WriteMode.HEX)

        assertFalse(model.state.value.canArm)
    }

    @Test
    fun `wipe mode can be armed with an empty input because zeroing is its whole purpose`() {
        val model = viewModel()
        model.onModeChange(WriteMode.WIPE)

        assertTrue(model.state.value.canArm)
    }

    @Test
    fun `typing then clearing the field disarms and blocks arming again`() {
        val model = viewModel()
        model.onInputChange("hi")
        assertTrue(model.state.value.canArm)

        model.onInputChange("")

        assertFalse(model.state.value.canArm)
        assertFalse(model.state.value.isArmed)
    }

    // --- Validation ---

    @Test
    fun `text within capacity is valid`() {
        val model = viewModel()

        model.onInputChange("hello")

        assertNull(model.state.value.problem)
        assertTrue(model.state.value.canArm)
    }

    @Test
    fun `text beyond capacity is reported rather than truncated`() {
        val model = viewModel()
        model.onRangeChange(startPage = 4, endPage = 4) // 4 bytes

        model.onInputChange("ABCDE")

        assertEquals(InputProblem.TOO_LONG, model.state.value.problem)
        assertFalse(model.state.value.canArm)
    }

    @Test
    fun `malformed hex is reported`() {
        val model = viewModel()
        model.onModeChange(WriteMode.HEX)

        model.onInputChange("ZZ")

        assertEquals(InputProblem.MALFORMED_HEX, model.state.value.problem)
        assertFalse(model.state.value.canArm)
    }

    @Test
    fun `switching mode re-validates the existing input`() {
        val model = viewModel()
        model.onRangeChange(4, 4)
        model.onInputChange("ABCDE") // 5 bytes as text: too long

        model.onModeChange(WriteMode.HEX) // as hex: "ABCDE" is an odd digit count

        assertEquals(InputProblem.MALFORMED_HEX, model.state.value.problem)
    }

    @Test
    fun `wipe mode ignores the input entirely`() {
        val model = viewModel()
        model.onInputChange("this is far too long to fit anywhere at all, truly enormous text")

        model.onModeChange(WriteMode.WIPE)

        // A wipe writes zeros regardless of what is typed, so stale text must not block it.
        assertNull(model.state.value.problem)
        assertTrue(model.state.value.canArm)
    }

    // --- Arming ---

    @Test
    fun `arming requires valid input`() {
        val model = viewModel()
        model.onRangeChange(4, 4)
        model.onInputChange("ABCDE")

        model.onArm()

        assertFalse(model.state.value.isArmed)
    }

    @Test
    fun `arming then disarming leaves nothing pending`() {
        val model = viewModel()
        model.onInputChange("hi")

        model.onArm()
        assertTrue(model.state.value.isArmed)

        model.onDisarm()
        assertFalse(model.state.value.isArmed)
    }

    @Test
    fun `changing the payload after arming disarms it`() {
        val model = viewModel()
        model.onInputChange("hi")
        model.onArm()

        model.onInputChange("changed")

        // Otherwise a tap could write something different from what was reviewed when arming.
        assertFalse(model.state.value.isArmed)
    }

    @Test
    fun `changing the range after arming disarms it`() {
        val model = viewModel()
        model.onInputChange("hi")
        model.onArm()

        model.onRangeChange(6, 8)

        assertFalse(model.state.value.isArmed)
    }

    // --- Writing ---

    @Test
    fun `a tag presented while not armed is ignored`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("hi")

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(0, repository.writeCount)
    }

    @Test
    fun `an armed write executes on the next tag and encodes the payload`() = runTest(dispatcher) {
        val model = viewModel()
        model.onRangeChange(4, 5)
        model.onInputChange("Hi")
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.writeCount)
        assertEquals(4, repository.lastStartPage)
        assertEquals("48 69 00 00 | 00 00 00 00", repository.lastPages?.joinToString(" | ") { it.toHex() })
    }

    @Test
    fun `a wipe writes zeros across the whole range`() = runTest(dispatcher) {
        val model = viewModel()
        model.onModeChange(WriteMode.WIPE)
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(12, repository.lastPages?.size)
        assertTrue(repository.lastPages?.all { page -> page.all { it == 0.toByte() } } == true)
    }

    @Test
    fun `expert mode is passed through to the repository`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("hi")
        model.onExpertModeChange(true)
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(true, repository.lastExpertMode)
    }

    @Test
    fun `a completed write disarms so a second tap cannot repeat it silently`() = runTest(dispatcher) {
        val model = viewModel()
        model.onInputChange("hi")
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()
        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.writeCount)
        assertFalse(model.state.value.isArmed)
    }

    @Test
    fun `the batch result is surfaced`() = runTest(dispatcher) {
        repository.result = Result.success(
            WriteBatchResult(startPage = 4, pagesRequested = 1, outcomes = emptyList()),
        )
        val model = viewModel()
        model.onInputChange("hi")
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(4, model.state.value.result?.startPage)
        assertFalse(model.state.value.isWriting)
    }

    @Test
    fun `a failed write session is reported without a stack trace`() = runTest(dispatcher) {
        repository.result = Result.failure(IllegalStateException("tag vanished"))
        val model = viewModel()
        model.onInputChange("hi")
        model.onArm()

        model.onTagPresented(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals("IllegalStateException: tag vanished", model.state.value.failure)
        assertFalse(model.state.value.isWriting)
    }
}
