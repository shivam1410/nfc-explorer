package dev.shivam.nfcexplorer.ui.scan

import app.cash.turbine.test
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.model.TagPresentation
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.model.WriteOutcome
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.usecase.ReadTagUseCase
import dev.shivam.nfcexplorer.fake.FakeUltralightTransport
import dev.shivam.nfcexplorer.fake.Mf0icu1Fixtures
import dev.shivam.nfcexplorer.logging.SessionLogger
import dev.shivam.nfcexplorer.ui.haptics.ScanFeedback
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private object StubHandle : TagHandle

    /** Hand-written fake, per the project's fakes-over-mocks rule. */
    private class FakeTagRepository : TagRepository {
        var result: Result<TagReport>? = null
        var readCount = 0

        override suspend fun read(handle: TagHandle): Result<TagReport> {
            readCount++
            return requireNotNull(result) { "test did not arrange a result" }
        }

        override suspend fun writePage(
            handle: TagHandle,
            page: Int,
            data: ByteArray,
            expertMode: Boolean,
        ): Result<WriteOutcome> = Result.failure(NotImplementedError("not under test"))
    }

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeTagRepository()

    private fun report(): TagReport {
        val transport = FakeUltralightTransport(Mf0icu1Fixtures.hotelCardLike()).apply { connect() }
        return ReadTagUseCase(SessionLogger { 0L })(
            transport,
            TagPresentation(
                uid = ByteBlock.copyOf(Mf0icu1Fixtures.SAMPLE_UID),
                chip = ChipProfile.MF0ICU1,
            ),
        )
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ScanViewModel(repository)

    // --- Capability gates ---

    @Test
    fun `initial state is starting`() {
        assertEquals(ScanUiState.Starting, viewModel().state.value)
    }

    @Test
    fun `an unsupported device is a terminal state`() {
        val model = viewModel()

        model.onCapabilityResolved(ScanCapability.UNSUPPORTED)

        assertEquals(ScanUiState.Unsupported, model.state.value)
    }

    @Test
    fun `a disabled adapter is distinct from unsupported hardware`() {
        val model = viewModel()

        model.onCapabilityResolved(ScanCapability.DISABLED)

        // Recoverable by the user, so it must not collapse into Unsupported.
        assertEquals(ScanUiState.Disabled, model.state.value)
    }

    @Test
    fun `an available adapter waits for a tag`() {
        val model = viewModel()

        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        assertEquals(ScanUiState.WaitingForTag, model.state.value)
    }

    // --- Read lifecycle ---

    @Test
    fun `discovering a tag moves through reading to captured`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.state.test {
            assertEquals(ScanUiState.WaitingForTag, awaitItem())

            model.onTagDiscovered(StubHandle)
            assertEquals(ScanUiState.Reading, awaitItem())

            val captured = awaitItem()
            assertIs<ScanUiState.Captured>(captured)
            assertEquals(16, captured.report.memory.pages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed read reports the exception without a stack trace`() = runTest(dispatcher) {
        repository.result = Result.failure(TagFieldLostException())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        val state = model.state.value
        assertIs<ScanUiState.Failed>(state)
        assertEquals("TagFieldLostException", state.exceptionName)
        assertEquals("tag left the field", state.message)
    }

    @Test
    fun `a failed re-tap keeps the previous report visible`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)
        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        repository.result = Result.failure(TagFieldLostException())
        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        // The dump the user is still reading must not be blanked by a fumbled second tap.
        assertIs<ScanUiState.Failed>(model.state.value)
        assertEquals(16, model.lastReport.value?.memory?.pages?.size)
    }

    @Test
    fun `re-tapping replaces the report with the newer one`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()
        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(2, repository.readCount)
        assertIs<ScanUiState.Captured>(model.state.value)
    }

    @Test
    fun `no report is retained before the first successful scan`() {
        assertNull(viewModel().lastReport.value)
    }

    // --- Haptics ---

    @Test
    fun `haptic signals fire on detection and on capture`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.onTagDiscovered(StubHandle)
        assertEquals(ScanFeedback.DETECTED, model.hapticSignal.value?.feedback)

        testScheduler.advanceUntilIdle()
        assertEquals(ScanFeedback.CAPTURED, model.hapticSignal.value?.feedback)
    }

    @Test
    fun `a failure produces a reject haptic`() = runTest(dispatcher) {
        repository.result = Result.failure(TagFieldLostException())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(ScanFeedback.FAILED, model.hapticSignal.value?.feedback)
    }

    @Test
    fun `consecutive identical scans produce distinct haptic tokens`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.AVAILABLE)

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()
        val first = model.hapticSignal.value

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()
        val second = model.hapticSignal.value

        // Same feedback value both times; only the token distinguishes them. Without it the
        // second tap would not re-fire LaunchedEffect and would feel dead.
        assertEquals(first?.feedback, second?.feedback)
        assertTrue((second?.token ?: 0) > (first?.token ?: 0), "token must advance")
    }

    // --- Guarding against work while unavailable ---

    @Test
    fun `a tag discovered while NFC is unsupported is ignored`() = runTest(dispatcher) {
        repository.result = Result.success(report())
        val model = viewModel()
        model.onCapabilityResolved(ScanCapability.UNSUPPORTED)

        model.onTagDiscovered(StubHandle)
        testScheduler.advanceUntilIdle()

        assertEquals(0, repository.readCount)
        assertEquals(ScanUiState.Unsupported, model.state.value)
    }
}
