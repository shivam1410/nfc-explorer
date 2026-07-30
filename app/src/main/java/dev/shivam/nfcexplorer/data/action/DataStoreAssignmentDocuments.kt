package dev.shivam.nfcexplorer.data.action

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.assignmentDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tag_actions",
)

/**
 * File-backed [AssignmentDocumentStore], in the app's private storage.
 *
 * Deliberately thin: everything worth testing sits in [TagActionStore] above this seam, since
 * `DataStore` cannot run in a JVM unit test. Verified on device instead.
 *
 * An [IOException] from `DataStore` becomes null rather than propagating. A tap must never crash
 * because a file was momentarily unreadable, and the layer above treats null identically to "nothing
 * assigned".
 */
@Singleton
class DataStoreAssignmentDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: SessionLogger,
) : AssignmentDocumentStore {

    override fun observe(): Flow<String?> = context.assignmentDataStore.data
        .catch { failure ->
            logRead(failure)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { preferences -> preferences[DOCUMENT] }

    override suspend fun read(): String? = try {
        context.assignmentDataStore.data.first()[DOCUMENT]
    } catch (failure: IOException) {
        logRead(failure)
        null
    }

    override suspend fun write(document: String) {
        context.assignmentDataStore.edit { preferences -> preferences[DOCUMENT] = document }
    }

    private fun logRead(failure: Throwable) {
        logger.warn(
            category = CATEGORY,
            message = "could not read tag action store; treating as empty",
            payload = mapOf("exception" to (failure::class.simpleName ?: "Throwable")),
        )
    }

    private companion object {
        const val CATEGORY = "actions"
        val DOCUMENT = stringPreferencesKey("assignments")
    }
}
