package dev.shivam.nfcexplorer.data.toggl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.toggl.TogglConfig
import javax.inject.Inject
import javax.inject.Singleton

/** The workspace id, in ordinary preferences. */
@Singleton
class PreferencesTogglConfig @Inject constructor(
    @ApplicationContext context: Context,
) : TogglConfig {

    private val prefs = context.getSharedPreferences("nfc-explorer-toggl", Context.MODE_PRIVATE)

    override fun workspaceId(): Long? = prefs.getLong(KEY, 0L).takeIf { it > 0 }

    override fun setWorkspaceId(id: Long?) {
        prefs.edit().apply {
            if (id == null || id <= 0) remove(KEY) else putLong(KEY, id)
        }.apply()
    }

    private companion object {
        const val KEY = "workspaceId"
    }
}
