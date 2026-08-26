package dev.shivam.nfcexplorer.domain.action

import dev.shivam.nfcexplorer.domain.whatsapp.WhatsApp

/**
 * A platform-agnostic description of what an action should produce.
 *
 * This is the seam that makes the risky part testable. Mapping a [TagAction] to *what should be
 * fired* is where the logic lives; turning an [IntentSpec] into a real `android.content.Intent` — or
 * into a dispatched gesture — is mechanical delegation. So the mapping is unit-tested and the adapter
 * is device-verified: the same reasoning as `docs/adr/0001-fakeable-tag-transport.md`.
 *
 * Not every spec is literally an `Intent` any more. [Drag] is a gesture and [Sequence] is a plan, but
 * they are described here for the same reason the others are — so the decision of what to do is
 * separable from the machinery that does it.
 */
sealed interface IntentSpec {

    /** Resolve and start the launcher intent for [packageName]. */
    data class LaunchPackage(val packageName: String) : IntentSpec

    /** Start an activity for [action], optionally with [uri] as data and string [extras]. */
    data class ActivityIntent(
        val action: String,
        val uri: String? = null,
        val extras: Map<String, String> = emptyMap(),
    ) : IntentSpec

    /** Dispatch a media key event. [keyCode] is an `android.view.KeyEvent` constant. */
    data class MediaKeyEvent(val keyCode: Int) : IntentSpec

    /**
     * Drag a finger, in screen ratios. The adapter multiplies by the real display size.
     *
     * Ratios survive the trip from the device a gesture was measured on to the device it runs on;
     * pixels do not.
     */
    data class Drag(
        val startXRatio: Float,
        val startYRatio: Float,
        val endXRatio: Float,
        val endYRatio: Float,
        val holdMillis: Long,
        val travelMillis: Long,
        val steps: Int,
        val requireForegroundPackage: String?,
        val awaitForegroundMillis: Long,
    ) : IntentSpec

    /** Start or stop a Toggl timer. The token is resolved by the adapter, never carried here. */
    data class TogglTimer(
        val workspaceId: Long,
        val description: String,
        val projectId: Long?,
    ) : IntentSpec

    /** Press a control located by id or accessibility label. */
    data class TapNode(
        val viewId: String?,
        val contentDescription: String?,
        val requireForegroundPackage: String?,
        val awaitForegroundMillis: Long,
    ) : IntentSpec

    /** Perform each spec in order, pausing [gapMillis] between them. Never nested. */
    data class Sequence(val specs: List<IntentSpec>, val gapMillis: Long) : IntentSpec
}

/**
 * Maps leaf actions to intent specs.
 *
 * Pure and total: every [TagAction.Leaf] yields exactly one [IntentSpec], with no failure path — the
 * action types already validated themselves at construction, so there is nothing left to reject here.
 *
 * It takes a [TagAction.Leaf] rather than a [TagAction] deliberately. A composite action cannot be
 * mapped without first asking the device a question, and accepting one here would mean either a
 * failure path or a lie; [ActionResolver] collapses composites first, and the compiler enforces the
 * order rather than a comment asking for it.
 */
object IntentSpecMapper {

    /** `android.intent.action.VIEW`, spelled out so this file needs no Android import. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** `KeyEvent.KEYCODE_MEDIA_*` values. */
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88

    fun map(action: TagAction.Leaf): IntentSpec = when (action) {
        is TagAction.LaunchApp -> IntentSpec.LaunchPackage(action.packageName)

        is TagAction.OpenUri -> IntentSpec.ActivityIntent(action = ACTION_VIEW, uri = action.uri)

        is TagAction.SendIntent -> IntentSpec.ActivityIntent(
            action = action.action,
            uri = action.uri,
            extras = action.extras,
        )

        is TagAction.MediaCommand -> IntentSpec.MediaKeyEvent(
            keyCode = when (action.key) {
                MediaKey.PLAY_PAUSE -> KEYCODE_MEDIA_PLAY_PAUSE
                MediaKey.NEXT -> KEYCODE_MEDIA_NEXT
                MediaKey.PREVIOUS -> KEYCODE_MEDIA_PREVIOUS
            },
        )

        is TagAction.TapNode -> IntentSpec.TapNode(
            viewId = action.viewId,
            contentDescription = action.contentDescription,
            requireForegroundPackage = action.requireForegroundPackage,
            awaitForegroundMillis = action.awaitForegroundMillis,
        )

        is TagAction.DragGesture -> IntentSpec.Drag(
            startXRatio = action.startXRatio,
            startYRatio = action.startYRatio,
            endXRatio = action.endXRatio,
            endYRatio = action.endYRatio,
            holdMillis = action.holdMillis,
            travelMillis = action.travelMillis,
            steps = action.steps,
            requireForegroundPackage = action.requireForegroundPackage,
            awaitForegroundMillis = action.awaitForegroundMillis,
        )

        is TagAction.WhatsAppMessage -> {
            val open = IntentSpec.ActivityIntent(
                action = ACTION_VIEW,
                uri = WhatsApp.linkFor(action.phoneNumber, action.message),
            )
            if (!action.autoSend) {
                open
            } else {
                // Open the chat, let it settle, then press send. The gap is not decoration: the
                // button does not exist until WhatsApp has drawn the conversation.
                IntentSpec.Sequence(
                    specs = listOf(
                        open,
                        IntentSpec.TapNode(
                            viewId = WhatsApp.SEND_BUTTON_ID,
                            contentDescription = WhatsApp.SEND_BUTTON_DESCRIPTION,
                            requireForegroundPackage = WhatsApp.PACKAGE,
                            awaitForegroundMillis = TagAction.DEFAULT_AWAIT_FOREGROUND_MILLIS,
                        ),
                    ),
                    gapMillis = TagAction.DEFAULT_GAP_MILLIS,
                )
            }
        }

        is TagAction.TogglToggle -> IntentSpec.TogglTimer(
            workspaceId = action.workspaceId,
            description = action.description,
            projectId = action.projectId,
        )

        // Steps cannot contain Steps, so this recursion is exactly one level deep.
        is TagAction.Steps -> IntentSpec.Sequence(
            specs = action.steps.map(::map),
            gapMillis = action.gapMillis,
        )
    }
}
