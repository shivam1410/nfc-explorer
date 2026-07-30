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

    /** Launches an installed app by package name. */
    data class LaunchApp(val packageName: String) : TagAction {
        init {
            require(packageName.isNotBlank()) { "packageName must not be blank" }
        }
    }

    /**
     * Opens a URI: an `https://` link, or an app deep link such as a YouTube Music playlist.
     */
    data class OpenUri(val uri: String) : TagAction {
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
    ) : TagAction {
        init {
            require(action.isNotBlank()) { "intent action must not be blank" }
            require(uri == null || SCHEME.containsMatchIn(uri)) {
                "uri, when present, must start with a scheme"
            }
            require(extras.keys.none { it.isBlank() }) { "extra keys must not be blank" }
        }
    }

    data class MediaCommand(val key: MediaKey) : TagAction

    private companion object {
        /** `scheme:` per RFC 3986 — a letter followed by letters, digits, `+`, `-` or `.`. */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    }
}
