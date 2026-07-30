package dev.shivam.nfcexplorer.domain.action

/**
 * An app a tag can be pointed at.
 *
 * @param packageName what [TagAction.LaunchApp] actually stores.
 * @param label what the user recognises. Nobody knows that YouTube Music is
 *   `com.google.android.apps.youtube.music`, so the package name is never what gets shown.
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Narrows the offered apps to those worth showing for [query].
 *
 * Matches the label *and* the package name. The label is how anyone will actually search; the package
 * name is kept searchable so someone who does know it — the reason this list replaced a text field —
 * has not lost anything by the change.
 *
 * A blank query offers everything, so deleting a half-typed word restores the full list rather than
 * emptying it. The incoming order is preserved: the catalog has already sorted by label, and reordering
 * results under the user's eyes as they type makes the list harder to scan, not easier.
 */
fun List<InstalledApp>.matching(query: String): List<InstalledApp> {
    val needle = query.trim()
    if (needle.isEmpty()) return this
    return filter {
        it.label.contains(needle, ignoreCase = true) ||
            it.packageName.contains(needle, ignoreCase = true)
    }
}
