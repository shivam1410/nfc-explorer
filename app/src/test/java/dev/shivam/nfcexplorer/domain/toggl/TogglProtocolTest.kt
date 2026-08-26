package dev.shivam.nfcexplorer.domain.toggl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.Base64

/**
 * The wire details, which fail unhelpfully when wrong.
 *
 * A swapped Basic-auth pair returns 403 with no hint that the order is the problem, so the ordering
 * is pinned here rather than left to be rediscovered against a live account.
 */
class TogglProtocolTest {

    @Test
    fun `the token is the username and api_token is the password`() {
        val header = TogglProtocol.authHeader("abc123")

        val decoded = String(Base64.getDecoder().decode(header.removePrefix("Basic ")))
        assertEquals("abc123:api_token", decoded)
    }

    @Test
    fun `a blank token is refused rather than sent`() {
        // An empty credential produces a well-formed header that always fails, which is the least
        // debuggable outcome available.
        assertFailsWith<IllegalArgumentException> { TogglProtocol.authHeader("") }
        assertFailsWith<IllegalArgumentException> { TogglProtocol.authHeader("   ") }
    }

    @Test
    fun `paths carry the workspace and entry`() {
        assertEquals("/workspaces/42/time_entries", TogglProtocol.startPath(42))
        assertEquals("/workspaces/42/time_entries/7/stop", TogglProtocol.stopPath(42, 7))
    }

    /** A running entry is denoted by a negative duration equal to minus the start. */
    @Test
    fun `a started entry is marked running by a negative duration`() {
        val body = TogglProtocol.startBody(42, "Deep work", null, 1_700_000_000)

        assertTrue(body.contains("\"duration\":-1700000000"), body)
        assertTrue(body.contains("\"workspace_id\":42"), body)
        assertTrue(body.contains("\"created_with\":\"NfcExplorer\""), body)
    }

    @Test
    fun `a project is included only when given`() {
        assertTrue(TogglProtocol.startBody(1, "x", 99, 10).contains("\"project_id\":99"))
        assertTrue(!TogglProtocol.startBody(1, "x", null, 10).contains("project_id"))
    }

    /** Descriptions are user text and routinely contain quotes; unescaped they corrupt the body. */
    @Test
    fun `quotes and backslashes in a description are escaped`() {
        val body = TogglProtocol.startBody(1, """say "hi" \ now""", null, 10)

        assertTrue(body.contains("""\"hi\""""), body)
        assertTrue(body.contains("""\\"""), body)
    }

    @Test
    fun `the start time is RFC3339 in UTC`() {
        assertEquals("2023-11-14T22:13:20Z", TogglProtocol.isoUtc(1_700_000_000))
    }
}
