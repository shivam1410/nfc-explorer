package dev.shivam.nfcexplorer.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which device this is, for naming the documents it owns in the shared folder.
 *
 * An interface for the same reason [SyncState] is one: the implementation needs a `Context`, and
 * without a seam the sync built on top of it cannot be tested off a phone.
 */
interface SyncDeviceId {
    val value: String
}

/**
 * A stable per-install identifier.
 *
 * Used only to name log documents so two devices writing to the same Drive folder cannot collide. It
 * is a random UUID generated on first use rather than anything derived from the hardware: nothing
 * here needs to identify the *device*, only to be different from the other one, and an advertising
 * or hardware id would be gratuitous data collection for a filename.
 *
 * Per *install*, not per device, and restore depends on that. An uninstall takes this with it, so a
 * reinstalled app no longer recognises the document it used to own -- which is exactly what lets it
 * pick that document up as somebody else's history and take its own taps back.
 */
@Singleton
class PreferencesSyncDeviceId @Inject constructor(
    @ApplicationContext context: Context,
) : SyncDeviceId {

    private val prefs = context.getSharedPreferences("nfc-explorer-sync", Context.MODE_PRIVATE)

    override val value: String =
        prefs.getString(KEY, null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString(KEY, it).apply()
        }

    private companion object {
        const val KEY = "deviceId"
    }
}
