package dev.shivam.nfcexplorer.data.update

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.update.InstalledVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the running build's version from the package manager.
 *
 * Preferred over `BuildConfig` so no Gradle feature has to be switched on for one string, and so the
 * value is whatever is actually installed rather than whatever was compiled in.
 */
@Singleton
class PackageInstalledVersion @Inject constructor(
    @ApplicationContext private val context: Context,
) : InstalledVersion {

    override fun name(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: UNKNOWN

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
