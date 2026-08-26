package dev.shivam.nfcexplorer.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Nothing here installs anything. Android shows its own confirmation screen every time, and will
 * refuse outright unless the user has separately allowed this app to install unknown apps — which is
 * the right shape for a self-updater and is why [canInstall] exists rather than a silent failure at
 * the last step.
 *
 * The download lands in `cacheDir/updates`, which is app-private and reclaimable by the system, and
 * is exposed to the installer through a `FileProvider`: a bare `file://` URI has been rejected since
 * API 24.
 */
/**
 * Downloading and installing an update.
 *
 * In `data/` rather than `domain/` because it deals in `File` and `Intent`; the seam exists so the
 * settings view model can be tested without a network or a package installer.
 */
interface UpdateInstaller {
    fun canInstall(): Boolean
    fun unknownSourcesIntent(): Intent
    suspend fun download(url: String, version: String): Result<File>
    fun install(apk: File): Result<Unit>
}

@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : UpdateInstaller {

    /** Whether the user has allowed this app to install packages. */
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The settings screen where that permission is granted, as an intent to perform. */
    override fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * Fetches [url] into the update cache.
     *
     * Downloads to a temporary name and renames on success, so an interrupted transfer can never be
     * mistaken for a complete APK and handed to the installer.
     */
    override suspend fun download(url: String, version: String): Result<File> = withContext(io) {
        runCatching {
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            // One file per version, so repeated checks do not fill the cache.
            directory.listFiles()?.forEach { it.delete() }

            val partial = File(directory, "update-$version.apk.part")
            val complete = File(directory, "update-$version.apk")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("User-Agent", "NfcExplorer")
                setRequestProperty("Accept", "application/octet-stream")
            }
            try {
                val code = connection.responseCode
                check(code == HttpURLConnection.HTTP_OK) { "download returned HTTP $code" }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            check(partial.length() > 0) { "downloaded file was empty" }
            check(partial.renameTo(complete)) { "could not finalise the download" }
            complete
        }
    }

    /**
     * Opens the system installer for [apk].
     *
     * `FLAG_GRANT_READ_URI_PERMISSION` is what lets the installer, a different process, read a file
     * inside this app's cache.
     */
    override fun install(apk: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000
    }
}
