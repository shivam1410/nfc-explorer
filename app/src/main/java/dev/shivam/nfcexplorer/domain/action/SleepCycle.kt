package dev.shivam.nfcexplorer.domain.action

/**
 * A ready-made toggle for Sleep Cycle (`com.northcube.sleepcycle`).
 *
 * A preset rather than a plugin: it is built entirely out of the general [TagAction] vocabulary and
 * holds no code of its own, so it is a set of *constants that were measured*, not a second code path.
 * Any other app with a start intent and a slide-to-stop control can be described the same way without
 * touching this file.
 *
 * ## Where these values come from
 *
 * Sleep Cycle publishes no automation API. Everything below was read out of the installed APK
 * (`4.26.32-production`) and then confirmed on a device — see `docs/sleep-cycle-automation.md` for
 * the full account, including the routes that were tried and rejected.
 *
 * - **Starting** is a documented-nowhere but genuinely exported activity. `StartupAlarmActivity` is
 *   `exported="true"` and demands no permission, so any app may launch it, and doing so starts a real
 *   session — the foreground service comes up and the tracking notification appears. It also works
 *   with the phone locked, which matters for a tag tapped at bedtime.
 * - **Stopping** has no intent at all. The sleep service accepts exactly four actions
 *   (`ACTION_START_NEW_SESSION`, `ACTION_MAINTAIN`, `ACTION_STOP_ALARM`,
 *   `ACTION_BROADCAST_CURRENT_ALARM`) and none of them ends a session — `ACTION_STOP_ALARM` only
 *   silences a ringing alarm. Firing the start action again does not toggle; it just opens the live
 *   screen. The ongoing notification carries no action buttons. The app *does* have a clean
 *   session-stop path behind its watch integration, but `WearableListenerService` verifies the caller
 *   is Play Services and silently drops a forged message.
 *
 * That leaves the slider on the sleep screen, which is why this preset needs a gesture and an
 * accessibility service where every other action in this app needs neither.
 */
object SleepCycle {

    const val PACKAGE = "com.northcube.sleepcycle"

    /** Exported, permission-free, and starts a session even from the lock screen. */
    const val ACTION_STARTUP_ALARM = "com.northcube.sleepcycle.STARTUP_ALARM"

    /**
     * The channel the ongoing "Analysis in progress" notification is posted on.
     *
     * Matched instead of the text because channel ids are never translated.
     */
    const val TRACKING_CHANNEL_ID = "CHANNEL_SLEEP_NOTIFICATION"

    /**
     * The slide-to-stop gesture, as ratios of the screen.
     *
     * Measured on a 1080x2424 display: press at (540, 1950), release at (540, 1100). The travel is
     * what matters and it is long — a drag ending at y=1300 was tested and did nothing, while the same
     * drag continued to y=1100 ended the session. Anything short of roughly a third of the screen
     * should be assumed not to trigger it.
     */
    const val STOP_X_RATIO = 0.5f
    const val STOP_START_Y_RATIO = 0.805f
    const val STOP_END_Y_RATIO = 0.454f

    /** Starts a session. Safe to use on its own if you only want a "go to bed" tag. */
    fun start(): TagAction.Leaf = TagAction.SendIntent(action = ACTION_STARTUP_ALARM)

    /**
     * Ends a running session by dragging the slider.
     *
     * Guarded on Sleep Cycle being frontmost, because a drag is blind: if anything else has taken the
     * screen, the gesture lands on that app instead. The start intent is what brings the sleep screen
     * up, so [toggle] fires this only when a session is already running and that screen is reachable.
     */
    fun stop(): TagAction.Leaf = TagAction.Steps(
        steps = listOf(
            // Sending the start action while a session is running does not start a second one; the
            // app recognises the running session and opens the live sleep screen. That is exactly
            // what the drag needs in front of it, so the "start" intent doubles as "show me the
            // screen with the slider on it".
            TagAction.SendIntent(action = ACTION_STARTUP_ALARM),
            TagAction.DragGesture(
                startXRatio = STOP_X_RATIO,
                startYRatio = STOP_START_Y_RATIO,
                endXRatio = STOP_X_RATIO,
                endYRatio = STOP_END_Y_RATIO,
                requireForegroundPackage = PACKAGE,
            ),
        ),
    )

    /** One tag, both directions: start when nothing is running, end the session when one is. */
    fun toggle(): TagAction = TagAction.WhileNotificationShowing(
        packageName = PACKAGE,
        channelId = TRACKING_CHANNEL_ID,
        showing = stop(),
        absent = start(),
    )
}
