package dev.shivam.nfcexplorer.di

import dev.shivam.nfcexplorer.data.action.AssignmentDocumentStore
import dev.shivam.nfcexplorer.data.action.DataStoreAssignmentDocuments
import dev.shivam.nfcexplorer.data.action.InstalledAppCatalog
import dev.shivam.nfcexplorer.data.action.TagActionRunner
import dev.shivam.nfcexplorer.data.action.TagActionStore
import dev.shivam.nfcexplorer.data.system.ActiveNotificationProbe
import dev.shivam.nfcexplorer.data.system.AndroidSystemGrants
import dev.shivam.nfcexplorer.data.secret.KeystoreSecretStore
import dev.shivam.nfcexplorer.data.sync.AccessTokens
import dev.shivam.nfcexplorer.data.sync.CloudSyncService
import dev.shivam.nfcexplorer.data.sync.DriveAppDataStore
import dev.shivam.nfcexplorer.data.log.ActivityLogStore
import dev.shivam.nfcexplorer.data.sync.PreferencesSyncDeviceId
import dev.shivam.nfcexplorer.data.sync.PreferencesSyncState
import dev.shivam.nfcexplorer.data.sync.SyncDeviceId
import dev.shivam.nfcexplorer.data.sync.SyncState
import dev.shivam.nfcexplorer.data.sync.GoogleAccessTokens
import dev.shivam.nfcexplorer.data.toggl.PreferencesTogglConfig
import dev.shivam.nfcexplorer.data.toggl.TogglHttpSession
import dev.shivam.nfcexplorer.data.update.ApkInstaller
import dev.shivam.nfcexplorer.data.update.GitHubReleaseSource
import dev.shivam.nfcexplorer.data.update.UpdateInstaller
import dev.shivam.nfcexplorer.data.update.PackageInstalledVersion
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.NotificationProbe
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import dev.shivam.nfcexplorer.domain.secret.SecretStore
import dev.shivam.nfcexplorer.domain.log.ActivityLog
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.toggl.TogglConfig
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.logging.SessionLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ActionBindingsModule {

    @Binds
    abstract fun bindAssignmentDocuments(
        impl: DataStoreAssignmentDocuments,
    ): AssignmentDocumentStore

    @Binds
    abstract fun bindActionPerformer(impl: TagActionRunner): ActionPerformer

    @Binds
    abstract fun bindAppCatalog(impl: InstalledAppCatalog): AppCatalog

    @Binds
    abstract fun bindNotificationProbe(impl: ActiveNotificationProbe): NotificationProbe

    @Binds
    abstract fun bindSystemGrants(impl: AndroidSystemGrants): SystemGrants

    @Binds
    abstract fun bindReleaseSource(impl: GitHubReleaseSource): ReleaseSource

    @Binds
    abstract fun bindUpdateInstaller(impl: ApkInstaller): UpdateInstaller

    @Binds
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun bindCloudStore(impl: DriveAppDataStore): CloudStore

    @Binds
    abstract fun bindCloudSync(impl: CloudSyncService): CloudSync

    @Binds
    abstract fun bindSyncState(impl: PreferencesSyncState): SyncState

    @Binds
    @Singleton
    abstract fun bindSyncDeviceId(impl: PreferencesSyncDeviceId): SyncDeviceId

    @Binds
    @Singleton
    abstract fun bindActivityLog(impl: ActivityLogStore): ActivityLog

    @Binds
    abstract fun bindAccessTokens(impl: GoogleAccessTokens): AccessTokens

    @Binds
    abstract fun bindTogglSession(impl: TogglHttpSession): TogglSession

    @Binds
    abstract fun bindTogglConfig(impl: PreferencesTogglConfig): TogglConfig

    @Binds
    abstract fun bindInstalledVersion(impl: PackageInstalledVersion): InstalledVersion
}

@Module
@InstallIn(SingletonComponent::class)
object ActionProvidersModule {

    @Provides
    @Singleton
    fun provideTagActionRepository(
        documents: AssignmentDocumentStore,
        logger: SessionLogger,
    ): TagActionRepository = TagActionStore(documents, logger)
}
