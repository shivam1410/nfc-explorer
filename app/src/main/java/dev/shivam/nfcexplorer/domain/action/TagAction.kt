package dev.shivam.nfcexplorer.domain.action

/** Media transport commands a tap can issue. */
enum class MediaKey { PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * What a tap should do.
 *
 * Describes the *intent* of an action, never how to perform it — constructing an actual
 * `android.content.Intent` belongs to `data/action`, so this stays pure Kotlin and testable without
 * a device.
 *
 * Every variant validates at construction. An action is stored and later fired with no user present
 * to correct it, so a malformed one should fail where it is created rather than at the moment of a
 * tap, which is the worst possible time to discover a blank package name.
 */
sealed interface TagAction {

    /**
     * An action that can be performed on its own, with no runtime state to consult first.
     *
     * The split exists so [IntentSpecMapper.map] can stay pure and total. A composite action such as
     * [WhileNotificationShowing] cannot be mapped without asking the device a question, so it is
     * resolved to a [Leaf] before mapping — and the type system, rather than a comment, is what
     * guarantees the mapper is never handed one.
     */
    sealed interface Leaf : TagAction

    /** Launches an installed app by package name. */
    data class LaunchApp(val packageName: String) : Leaf {
        init {
            require(packageName.isNotBlank()) { "packageName must not be blank" }
        }
    }

    /**
     * Opens a URI: an `https://` link, or an app deep link such as a YouTube Music playlist.
     */
    data class OpenUri(val uri: String) : Leaf {
        init {
            require(uri.isNotBlank()) { "uri must not be blank" }
            // A URI without a scheme resolves to nothing, and would fail silently at tap time.
            require(SCHEME.containsMatchIn(uri)) { "uri must start with a scheme, e.g. https://" }
        }
    }

    /**
     * Sends an explicit intent action, with an optional data URI and string extras.
     *
     * The escape hatch: it covers any app documenting an intent, which is how Sleep as Android and
     * similar are reached without this app growing a plugin per service.
     *
     * Extras are strings only. No component targeting, no parcelables — what a stored action can
     * express stays close to what a user typed.
     */
    data class SendIntent(
        val action: String,
        val uri: String? = null,
        val extras: Map<String, String> = emptyMap(),
    ) : Leaf {
        init {
            require(action.isNotBlank()) { "intent action must not be blank" }
            require(uri == null || SCHEME.containsMatchIn(uri)) {
                "uri, when present, must start with a scheme"
            }
            require(extras.keys.none { it.isBlank() }) { "extra keys must not be blank" }
        }
    }

    data class MediaCommand(val key: MediaKey) : Leaf

    /**
     * Drags a finger across the screen, for a control that an intent cannot reach.
     *
     * The last resort, and it is deliberately unpleasant to configure. An app that exposes an intent
     * should be driven with [SendIntent]; this exists for controls that are a *gesture* and nothing
     * else — Sleep Cycle's slide-to-stop being the case it was written for, where the app publishes
     * an intent to start a sleep session but none to end one.
     *
     * Coordinates are **ratios of the screen**, not pixels. A recorded pixel pair is only correct on
     * the device it was recorded on; a ratio survives a different phone and a rotation.
     *
     * [requireForegroundPackage], when set, aborts the drag unless that package is frontmost. Without
     * it a mistimed tap pokes at whatever happens to be on screen — which is not hypothetical: while
     * this feature was being investigated, an unrelated app stole focus and silently absorbed several
     * drags aimed at Sleep Cycle.
     *
     * [awaitForegroundMillis] is how long to keep looking for that package before giving up. A fixed
     * pause would have to be pessimistic to cover a slow launch, and would still lose to an app that
     * grabs the screen a moment later; waiting for the condition costs nothing when the screen is
     * already right and survives the case that actually happens.
     *
     * [holdMillis] and [steps] are not decoration. A smoothly interpolated swipe of the right length
     * was tested against Sleep Cycle's slider twice and did nothing at all; a stepped drag that
     * dwells at the start point before moving is what actually grabs the control.
     */
    data class DragGesture(
        val startXRatio: Float,
        val startYRatio: Float,
        val endXRatio: Float,
        val endYRatio: Float,
        val holdMillis: Long = DEFAULT_HOLD_MILLIS,
        val travelMillis: Long = DEFAULT_TRAVEL_MILLIS,
        val steps: Int = DEFAULT_STEPS,
        val requireForegroundPackage: String? = null,
        val awaitForegroundMillis: Long = DEFAULT_AWAIT_FOREGROUND_MILLIS,
    ) : Leaf {
        init {
            require(startXRatio in RATIO && startYRatio in RATIO) { "start must be within the screen" }
            require(endXRatio in RATIO && endYRatio in RATIO) { "end must be within the screen" }
            require(holdMillis >= 0) { "holdMillis must not be negative" }
            require(travelMillis > 0) { "travelMillis must be positive" }
            require(steps >= 2) { "steps must be at least 2" }
            require(requireForegroundPackage?.isNotBlank() != false) {
                "requireForegroundPackage, when present, must not be blank"
            }
            require(awaitForegroundMillis >= 0) { "awaitForegroundMillis must not be negative" }
        }
    }

