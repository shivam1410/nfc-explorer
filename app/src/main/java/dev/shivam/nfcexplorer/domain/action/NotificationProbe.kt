package dev.shivam.nfcexplorer.domain.action

/**
 * Whether an app is currently showing a notification on a given channel.
 *
 * Three states, not a `Boolean`, and the third is the point. Reading other apps' notifications needs
 * a permission the user grants by hand in system settings, and it can be revoked at any time. If that
 * collapsed into `false`, a revoked permission would look exactly like "no sleep session running" —
 * so a tag meant to *end* a session would cheerfully start a second one, and the cause would be
 * invisible.
 *
 * The same reasoning as `MemoryDump` refusing to render an unread page as `00`: absence and
 * not-knowing are different facts and are kept apart.
 */
sealed interface NotificationState {

    /** The app has a live notification on that channel. */
    data object Showing : NotificationState

    /** Access is granted and there is no such notification. */
    data object Absent : NotificationState

    /** The question could not be asked. [reason] is for the log and the user, not for control flow. */
    data class Unavailable(val reason: String) : NotificationState
}

/** Asks the platform whether a notification is showing. Implemented in `data/`. */
fun interface NotificationProbe {
    fun stateOf(packageName: String, channelId: String): NotificationState
}
