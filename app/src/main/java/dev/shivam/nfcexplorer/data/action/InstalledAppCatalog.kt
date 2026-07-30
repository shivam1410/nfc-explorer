package dev.shivam.nfcexplorer.data.action

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.action.AppCatalog
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads launchable apps from `PackageManager`.
 *
 * Thin by design, like the transport adapters: the query and the label lookup happen here, and every
 * decision made about the result — searching it, pre-filling a label from it — lives above the
 * [AppCatalog] seam where a fake can drive it.
 *
 * Visibility comes from the `<queries>` element in the manifest declaring the MAIN/LAUNCHER intent, not
 * from `QUERY_ALL_PACKAGES`. Under Android 11+ package filtering that is exactly the set this needs: an
 * app the user could tap on their home screen. `QUERY_ALL_PACKAGES` would additionally reveal apps with
 * no launcher entry, which cannot be launched anyway, in exchange for a permission that reads as
 * surveillance and would break the one-permission rule this app holds itself to.
 */
@Singleton
class InstalledAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppCatalog {

    /**
     * Off the main thread: this enumerates and loads a label for every launchable app, which on a full
     * phone is hundreds of `PackageManager` round trips and long enough to drop frames.
     */
    override suspend fun launchable(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        resolve(manager, launcherIntent)
            .map { resolved ->
                InstalledApp(
                    packageName = resolved.activityInfo.packageName,
                    label = resolved.loadLabel(manager).toString(),
                )
            }
            // An app can expose several launcher activities; the user is choosing an app, not one of
            // its entry points, and LaunchApp stores only a package either way.
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The typed-flags overload arrived in API 33; below that the deprecated form is the only option. */
    private fun resolve(manager: PackageManager, intent: Intent) = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        else -> {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, 0)
        }
    }
}