    /**
     * Starts a Toggl time entry, or stops the one already running.
     *
     * A named integration rather than a generic HTTP action, and worth justifying since [SendIntent]
     * exists precisely to avoid a plugin per service: an intent cannot carry Basic auth, read a JSON
     * response, and branch on it. Expressing that generically would mean inventing an HTTP DSL that
     * only one action would ever use.
     *
     * Unlike the Sleep Cycle toggle, this needs no notification and no gesture. Toggl answers
     * authoritatively what is running, so a timer stopped from the web app is simply not running the
     * next time a tag is tapped — no local state to drift.
     *
     * The credential is deliberately absent from this type. It lives in the encrypted secret store,
     * never in an action, never in the assignment document, and never on the tag: an Ultralight page
     * has no read authentication, so a token written there is readable by any phone that touches the
     * card.
     */
    data class TogglToggle(
        val workspaceId: Long,
        val description: String,
        val projectId: Long? = null,
    ) : Leaf {
        init {
            require(workspaceId > 0) { "workspaceId must be positive" }
            require(projectId == null || projectId > 0) { "projectId, when present, must be positive" }
        }
    }

    /**
     * Performs several actions in order, pausing [gapMillis] between them.
     *
     * Exists because a gesture usually cannot be the whole story: something has to put the right
     * screen in front of it first. Ending a Sleep Cycle session is "bring the sleep screen up, wait
     * for it to settle, then drag" — three facts that belong to one action, not three tags.
     *
     * Nesting is rejected rather than merely discouraged. A flat list keeps the total run time
     * obvious, which matters for something fired by a tap that the user is not watching.
     */
    data class Steps(
        val steps: List<Leaf>,
        val gapMillis: Long = DEFAULT_GAP_MILLIS,
    ) : Leaf {
        init {
            require(steps.isNotEmpty()) { "steps must not be empty" }
            require(steps.none { it is Steps }) { "steps must not nest" }
            require(gapMillis >= 0) { "gapMillis must not be negative" }
        }
    }

    /**
     * Picks between two actions depending on whether an app is currently showing a notification.
     *
     * This is how one tag becomes a toggle. A long-running app state — a sleep session, a recording, a
     * timer — almost always has an ongoing foreground-service notification behind it, and that
     * notification is far better evidence than a flag this app stores itself: it disappears exactly
     * when the state really ends, including when the app ends it without being asked.
     *
     * A remembered flag was the obvious alternative and is quietly wrong. Sleep Cycle ends its session
     * on its own every morning when the alarm fires; a stored flag would still read "running", so the
     * next tap would try to stop a session that had already stopped, and stay inverted from then on.
     *
     * Matching is by **notification channel**, not by the text on it. Channel ids are chosen by the
     * app's developers and never translated, so `CHANNEL_SLEEP_NOTIFICATION` identifies a running
     * sleep session on any phone; "Analysis in progress" identifies one only in English, and would
     * silently stop matching for a user whose phone is set to anything else.
     *
     * Both branches are [Leaf] on purpose: it bounds resolution to a single step, so a composite can
     * never nest inside a composite and no cycle is representable.
     */
    data class WhileNotificationShowing(
        val packageName: String,
        val channelId: String,
        val showing: Leaf,
        val absent: Leaf,
    ) : TagAction {
        init {
            require(packageName.isNotBlank()) { "packageName must not be blank" }
            require(channelId.isNotBlank()) { "channelId must not be blank" }
        }
    }

    private companion object {
        /** `scheme:` per RFC 3986 — a letter followed by letters, digits, `+`, `-` or `.`. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        val RATIO = 0f..1f

        const val DEFAULT_HOLD_MILLIS = 150L
        const val DEFAULT_TRAVEL_MILLIS = 1_000L
        const val DEFAULT_STEPS = 10
        const val DEFAULT_GAP_MILLIS = 900L
        const val DEFAULT_AWAIT_FOREGROUND_MILLIS = 4_000L
    }
}
