package dev.shivam.nfcexplorer.di

import dev.shivam.nfcexplorer.data.action.AssignmentDocumentStore
import dev.shivam.nfcexplorer.data.action.DataStoreAssignmentDocuments
import dev.shivam.nfcexplorer.data.action.TagActionStore
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
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
}

@Module
@InstallIn(SingletonComponent::class)
object ActionProvidersModule {

    @Provides
    @Singleton
    fun provideTagActionRepository(documents: AssignmentDocumentStore): TagActionRepository =
        TagActionStore(documents)
}
