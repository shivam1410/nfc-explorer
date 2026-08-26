package dev.shivam.nfcexplorer.domain.toggl

/**
 * Account-level Toggl settings.
 *
 * The workspace lives here rather than on each action because it is a property of the account, not
 * of a tag: every timer this app starts goes to the same workspace, and asking for it again on every
 * tag invited a different answer each time. Changing it in one place now retargets every Toggl tag.
 *
 * Not a secret, so it is deliberately not in the [dev.shivam.nfcexplorer.domain.secret.SecretStore] —
 * putting a plain id behind the Keystore would blur what that store is for.
 */
interface TogglConfig {

    /** The configured workspace, or null when the user has not set one. */
    fun workspaceId(): Long?

    fun setWorkspaceId(id: Long?)
}
