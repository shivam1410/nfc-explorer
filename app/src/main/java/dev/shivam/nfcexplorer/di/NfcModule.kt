package dev.shivam.nfcexplorer.di

import dev.shivam.nfcexplorer.data.repository.TagRepositoryImpl
import dev.shivam.nfcexplorer.domain.repository.TagRepository
import dev.shivam.nfcexplorer.domain.usecase.ReadTagUseCase
import dev.shivam.nfcexplorer.domain.usecase.WritePageUseCase
import dev.shivam.nfcexplorer.domain.usecase.WritePagesUseCase
import dev.shivam.nfcexplorer.domain.writer.WriteGuard
import dev.shivam.nfcexplorer.logging.SessionLogger
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NfcProvidersModule {

    /**
     * One logger for the whole process.
     *
     * The log spans tag sessions on purpose: re-tapping a card appends to the same log so a
     * sequence of attempts can be compared and exported together.
     */
    @Provides
    @Singleton
    fun provideSessionLogger(): SessionLogger = SessionLogger()

    @Provides
    @Singleton
    fun provideWriteGuard(): WriteGuard = WriteGuard()

    @Provides
    @Singleton
    fun provideReadTagUseCase(logger: SessionLogger): ReadTagUseCase = ReadTagUseCase(logger)

    @Provides
    @Singleton
    fun provideWritePageUseCase(guard: WriteGuard, logger: SessionLogger): WritePageUseCase =
        WritePageUseCase(guard, logger)

    @Provides
    @Singleton
    fun provideWritePagesUseCase(
        writePage: WritePageUseCase,
        logger: SessionLogger,
    ): WritePagesUseCase = WritePagesUseCase(writePage, logger)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NfcBindingsModule {

    @Binds
    abstract fun bindTagRepository(impl: TagRepositoryImpl): TagRepository
}
