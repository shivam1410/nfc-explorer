package dev.shivam.nfcexplorer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the dispatcher used for tag I/O.
 *
 * Injected rather than referenced directly so a test can substitute a deterministic dispatcher,
 * and so invariant I5 (no tag I/O on the main thread) is expressed in one place.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Marks the scope for work that must outlive the component that started it.
 *
 * Exists because of a real defect: `TagActionActivity` is `noHistory` and draws nothing, so the
 * instant it fires the intent that raises another app's screen it is destroyed -- taking
 * `lifecycleScope` with it. A single-shot action finished before that mattered, but the two-step
 * action that ends a Sleep Cycle session waits between its steps, and the wait was cancelled every
 * time: `action failed {exception=JobCancellationException}` on a tag that had visibly started a
 * session moments earlier.
 *
 * The process stays alive regardless, because this app hosts a notification listener and an
 * accessibility service, so work handed to this scope genuinely does complete.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * [SupervisorJob] so one failed action cannot cancel the scope and silently kill every later tap.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
