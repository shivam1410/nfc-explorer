package dev.shivam.nfcexplorer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shivam.nfcexplorer.data.log.ActivityLogStore
import dev.shivam.nfcexplorer.data.sync.AccessTokens
import dev.shivam.nfcexplorer.data.sync.CloudSyncService
import dev.shivam.nfcexplorer.data.sync.DriveAppDataStore
import dev.shivam.nfcexplorer.data.sync.GoogleAccessTokens
import dev.shivam.nfcexplorer.data.sync.PreferencesSyncDeviceId
import dev.shivam.nfcexplorer.data.sync.PreferencesSyncState
import dev.shivam.nfcexplorer.data.sync.SyncDeviceId
import dev.shivam.nfcexplorer.data.sync.SyncState
import dev.shivam.nfcexplorer.data.toggl.PreferencesTogglConfig
import dev.shivam.nfcexplorer.data.toggl.TogglHttpSession
import dev.shivam.nfcexplorer.domain.log.ActivityLog
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import dev.shivam.nfcexplorer.domain.sync.CloudSync
import dev.shivam.nfcexplorer.domain.toggl.TogglConfig
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import javax.inject.Singleton

/**
 * Everything that talks to something outside the phone: Drive, Toggl, and the history they carry.
 *
 * Split out of `ActionBindingsModule` when that class passed the `TooManyFunctions` threshold for
 * the second time. Raising the threshold again would have been the third time in a row a rule was
 * moved rather than the code — and the seam was obvious once looked for, because none of these
 * bindings has anything to do with what a tag does when tapped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

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
}
