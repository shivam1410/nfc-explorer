package dev.shivam.nfcexplorer.data.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.shivam.nfcexplorer.domain.action.NotificationProbe
import dev.shivam.nfcexplorer.domain.action.NotificationState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the bound listener so [ActiveNotificationProbe] can read what is on screen.
 *
 * A companion reference is the only way across: the platform constructs this service, so it cannot be
 * injected, and nothing else can obtain the instance that is actually connected.
 */
class TrackingNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        bound = this
    }

    override fun onListenerDisconnected() {
        bound = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        bound = null
        super.onDestroy()
    }

    companion object {
        /** Written from the main thread, read from whichever thread performs an action. */
        @Volatile
        internal var bound: TrackingNotificationListener? = null
    }
}

/**
 * Answers "is this app showing a notification on this channel?" from the live notification shade.
 *
 * Reading other apps' notifications requires an access grant the user makes by hand, and the whole
 * point of [NotificationState.Unavailable] is that this class never pretends otherwise. Every reason
 * it cannot answer is reported as itself, because a toggle that silently assumes "not running" would
 * start a second sleep session rather than end the one in progress.
 */
@Singleton
class ActiveNotificationProbe @Inject constructor(
    private val grants: AndroidSystemGrants,
) : NotificationProbe {

    override fun stateOf(packageName: String, channelId: String): NotificationState {
        if (!grants.isNotificationAccessGranted()) {
            return NotificationState.Unavailable(
                "Notification access is not granted to NFC Explorer",
            )
        }

        val service = TrackingNotificationListener.bound
            ?: return NotificationState.Unavailable("Notification listener is not connected yet")

        // getActiveNotifications throws if the binder died between the null check and here.
        val active: Array<StatusBarNotification> = runCatching { service.activeNotifications }
            .getOrNull()
            ?: return NotificationState.Unavailable("Could not read active notifications")

        val showing = active.any { it.packageName == packageName && it.notification.channelId == channelId }
        return if (showing) NotificationState.Showing else NotificationState.Absent
    }

}
