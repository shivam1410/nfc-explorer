package dev.shivam.nfcexplorer.domain.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Version comparison, which decides whether the user is nagged about an update.
 *
 * Both failure directions matter: announcing an update that does not exist trains people to ignore
 * the banner, and hiding a real one defeats the feature. Uncomparable input therefore resolves to
 * "no update" rather than to a guess.
 */
class AppVersionTest {

    @Test
    fun `a plain semver parses`() {
        assertEquals(listOf(1, 2, 3), AppVersion.parse("1.2.3"))
    }

    /** Real tags from this repo are not bare semver. */
    @Test
    fun `a decorated release tag parses to its numeric run`() {
        assertEquals(listOf(0, 1, 0), AppVersion.parse("sleep-cycle-toggle-v0.1.0"))
        assertEquals(listOf(1, 4), AppVersion.parse("v1.4"))
    }

    @Test
    fun `a tag with no digits is uncomparable rather than zero`() {
        assertNull(AppVersion.parse("nightly"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `a later version is newer`() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
    }

    /** Padding, so a release tagged `1.2` does not read as older than the `1.2.0` installed. */
    @Test
    fun `missing components count as zero`() {
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"))
        assertFalse(AppVersion.isNewer("1.2.0", "1.2"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
        assertFalse(AppVersion.isNewer("0.9.9", "1.0.0"))
    }

    /** Ten is greater than nine; string ordering would say otherwise. */
    @Test
    fun `components compare numerically not alphabetically`() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.0"))
        assertFalse(AppVersion.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `an uncomparable version on either side is never an update`() {
        assertFalse(AppVersion.isNewer("nightly", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.2.0", "unknown"))
    }
}
