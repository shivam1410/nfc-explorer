package dev.shivam.nfcexplorer.domain.action

/**
 * Which of the two user-granted capabilities the Sleep Cycle toggle needs are currently in place.
 *
 * Both are revocable at any time from system settings, so this is read fresh rather than remembered.
 */
data class SystemGrantState(
    val notificationAccess: Boolean = false,
    val gestureService: Boolean = false,
) {
    /** A toggle can only work end to end when both are granted. */
    val readyForToggle: Boolean get() = notificationAccess && gestureService
}

/** Reads the current grants. Implemented in `data/`, because only Android knows. */
fun interface SystemGrants {
    fun current(): SystemGrantState
}

/**
 * The settings screens that grant them.
 *
 * Held as plain action strings so they can be performed through the existing [TagAction.SendIntent]
 * path rather than needing the UI layer to learn about `Context`. Opening a settings screen is just
 * another intent, so it goes through the same machinery a tag would use.
 */
object SystemSettings {
    const val NOTIFICATION_LISTENERS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
    const val ACCESSIBILITY = "android.settings.ACCESSIBILITY_SETTINGS"

    fun openNotificationAccess(): TagAction = TagAction.SendIntent(NOTIFICATION_LISTENERS)

    fun openAccessibility(): TagAction = TagAction.SendIntent(ACCESSIBILITY)
}
