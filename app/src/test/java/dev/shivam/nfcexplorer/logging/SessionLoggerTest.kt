package dev.shivam.nfcexplorer.logging

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionLoggerTest {

    /** Deterministic clock: tests must not depend on wall time. */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun `a new logger has no entries`() {
        assertTrue(SessionLogger(FakeClock()).entries.value.isEmpty())
    }

    @Test
    fun `entries are appended in call order`() {
        val logger = SessionLogger(FakeClock())

        logger.info("scan", "first")
        logger.info("scan", "second")
        logger.info("scan", "third")

        assertEquals(
            listOf("first", "second", "third"),
            logger.entries.value.map { it.message },
        )
    }

    @Test
    fun `timestamps come from the injected time source`() {
        val clock = FakeClock(now = 5_000L)
        val logger = SessionLogger(clock)

        logger.info("scan", "before")
        clock.now = 7_500L
        logger.info("scan", "after")

        assertEquals(listOf(5_000L, 7_500L), logger.entries.value.map { it.timestampMillis })
    }

    @Test
    fun `sequence numbers increase even when the clock does not move`() {
        // Several operations in one tag session land in the same millisecond, so the
        // timestamp alone cannot order them.
        val logger = SessionLogger(FakeClock(now = 42L))

        repeat(3) { logger.info("read", "page") }

        assertEquals(listOf(0L, 1L, 2L), logger.entries.value.map { it.sequence })
        assertEquals(setOf(42L), logger.entries.value.map { it.timestampMillis }.toSet())
    }

    @Test
    fun `level helpers record the matching level`() {
        val logger = SessionLogger(FakeClock())

        logger.debug("c", "d")
        logger.info("c", "i")
        logger.warn("c", "w")
        logger.error("c", "e")

        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            logger.entries.value.map { it.level },
        )
    }

    @Test
    fun `structured payload is preserved`() {
        val logger = SessionLogger(FakeClock())

        logger.error(
            category = "write",
            message = "page rejected",
            payload = mapOf("page" to "7", "lockedBy" to "L_7", "exception" to "TagNakException"),
        )

        assertEquals(
            mapOf("page" to "7", "lockedBy" to "L_7", "exception" to "TagNakException"),
            logger.entries.value.single().payload,
        )
    }

    // --- Invariant I4: append-only ---

    @Test
    fun `appending never alters or removes an earlier entry`() {
        val logger = SessionLogger(FakeClock())
        logger.info("scan", "first")
        val firstSnapshot = logger.entries.value.single()

        repeat(5) { index -> logger.info("scan", "later-$index") }

        assertEquals(firstSnapshot, logger.entries.value.first())
        assertEquals(6, logger.entries.value.size)
    }

    @Test
    fun `a previously read list is not mutated by later appends`() {
        val logger = SessionLogger(FakeClock())
        logger.info("scan", "first")
        val heldReference = logger.entries.value

        logger.info("scan", "second")

        // A caller holding the old list -- an exporter mid-write, say -- must keep seeing
        // exactly what it read.
        assertEquals(1, heldReference.size)
        assertEquals(2, logger.entries.value.size)
    }

    // --- Emission ---

    @Test
    fun `entries emits the growing log to collectors`() = runTest {
        val logger = SessionLogger(FakeClock())

        logger.entries.test {
            assertTrue(awaitItem().isEmpty())

            logger.info("scan", "tag discovered")
            assertEquals(listOf("tag discovered"), awaitItem().map { it.message })

            logger.info("read", "page 0")
            assertEquals(listOf("tag discovered", "page 0"), awaitItem().map { it.message })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `log returns the entry it appended`() {
        val logger = SessionLogger(FakeClock(now = 99L))

        val returned = logger.log(LogLevel.WARN, "nfc", "tag lost")

        assertEquals(returned, logger.entries.value.single())
        assertEquals(99L, returned.timestampMillis)
    }

    // --- Concurrency: the pipeline logs from Dispatchers.IO while the UI collects ---

    @Test
    fun `concurrent appends lose nothing and keep sequence numbers unique`() = runTest {
        val logger = SessionLogger(FakeClock())
        val writers = 8
        val perWriter = 50

        withContext(Dispatchers.Default) {
            (0 until writers).map { writer ->
                async {
                    repeat(perWriter) { index -> logger.info("w$writer", "entry-$index") }
                }
            }.awaitAll()
        }

        val entries = logger.entries.value
        assertEquals(writers * perWriter, entries.size)
        assertEquals(entries.size, entries.map { it.sequence }.toSet().size)
    }
}
