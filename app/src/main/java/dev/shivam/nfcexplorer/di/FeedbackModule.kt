package dev.shivam.nfcexplorer.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.shivam.nfcexplorer.data.feedback.PreferencesFeedbackSettings
import dev.shivam.nfcexplorer.domain.feedback.FeedbackSettings
import javax.inject.Singleton

/**
 * Tap feedback bindings.
 *
 * Its own module rather than more lines in `ActionBindingsModule`, which is already at the
 * `TooManyFunctions` threshold. A module per concern also means the trigger's dependencies and the
 * settings screen's stay legible.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {

    @Binds
    @Singleton
    abstract fun bindFeedbackSettings(impl: PreferencesFeedbackSettings): FeedbackSettings
}
