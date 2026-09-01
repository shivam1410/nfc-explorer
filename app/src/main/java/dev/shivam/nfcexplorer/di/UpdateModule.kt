package dev.shivam.nfcexplorer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shivam.nfcexplorer.data.update.ApkInstaller
import dev.shivam.nfcexplorer.data.update.GitHubReleaseSource
import dev.shivam.nfcexplorer.data.update.PackageInstalledVersion
import dev.shivam.nfcexplorer.data.update.UpdateInstaller
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import dev.shivam.nfcexplorer.domain.update.ReleaseSource

/**
 * Finding out a newer build exists, and handing it to the system installer.
 *
 * Three bindings that only the settings screen's update section consumes, kept together so the
 * in-app updater's reach is visible at a glance rather than inferred from a long mixed module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    abstract fun bindReleaseSource(impl: GitHubReleaseSource): ReleaseSource

    @Binds
    abstract fun bindUpdateInstaller(impl: ApkInstaller): UpdateInstaller

    @Binds
    abstract fun bindInstalledVersion(impl: PackageInstalledVersion): InstalledVersion
}
