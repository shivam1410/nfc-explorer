package dev.shivam.nfcexplorer.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the last sync succeeded.
 *
 * Recorded because "did that work?" is otherwise only answerable by syncing again. The result line
 * disappears with the screen, so without this the answer is lost the moment you navigate away.
 *
 * Only successes are stamped. A failed attempt that moved the time would claim the data is current
 * when it is not, which is exactly the reassurance this must never give falsely.
 */
interface SyncState {
    /** Null when this device has never completed a sync. */
    fun lastSyncedAtMillis(): Long?

    fun recordSuccess(atMillis: Long)
}

@Singleton
class PreferencesSyncState @Inject constructor(
    @ApplicationContext context: Context,
) : SyncState {
    private val prefs = context.getSharedPreferences("nfc-explorer-sync", Context.MODE_PRIVATE)

    override fun lastSyncedAtMillis(): Long? = prefs.getLong(KEY, 0L).takeIf { it > 0 }

    override fun recordSuccess(atMillis: Long) {
        prefs.edit().putLong(KEY, atMillis).apply()
    }

    private companion object {
        const val KEY = "lastSyncedAt"
    }
}
