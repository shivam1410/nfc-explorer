package dev.shivam.nfcexplorer.domain.action

/**
 * A platform-agnostic description of the intent an action should produce.
 *
 * This is the seam that makes the risky part testable. Mapping a [TagAction] to *what should be
 * fired* is where the logic lives; turning an [IntentSpec] into a real `android.content.Intent` is
 * mechanical delegation. So the mapping is unit-tested and the adapter is device-verified — the same
 * reasoning as `docs/adr/0001-fakeable-tag-transport.md`.
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
}

/**
 * Maps actions to intent specs.
 *
 * Pure and total: every [TagAction] yields exactly one [IntentSpec], with no failure path — the
 * action types already validated themselves at construction, so there is nothing left to reject here.
 */
object IntentSpecMapper {

    /** `android.intent.action.VIEW`, spelled out so this file needs no Android import. */
    const val ACTION_VIEW = "android.intent.action.VIEW"

    /** `KeyEvent.KEYCODE_MEDIA_*` values. */
    const val KEYCODE_MEDIA_PLAY_PAUSE = 85
    const val KEYCODE_MEDIA_NEXT = 87
    const val KEYCODE_MEDIA_PREVIOUS = 88

    fun map(action: TagAction): IntentSpec = when (action) {
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
    }
}
