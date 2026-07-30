package dev.shivam.nfcexplorer.domain.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching the installed-app list.
 *
 * The editor used to take a package name as free text, which meant knowing that YouTube Music is
 * `com.google.android.apps.youtube.music`. Nobody knows that. Picking from a list also makes an
 * unlaunchable target unreachable rather than merely discouraged.
 */
class InstalledAppTest {

    private val apps = listOf(
        InstalledApp("com.google.android.apps.youtube.music", "YouTube Music"),
        InstalledApp("com.spotify.music", "Spotify"),
        InstalledApp("com.toggl.giskard", "Toggl Track"),
        InstalledApp("com.urbandroid.sleep", "Sleep as Android"),
    )

    @Test
    fun `an empty query offers everything`() {
        assertEquals(apps, apps.matching(""))
    }

    @Test
    fun `a blank query offers everything`() {
        // Typing a space and deleting the word should not empty the list.
        assertEquals(apps, apps.matching("   "))
    }

    @Test
    fun `matching is case insensitive on the name people actually know`() {
        val found = apps.matching("toggl")

        assertEquals(listOf("Toggl Track"), found.map { it.label })
    }

    @Test
    fun `matching finds a word anywhere in the name, and keeps the offered order`() {
        // "music" is the second word of YouTube Music's name and the last segment of Spotify's
        // package, so both match by different routes - and they come back in catalog order rather
        // than in whichever order the two rules happened to fire.
        assertEquals(
            listOf("YouTube Music", "Spotify"),
            apps.matching("music").map { it.label },
        )
    }

    @Test
    fun `the package name is searchable too`() {
        // The power-user path this replaced: someone who does know the package should not lose it.
        val found = apps.matching("urbandroid")

        assertEquals(listOf("Sleep as Android"), found.map { it.label })
    }

    @Test
    fun `a query matching nothing offers nothing`() {
        assertTrue(apps.matching("zzzz").isEmpty())
    }
}
