package dev.shivam.nfcexplorer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two ways a tag reaches this app have to agree on which tags they accept.
 *
 * Reader mode, used while the app is open, enables all four RF technologies. The manifest's
 * tech-list decides which tags the system will launch the app for when it is closed. Those were
 * allowed to disagree, and the failure was silent in the worst direction: a tag of an unclaimed
 * family could be scanned, assigned an action and shown as set up, then do nothing forever when
 * tapped, because nothing had asked the system to start the app for it.
 *
 * There is a third path, and it caught this test out once already: the trigger proves a tag is live
 * before running anything, and it could only open the families it knew about. Widening the filter
 * alone therefore bought nothing -- the system launched the app for an NfcV tag, the presence check
 * found no technology it could open, and a tag sitting on the phone was treated as absent.
 *
 * Asserted against the sources rather than a constant, because a constant would have agreed with
 * itself while the files diverged.
 */
class TagDispatchReachTest {

    @Test
    fun `every technology reader mode accepts can also launch the app`() {
        val claimed = techFilter()

        RF_TECHNOLOGIES.forEach { technology ->
            assertTrue(
                technology in claimed,
                "reader mode accepts $technology, so a tag assigned an action through it must be " +
                    "able to launch the app; add it to nfc_tech_filter.xml. Claimed: $claimed",
            )
        }
    }

    @Test
    fun `reader mode still enables the four technologies this pins it to`() {
        val source = readerModeSource()

        RF_FLAGS.forEach { flag ->
            assertTrue(
                source.contains(flag),
                "reader mode no longer enables $flag, so the filter above is pinned to a claim " +
                    "that is out of date",
            )
        }
    }

    @Test
    fun `every technology the app claims can be opened for the presence check`() {
        val trigger = locate(TRIGGER_PATHS).readText()

        RF_TECHNOLOGIES.map { it.substringAfterLast('.') }.forEach { technology ->
            assertTrue(
                trigger.contains("$technology.get(tag)"),
                "the filter claims $technology, so the trigger must be able to open one; without " +
                    "it the tag launches the app and is then refused as absent on every tap",
            )
        }
    }

    /**
     * IsoDep is what bank cards and transit passes speak. Claiming it would put this app in the
     * chooser whenever one is near the phone, for the sake of tags nobody buys to automate.
     */
    @Test
    fun `the app does not claim contactless payment cards`() {
        assertEquals(
            emptyList(),
            techFilter().filter { it.endsWith("IsoDep") },
            "IsoDep would claim bank cards and transit passes",
        )
    }

    private fun techFilter(): List<String> =
        Regex("<tech>([^<]+)</tech>")
            .findAll(locate(TECH_FILTER_PATHS).readText())
            .map { it.groupValues[1].trim() }
            .toList()

    private fun readerModeSource(): String = locate(READER_MODE_PATHS).readText()

    private fun locate(candidates: List<String>): File =
        candidates.map(::File).firstOrNull { it.isFile }
            ?: error(
                "could not locate the file from ${File("").absolutePath}; " +
                    "tried ${candidates.joinToString()}",
            )

    private companion object {
        val RF_TECHNOLOGIES = listOf(
            "android.nfc.tech.NfcA",
            "android.nfc.tech.NfcB",
            "android.nfc.tech.NfcF",
            "android.nfc.tech.NfcV",
        )

        val RF_FLAGS = listOf(
            "FLAG_READER_NFC_A",
            "FLAG_READER_NFC_B",
            "FLAG_READER_NFC_F",
            "FLAG_READER_NFC_V",
        )

        val TECH_FILTER_PATHS = listOf(
            "src/main/res/xml/nfc_tech_filter.xml",
            "app/src/main/res/xml/nfc_tech_filter.xml",
            "../app/src/main/res/xml/nfc_tech_filter.xml",
        )

        val TRIGGER_PATHS = listOf(
            "src/main/java/dev/shivam/nfcexplorer/TagActionActivity.kt",
            "app/src/main/java/dev/shivam/nfcexplorer/TagActionActivity.kt",
            "../app/src/main/java/dev/shivam/nfcexplorer/TagActionActivity.kt",
        )

        val READER_MODE_PATHS = listOf(
            "src/main/java/dev/shivam/nfcexplorer/data/nfc/NfcReaderModeController.kt",
            "app/src/main/java/dev/shivam/nfcexplorer/data/nfc/NfcReaderModeController.kt",
            "../app/src/main/java/dev/shivam/nfcexplorer/data/nfc/NfcReaderModeController.kt",
        )
    }
}
