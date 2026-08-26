package dev.shivam.nfcexplorer.domain.toggl

import java.util.Base64

/**
 * The wire details of Toggl Track's v9 API, as pure functions.
 *
 * Separated from the HTTP call so the parts that are easy to get subtly wrong — and that fail with an
 * unhelpful 403 rather than an error you can read — are unit-tested: the auth header in particular.
 *
 * Toggl's Basic auth is the classic trap. The **token** is the username and the literal string
 * `api_token` is the password, not the other way round, and swapping them produces an
 * indistinguishable authentication failure.
 */
object TogglProtocol {

    const val BASE_URL = "https://api.track.toggl.com/api/v9"

    /** Where the running entry lives, or `null` in the body when nothing is running. */
    const val CURRENT_ENTRY_PATH = "/me/time_entries/current"

    /** Identifies this client to Toggl, which is good manners and aids their rate limiting. */
    const val USER_AGENT = "NfcExplorer"

    /**
     * `Basic base64(token:api_token)`.
     *
     * @throws IllegalArgumentException when [token] is blank, because an empty credential produces a
     *   perfectly well-formed header that always fails.
     */
    fun authHeader(token: String): String {
        require(token.isNotBlank()) { "token must not be blank" }
        val encoded = Base64.getEncoder().encodeToString("$token:api_token".toByteArray())
        return "Basic $encoded"
    }

    fun startPath(workspaceId: Long): String = "/workspaces/$workspaceId/time_entries"

    fun stopPath(workspaceId: Long, entryId: Long): String =
        "/workspaces/$workspaceId/time_entries/$entryId/stop"

    /**
     * The body that starts an entry.
     *
     * `duration` is negative and `start` is now, which is how Toggl denotes a running entry: a
     * running entry's duration is the negative of its start in epoch seconds. `created_with` is
     * required by the API.
     *
     * Hand-built rather than serialised from a DTO because it is four fields and lives next to the
     * documentation that explains why each one is shaped this way.
     */
    fun startBody(
        workspaceId: Long,
        description: String,
        projectId: Long?,
        startEpochSeconds: Long,
    ): String = buildString {
        append("{")
        append("\"created_with\":\"").append(USER_AGENT).append("\",")
        append("\"workspace_id\":").append(workspaceId).append(",")
        append("\"description\":\"").append(escape(description)).append("\",")
        projectId?.let { append("\"project_id\":").append(it).append(",") }
        append("\"start\":\"").append(isoUtc(startEpochSeconds)).append("\",")
        append("\"duration\":").append(-startEpochSeconds)
        append("}")
    }

    /** RFC3339 in UTC, which is the only start format the API documents. */
    fun isoUtc(epochSeconds: Long): String =
        java.time.Instant.ofEpochSecond(epochSeconds).toString()

    /** Minimal JSON string escaping — descriptions are user text and may contain quotes. */
    private fun escape(raw: String): String = buildString {
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}

/** What a toggle did, so the log and the UI can say something true. */
sealed interface TogglOutcome {
    data class Started(val description: String) : TogglOutcome
    data class Stopped(val entryId: Long) : TogglOutcome
}

/** Talks to Toggl. Implemented in `data/`. */
interface TogglSession {
    /** Stops the running entry if there is one, otherwise starts a new one. */
    suspend fun toggle(workspaceId: Long, description: String, projectId: Long?): Result<TogglOutcome>
}
