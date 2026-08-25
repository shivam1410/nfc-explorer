package dev.shivam.nfcexplorer.domain.action

/**
 * Collapses a [TagAction] to the single [TagAction.Leaf] that should actually be performed.
 *
 * Pure, and the whole reason the toggle is testable without a device: deciding *which* branch to take
 * is the part that can be wrong, so it lives here with a fake probe in the tests, while asking the
 * platform what is on screen is mechanical delegation below the seam.
 */
object ActionResolver {

    /** The outcome of resolving. Refusal is a first-class result, not an exception. */
    sealed interface Resolution {

        /** Perform this. */
        data class Perform(val leaf: TagAction.Leaf) : Resolution

        /**
         * Do nothing, and say why.
         *
         * Guessing would be worse than stopping. A toggle that cannot see the current state has a
         * 50% chance of doing the opposite of what was wanted, and the wrong half — starting a
         * second sleep session, or ending a real night's recording — is not a cheap mistake.
         */
        data class Refused(val reason: String) : Resolution
    }

    fun resolve(action: TagAction, notifications: NotificationProbe): Resolution = when (action) {
        is TagAction.Leaf -> Resolution.Perform(action)

        is TagAction.WhileNotificationShowing ->
            when (val state = notifications.stateOf(action.packageName, action.channelId)) {
                NotificationState.Showing -> Resolution.Perform(action.showing)
                NotificationState.Absent -> Resolution.Perform(action.absent)
                is NotificationState.Unavailable -> Resolution.Refused(state.reason)
            }
    }
}
