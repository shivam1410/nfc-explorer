package dev.shivam.nfcexplorer.domain.action

/**
 * The apps on this device a tag could be pointed at.
 *
 * A seam for the same reason as the transport interfaces: enumerating installed apps needs
 * `PackageManager`, which does not exist on the JVM, and everything worth testing — searching,
 * pre-filling a label, not re-reading the list — sits above it. See
 * `docs/adr/0001-fakeable-tag-transport.md` for the pattern.
 */
interface AppCatalog {

    /**
     * Every app with a launcher entry, sorted by label.
     *
     * Restricted to launchable apps on purpose. [TagAction.LaunchApp] can only work through a launch
     * intent, so an app without one could be typed into a text field but never actually run — offering
     * only this list makes that failure unreachable rather than merely discouraged.
     */
    suspend fun launchable(): List<InstalledApp>
}
