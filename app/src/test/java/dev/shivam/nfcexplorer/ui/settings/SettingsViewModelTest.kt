package dev.shivam.nfcexplorer.ui.settings

import dev.shivam.nfcexplorer.data.sync.Authorization
import dev.shivam.nfcexplorer.data.update.UpdateInstaller
import dev.shivam.nfcexplorer.domain.update.InstallStatus
import dev.shivam.nfcexplorer.data.sync.AccessTokens
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.SystemGrantState
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.secret.SecretStore
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.toggl.TogglAccount
import dev.shivam.nfcexplorer.domain.toggl.TogglOutcome
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import dev.shivam.nfcexplorer.domain.sync.SyncReport
import dev.shivam.nfcexplorer.domain.update.AppRelease
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import dev.shivam.nfcexplorer.domain.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

/**
 * The settings surface: permissions, update checks, secrets and sync.
 *
 * These behaviours used to sit on the actions view model and moved here when the editor stopped
 * restating permissions it does not own. The tests moved with them rather than being deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeGrants(var state: SystemGrantState = SystemGrantState()) : SystemGrants {
        override fun current(): SystemGrantState = state
    }

    private class RecordingPerformer : ActionPerformer {
        val performed = mutableListOf<TagAction>()
        override suspend fun perform(action: TagAction): Result<Unit> {
            performed += action
            return Result.success(Unit)
        }
    }

    private class FakeSecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun read(key: String): String? = values[key]
        override fun has(key: String): Boolean = values.containsKey(key)
        override fun write(key: String, value: String) { values[key] = value }
        override fun clear(key: String) { values.remove(key) }
    }

    private val grants = FakeGrants()
    private val performer = RecordingPerformer()
    private val secrets = FakeSecrets()

    /** An installer that never touches the network or the package manager. */
    private class FakeInstaller(
        var allowed: Boolean = true,
        var downloadResult: Result<java.io.File> = Result.success(java.io.File("update.apk")),
    ) : UpdateInstaller {
        var installed: java.io.File? = null
        override fun canInstall() = allowed
        override fun unknownSourcesIntent() = android.content.Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES")
        override suspend fun download(url: String, version: String) = downloadResult
        override fun install(apk: java.io.File): Result<Unit> {
            installed = apk
            return Result.success(Unit)
        }
    }

    private val installer = FakeInstaller()

    /** Answers as Toggl would, without a network. */
    private class FakeToggl(
        var account: Result<TogglAccount> = Result.success(TogglAccount("Ada", 42)),
    ) : TogglSession {
        override suspend fun toggle(description: String, projectId: Long?) =
            Result.success<TogglOutcome>(TogglOutcome.Started(description))
        override suspend fun account(): Result<TogglAccount> = account
    }

    private val toggl = FakeToggl()

    private fun viewModel(
        releases: ReleaseSource = ReleaseSource { Result.success(null) },
        tokens: AccessTokens = AccessTokens { Authorization.Token("t") },
        sync: CloudSync = CloudSync { Result.success(SyncReport(0, 0, 0)) },
        version: String = "0.1.0",
    ) = SettingsViewModel(
        grants = grants,
        performer = performer,
        releases = releases,
        secrets = secrets,
        tokens = tokens,
        cloudSync = sync,
        installedVersion = InstalledVersion { version },
        installer = installer,
        toggl = toggl,
    )

    // --- Permissions ---

    @Test
    fun `grants are read up front and refreshed on demand`() = runTest {
        grants.state = SystemGrantState(notificationAccess = true, gestureService = false)
        val model = viewModel()
        advanceUntilIdle()
        assertFalse(model.state.value.grants.readyForToggle)

        grants.state = SystemGrantState(notificationAccess = true, gestureService = true)
        model.refreshGrants()

        assertTrue(model.state.value.grants.readyForToggle)
    }

    @Test
    fun `opening each settings screen fires its intent`() = runTest {
        val model = viewModel()

        model.onOpenNotificationAccess()
        model.onOpenAccessibilitySettings()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                "android.settings.ACCESSIBILITY_SETTINGS",
            ),
            performer.performed.filterIsInstance<TagAction.SendIntent>().map { it.action },
        )
    }

    // --- Updates ---

    @Test
    fun `a newer release is offered`() = runTest {
        val release = AppRelease("v0.2.0", "0.2.0", "https://example.test", null, true)
        val model = viewModel(releases = { Result.success(release) })

        model.onCheckForUpdates()
        advanceUntilIdle()

        val status = model.state.value.update
        assertTrue(status is UpdateStatus.Available, "got $status")
    }

    @Test
    fun `the same version reports up to date`() = runTest {
        val release = AppRelease("v0.1.0", "0.1.0", "https://example.test", null, false)
        val model = viewModel(releases = { Result.success(release) })

        model.onCheckForUpdates()
        advanceUntilIdle()

        assertTrue(model.state.value.update is UpdateStatus.UpToDate)
    }

    /** A check that never reached GitHub proves nothing, and must not read as "you are current". */
    @Test
    fun `a failed check is reported as failed rather than up to date`() = runTest {
        val model = viewModel(releases = { Result.failure(IllegalStateException("offline")) })

        model.onCheckForUpdates()
        advanceUntilIdle()

        val status = model.state.value.update
        assertTrue(status is UpdateStatus.Failed, "got $status")
        assertTrue(status.reason.contains("offline"))
    }

    // --- Secrets ---

    @Test
    fun `saving a token stores it and forgets the draft`() = runTest {
        val model = viewModel()

        model.onTogglDraftChange("secret-token")
        model.onSaveTogglToken()

        assertEquals("secret-token", secrets.values[SecretStore.TOGGL_TOKEN])
        // The draft is cleared so the value does not linger in observable UI state.
        assertEquals("", model.state.value.togglDraft)
        assertTrue(model.state.value.togglTokenSet)
    }

    /**
     * Clearing the field on save is otherwise indistinguishable from the save failing, so the tail
     * is what tells the user something is actually stored.
     */
    @Test
    fun `a saved token is confirmed by its last few characters`() = runTest {
        val model = viewModel()

        model.onTogglDraftChange("abcdef123456")
        model.onSaveTogglToken()

        assertEquals("3456", model.state.value.togglTokenTail)
    }

    @Test
    fun `the whole token never reaches ui state`() = runTest {
        val model = viewModel()

        model.onTogglDraftChange("abcdef123456")
        model.onSaveTogglToken()

        val state = model.state.value
        assertFalse(state.togglDraft.contains("abcdef"), "the draft must not retain the secret")
        assertFalse(state.togglTokenTail?.contains("abcdef") == true)
    }

    @Test
    fun `an existing token is recognised when the screen opens`() = runTest {
        secrets.values[SecretStore.TOGGL_TOKEN] = "zzzz9999"

        val model = viewModel()
        advanceUntilIdle()

        assertTrue(model.state.value.togglTokenSet)
        assertEquals("9999", model.state.value.togglTokenTail)
    }

    @Test
    fun `clearing forgets the tail as well as the token`() = runTest {
        secrets.values[SecretStore.TOGGL_TOKEN] = "zzzz9999"
        val model = viewModel()

        model.onClearTogglToken()

        assertEquals(null, model.state.value.togglTokenTail)
    }

    /** Revealing is a view concern and must never be remembered. */
    @Test
    fun `token visibility toggles and resets on save`() = runTest {
        val model = viewModel()

        model.onToggleTokenVisibility()
        assertTrue(model.state.value.togglTokenVisible)

        model.onTogglDraftChange("abc")
        model.onSaveTogglToken()
        assertFalse(model.state.value.togglTokenVisible)
    }

    @Test
    fun `clearing removes the stored token`() = runTest {
        secrets.values[SecretStore.TOGGL_TOKEN] = "old"
        val model = viewModel()

        model.onClearTogglToken()

        assertFalse(secrets.values.containsKey(SecretStore.TOGGL_TOKEN))
        assertFalse(model.state.value.togglTokenSet)
    }

    @Test
    fun `a blank token is not saved`() = runTest {
        val model = viewModel()

        model.onTogglDraftChange("   ")
        model.onSaveTogglToken()

        assertFalse(secrets.values.containsKey(SecretStore.TOGGL_TOKEN))
    }


    // --- Installing an update ---

    private val release = AppRelease("v0.3.0", "0.3.0", "https://example.test", "https://example.test/a.apk", true)

    @Test
    fun `a downloaded update is handed to the installer`() = runTest {
        val model = viewModel()

        model.onDownloadAndInstall(release)
        advanceUntilIdle()

        assertEquals(InstallStatus.Handed, model.state.value.install)
        assertEquals("update.apk", installer.installed?.name)
    }

    /** Not a failure: the download worked and one settings toggle stands in the way. */
    @Test
    fun `a missing install permission is reported as needing permission`() = runTest {
        installer.allowed = false
        val model = viewModel()

        model.onDownloadAndInstall(release)
        advanceUntilIdle()

        assertEquals(InstallStatus.NeedsPermission, model.state.value.install)
        assertEquals(null, installer.installed, "nothing should be handed over without permission")
    }

    @Test
    fun `a failed download is reported and installs nothing`() = runTest {
        installer.downloadResult = Result.failure(IllegalStateException("offline"))
        val model = viewModel()

        model.onDownloadAndInstall(release)
        advanceUntilIdle()

        val status = model.state.value.install
        assertTrue(status is InstallStatus.Failed && status.reason.contains("offline"), "got $status")
        assertEquals(null, installer.installed)
    }

    /** A source-only release has nothing to install; saying so beats a button that cannot work. */
    @Test
    fun `a release with no apk is refused before downloading`() = runTest {
        val model = viewModel()

        model.onDownloadAndInstall(release.copy(apkUrl = null))
        advanceUntilIdle()

        assertTrue(model.state.value.install is InstallStatus.Failed)
    }


    /** The workspace is discovered from the token, so the user never has to look it up. */
    @Test
    fun `saving a token immediately verifies it and reports the account`() = runTest {
        val model = viewModel()

        model.onTogglDraftChange("good-token")
        model.onSaveTogglToken()
        advanceUntilIdle()

        val check = model.state.value.togglCheck
        assertTrue(check is TogglCheck.Connected, "got $check")
        assertEquals("Ada", check.name)
        assertEquals(42, check.workspaceId)
    }

    /** A typo must surface now, not as a tag that silently does nothing at bedtime. */
    @Test
    fun `a token Toggl rejects is reported as failed`() = runTest {
        toggl.account = Result.failure(IllegalStateException("HTTP 403"))
        val model = viewModel()

        model.onTogglDraftChange("bad-token")
        model.onSaveTogglToken()
        advanceUntilIdle()

        val check = model.state.value.togglCheck
        assertTrue(check is TogglCheck.Failed && check.reason.contains("403"), "got $check")
    }

    @Test
    fun `checking with no token saved does nothing`() = runTest {
        val model = viewModel()

        model.onCheckToggl()
        advanceUntilIdle()

        assertEquals(TogglCheck.Idle, model.state.value.togglCheck)
    }

    // --- Sync ---

    @Test
    fun `a sync that moves nothing reports quietly`() = runTest {
        val model = viewModel(sync = { Result.success(SyncReport(0, 0, 0)) })

        model.onSyncNow()
        advanceUntilIdle()

        val state = model.state.value.sync
        assertTrue(state is SyncUiState.Done && state.report.quiet, "got $state")
    }

    @Test
    fun `a sync reports what actually moved`() = runTest {
        val model = viewModel(sync = { Result.success(SyncReport(pulled = 2, pushed = 1, logsUploaded = 1)) })

        model.onSyncNow()
        advanceUntilIdle()

        val state = model.state.value.sync
        assertTrue(state is SyncUiState.Done && !state.report.quiet)
        assertEquals(2, state.report.pulled)
    }

    @Test
    fun `a failing sync surfaces the reason`() = runTest {
        val model = viewModel(sync = { Result.failure(IllegalStateException("no network")) })

        model.onSyncNow()
        advanceUntilIdle()

        val state = model.state.value.sync
        assertTrue(state is SyncUiState.Failed && state.reason.contains("no network"), "got $state")
    }

    /** Declining consent must not read as a successful sync. */
    @Test
    fun `declining consent is a failure not a quiet success`() = runTest {
        val model = viewModel()

        model.onConsentResult(granted = false)
        advanceUntilIdle()

        assertTrue(model.state.value.sync is SyncUiState.Failed)
    }

    @Test
    fun `authorization failure is reported without attempting a sync`() = runTest {
        var synced = false
        val model = viewModel(
            tokens = { Authorization.Failed("DEVELOPER_ERROR") },
            sync = { synced = true; Result.success(SyncReport(0, 0, 0)) },
        )

        model.onSyncNow()
        advanceUntilIdle()

        assertTrue(model.state.value.sync is SyncUiState.Failed)
        assertFalse(synced, "a sync must not run without authorization")
    }
}
