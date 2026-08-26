package dev.shivam.nfcexplorer.di

import dev.shivam.nfcexplorer.data.action.AssignmentDocumentStore
import dev.shivam.nfcexplorer.data.action.DataStoreAssignmentDocuments
import dev.shivam.nfcexplorer.data.action.InstalledAppCatalog
import dev.shivam.nfcexplorer.data.action.TagActionRunner
import dev.shivam.nfcexplorer.data.action.TagActionStore
import dev.shivam.nfcexplorer.data.system.ActiveNotificationProbe
import dev.shivam.nfcexplorer.data.system.AndroidSystemGrants
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.NotificationProbe
import dev.shivam.nfcexplorer.domain.action.SystemGrants
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
