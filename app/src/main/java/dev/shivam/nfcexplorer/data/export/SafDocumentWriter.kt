package dev.shivam.nfcexplorer.data.export

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a document body to a user-chosen location.
 *
 * The Storage Access Framework is used rather than direct file paths, which is why this app requests
 * **no storage permission at all**: the user picks the destination in the system picker and the app
 * receives a grant for that single document. Nothing else on the device is reachable.
 */
@Singleton
class SafDocumentWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Writes [body] as UTF-8 to [uri], returning the number of bytes written.
     *
     * Truncates first: a picker can return an existing document, and without `"wt"` a shorter export
     * would leave the tail of the previous one behind and produce a corrupt file.
     */
    fun write(uri: Uri, body: String): Result<Int> = try {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: return Result.failure(IOException("could not open $uri for writing"))

        stream.use { output ->
            output.write(bytes)
            output.flush()
        }
        Result.success(bytes.size)
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: SecurityException) {
        // The grant can be revoked between picking and writing.
        Result.failure(failure)
    }
}
