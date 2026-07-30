package dev.shivam.nfcexplorer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Enforces invariant I1: the domain layer contains no Android framework dependency.
 *
 * This is a build-breaking guard rather than a convention, because the guarantee it protects
 * is load-bearing. Everything above the transport seam is testable on the JVM precisely
 * because it never touches `android.*` — NFC hardware does not exist in the emulator, so the
 * day a framework import creeps into `domain/` is the day that testability quietly dies.
 *
 * See `docs/adr/0001-fakeable-tag-transport.md`.
 */
class DomainPurityTest {

    @Test
    fun `domain layer imports no android or androidx types`() {
        val sources = domainSources()

        val violations = sources.flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> isFrameworkImport(line) }
                .map { (index, line) -> "${file.name}:${index + 1}  ${line.trim()}" }
        }

        assertTrue(
            violations.isEmpty(),
            "domain must stay free of framework imports, found:\n" + violations.joinToString("\n"),
        )
    }

    @Test
    fun `the scan actually reaches the domain sources`() {
        // Without this, a wrong path would make the purity test vacuously pass -- the most
        // dangerous kind of green.
        val sources = domainSources()

        assertTrue(
            sources.size >= MIN_EXPECTED_SOURCES,
            "expected at least $MIN_EXPECTED_SOURCES domain sources, scanned ${sources.size}. " +
                "The purity test cannot pass by scanning nothing.",
        )
        assertTrue(sources.any { it.name == "TagTransport.kt" }, "transport seam not scanned")
        assertTrue(sources.any { it.name == "StaticLockDecoder.kt" }, "decoders not scanned")
    }

    @Test
    fun `the detector recognises framework imports it must catch`() {
        // Proves the guard has teeth, permanently and repeatably, rather than relying on a
        // one-off manual experiment of breaking a file and putting it back.
        val forbidden = listOf(
            "import android.nfc.Tag",
            "import android.util.Log",
            "import android.nfc.tech.MifareUltralight",
            "import androidx.compose.runtime.Composable",
            "   import android.os.Bundle",
        )

        forbidden.forEach { line ->
            assertTrue(isFrameworkImport(line), "should have been flagged: $line")
        }
    }

    @Test
    fun `the detector allows plain kotlin and jvm imports`() {
        val allowed = listOf(
            "import java.io.IOException",
            "import java.io.Closeable",
            "import kotlin.math.max",
            "import dev.shivam.nfcexplorer.domain.model.ByteBlock",
            "import kotlinx.coroutines.flow.Flow",
            // Words merely containing the token must not trip the check.
            "// mentions android in a comment",
            "val androidStyleName = 1",
            "import dev.shivam.nfcexplorer.util.androidishHelper",
        )

        allowed.forEach { line ->
            assertEquals(false, isFrameworkImport(line), "should not have been flagged: $line")
        }
    }

    private companion object {

        /** Guards against the scan silently finding nothing. Raise as the layer grows. */
        const val MIN_EXPECTED_SOURCES = 10

        private val FORBIDDEN_PREFIXES = listOf("android.", "androidx.")

        private val CANDIDATE_ROOTS = listOf(
            "src/main/java/dev/shivam/nfcexplorer/domain",
            "app/src/main/java/dev/shivam/nfcexplorer/domain",
            "../app/src/main/java/dev/shivam/nfcexplorer/domain",
        )

        fun isFrameworkImport(line: String): Boolean {
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) return false
            val target = trimmed.removePrefix("import ").trimStart()
            return FORBIDDEN_PREFIXES.any { target.startsWith(it) }
        }

        fun domainSources(): List<File> {
            val root = CANDIDATE_ROOTS.map(::File).firstOrNull { it.isDirectory }
                ?: error(
                    "could not locate domain sources from ${File("").absolutePath}; " +
                        "tried ${CANDIDATE_ROOTS.joinToString()}",
                )
            return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
