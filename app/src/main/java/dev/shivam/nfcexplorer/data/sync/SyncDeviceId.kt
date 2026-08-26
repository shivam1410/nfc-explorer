package dev.shivam.nfcexplorer.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A stable per-install identifier, plus when this session began.
 *
 * Used only to name log files so two devices writing to the same Drive folder cannot collide. It is
 * a random UUID generated on first use rather than anything derived from the hardware: nothing here
 * needs to identify the *device*, only to be different from the other one, and an advertising or
 * hardware id would be gratuitous data collection for a filename.
 */
@Singleton
class SyncDeviceId @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("nfc-explorer-sync", Context.MODE_PRIVATE)

    val value: String = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().take(8).also {
        prefs.edit().putString(KEY, it).apply()
    }

    /** Fixed for the life of the process, so repeated syncs overwrite one log rather than pile up. */
    val sessionStartedAt: Long = System.currentTimeMillis()

    private companion object {
        const val KEY = "deviceId"
    }
}
