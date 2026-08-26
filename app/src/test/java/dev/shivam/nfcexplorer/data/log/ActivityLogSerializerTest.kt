package dev.shivam.nfcexplorer.data.log

import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.logging.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The format the phone and the user's Drive share.
 *
 * Worth its own tests now that restore exists: what this writes is read back by the same app after
 * a reinstall, when the document is the only copy of the history left anywhere.
 */
class ActivityLogSerializerTest {

    private val entries = listOf(
        LogEntry(
            sequence = 1,
            timestampMillis = 1_700_000_100,
            level = LogLevel.WARN,
            category = "trigger",
            message = "refused a tag that has an assignment",
            payload = mapOf("uid" to "7C C7 27 91", "presence" to "left the field"),
        ),
        LogEntry(
            sequence = 0,
            timestampMillis = 1_700_000_000,
            level = LogLevel.INFO,
            category = "action",
            message = "sent intent",
        ),
    )

    @Test
    fun `an entry survives the round trip intact`() {
        assertEquals(entries, ActivityLogSerializer.decode(ActivityLogSerializer.encode(entries)))
    }

    @Test
    fun `an empty history round trips`() {
        assertEquals(emptyList(), ActivityLogSerializer.decode(ActivityLogSerializer.encode(emptyList())))
    }

    /**
     * A document written by a newer version has to stay readable by an older one. The alternative
     * is a phone that refuses its own history because a field it does not care about appeared.
     */
    @Test
    fun `a field this version does not know about is ignored`() {
        val newer = """
            [{"sequence":0,"timestampMillis":1700000000,"level":"INFO","category":"action",
              "message":"sent intent","payload":{},"deviceName":"a later version added this"}]
        """.trimIndent()

        val decoded = ActivityLogSerializer.decode(newer)

        assertEquals(listOf("sent intent"), decoded.map { it.message })
    }

    /** One unreadable field is a poor reason to hand back nothing. */
    @Test
    fun `an unrecognised level degrades to INFO rather than failing the document`() {
        val odd = """
            [{"sequence":0,"timestampMillis":1700000000,"level":"CATASTROPHE","category":"action",
              "message":"sent intent","payload":{}}]
        """.trimIndent()

        assertEquals(LogLevel.INFO, ActivityLogSerializer.decode(odd).single().level)
    }

    @Test
    fun `text that is not a log document is refused rather than silently emptied`() {
        assertTrue(runCatching { ActivityLogSerializer.decode("not json") }.isFailure)
    }
}
