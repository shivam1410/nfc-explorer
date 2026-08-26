package dev.shivam.nfcexplorer.data.system

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.action.SystemGrantState
import dev.shivam.nfcexplorer.domain.action.SystemGrants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads both grants out of `Settings.Secure`.
 *
 * Asking the settings store rather than inferring from a bound service matters: a service that has
 * not bound yet and a permission that was never granted look identical from the binder, and they need
 * completely different things from the user.
 */
@Singleton
class AndroidSystemGrants @Inject constructor(
    @ApplicationContext private val context: Context,
) : SystemGrants {

    override fun current() = SystemGrantState(
        notificationAccess = isNotificationAccessGranted(),
        gestureService = isGestureServiceEnabled(),
    )

    fun isNotificationAccessGranted(): Boolean =
        listed(ENABLED_LISTENERS, TrackingNotificationListener::class.java)

    /**
     * Enabled *and* accessibility switched on globally. The master switch being off leaves stale
     * component names in the list, so the list alone would over-report.
     */
    fun isGestureServiceEnabled(): Boolean {
        val master = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        }.getOrDefault(0)
        if (master != 1) return false
        return listed(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, GestureDispatchService::class.java)
    }

    /** Whether our component appears in one of the colon-separated secure settings lists. */
    private fun listed(key: String, service: Class<*>): Boolean {
        val raw = runCatching { Settings.Secure.getString(context.contentResolver, key) }
            .getOrNull()
            .orEmpty()
        val us = ComponentName(context, service)
        return raw.split(':').any { entry -> ComponentName.unflattenFromString(entry) == us }
    }

    private companion object {
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}
