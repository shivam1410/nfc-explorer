package dev.shivam.nfcexplorer.data.feedback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.feedback.FeedbackSettings
import dev.shivam.nfcexplorer.domain.feedback.FeedbackVolume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The four tap-feedback preferences, in ordinary preferences.
 *
 * `SharedPreferences` rather than `DataStore`, unlike the assignment store: these are read on the
 * dispatch path before the action fires, where suspending is not an option, and nothing observes
 * them as a flow. Same shape as [dev.shivam.nfcexplorer.data.toggl.PreferencesTogglConfig].
 *
 * Both tones default to silent. The feature exists because a tap is too loud, so a second sound on
 * by default would be the wrong answer to the question that prompted it.
 */
@Singleton
class PreferencesFeedbackSettings @Inject constructor(
    @ApplicationContext context: Context,
) : FeedbackSettings {

    private val prefs = context.getSharedPreferences("nfc-explorer-feedback", Context.MODE_PRIVATE)

    override fun ranTone(): String? = prefs.getString(KEY_RAN_TONE, null)

    override fun setRanTone(uri: String?) = writeTone(KEY_RAN_TONE, uri)

    override fun failedTone(): String? = prefs.getString(KEY_FAILED_TONE, null)

    override fun setFailedTone(uri: String?) = writeTone(KEY_FAILED_TONE, uri)

    override fun volumePercent(): Int =
        FeedbackVolume.clamp(prefs.getInt(KEY_VOLUME, FeedbackVolume.DEFAULT_PERCENT))

    /**
     * Clamped on the way in, so an out-of-range value never reaches the store at all.
     *
     * The read clamps too, but only as a belt for a preferences file edited by hand or carried
     * forward from a future version. Enforcing the invariant at the write is what keeps every
     * reader from having to remember it.
     */
    override fun setVolumePercent(percent: Int) {
        prefs.edit().putInt(KEY_VOLUME, FeedbackVolume.clamp(percent)).apply()
    }

    override fun toastsEnabled(): Boolean = prefs.getBoolean(KEY_TOASTS, true)

    override fun setToastsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TOASTS, enabled).apply()
    }

    /**
     * Silent removes the key rather than storing an empty string.
     *
     * "" and null must not become two spellings of the same thing: the picker returns null for
     * Silent, and a stored "" would come back as a URI that fails to resolve — silence by accident,
     * logged as a broken tone, indistinguishable from a tone whose file was deleted.
     */
    private fun writeTone(key: String, uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove(key) else putString(key, uri)
        }.apply()
    }

    private companion object {
        const val KEY_RAN_TONE = "ranTone"
        const val KEY_FAILED_TONE = "failedTone"
        const val KEY_VOLUME = "volumePercent"
        const val KEY_TOASTS = "toastsEnabled"
    }
}
