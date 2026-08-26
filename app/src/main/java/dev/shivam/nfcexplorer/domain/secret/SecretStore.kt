package dev.shivam.nfcexplorer.domain.secret

/**
 * Stores credentials that must never appear in plain storage, an export, or on a tag.
 *
 * An interface in `domain/` so the action layer can depend on "there is a token for Toggl" without
 * depending on how it is protected, and so tests can supply an in-memory one.
 *
 * The deliberate omission is a way to enumerate or read everything. A caller asks for one named
 * secret; nothing offers a dump, because the session export and the log both walk this app's state
 * and neither has any business finding a token in it.
 */
interface SecretStore {

    /** The secret for [key], or null when none has been stored. */
    fun read(key: String): String?

    /** True when a secret exists, so UI can say "set" without reading the value. */
    fun has(key: String): Boolean

    fun write(key: String, value: String)

    fun clear(key: String)

    companion object {
        /** The Toggl personal API token. */
        const val TOGGL_TOKEN = "toggl.apiToken"
    }
}
