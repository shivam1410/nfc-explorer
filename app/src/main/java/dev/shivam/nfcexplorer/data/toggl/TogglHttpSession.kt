package dev.shivam.nfcexplorer.data.toggl

import dev.shivam.nfcexplorer.di.IoDispatcher
import dev.shivam.nfcexplorer.domain.secret.SecretStore
import dev.shivam.nfcexplorer.domain.toggl.TogglAccount
import dev.shivam.nfcexplorer.domain.toggl.TogglOutcome
import dev.shivam.nfcexplorer.domain.toggl.TogglConfig
import dev.shivam.nfcexplorer.domain.toggl.TogglProtocol
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Toggl over HTTP.
 *
 * The toggle asks the server what is running rather than remembering it, which is the same lesson the
 * Sleep Cycle work produced -- except here the answer is authoritative rather than inferred from a
 * notification. A timer stopped from the Toggl web app is simply not running the next time this asks.
 *
 * `HttpURLConnection` for the same reason as the update check: a handful of unauthenticated-shaped
 * requests do not justify pulling in an HTTP stack.
 */
@Singleton
class TogglHttpSession @Inject constructor(
    private val secrets: SecretStore,
    private val config: TogglConfig,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TogglSession {

    override suspend fun toggle(
        description: String,
        tags: List<String>,
        projectId: Long?,
    ): Result<TogglOutcome> = withContext(io) {
        runCatching {
            val token = secrets.read(SecretStore.TOGGL_TOKEN)
                ?: error("No Toggl API token saved. Add one in Settings.")
            val auth = TogglProtocol.authHeader(token)
            // Discovered from the token on first use and cached, so the user never types it.
            val workspaceId = config.workspaceId()
                ?: fetchAccount(auth).workspaceId
                ?: error("Your Toggl account reports no default workspace.")

            val current = currentEntry(auth)
            if (current != null && current.id != null) {
                stop(auth, current.workspaceId ?: workspaceId, current.id)
                TogglOutcome.Stopped(current.id)
            } else {
                start(auth, workspaceId, description, tags, projectId)
                TogglOutcome.Started(description)
            }
        }
    }

    override suspend fun account(): Result<TogglAccount> = withContext(io) {
        runCatching {
            val token = secrets.read(SecretStore.TOGGL_TOKEN)
                ?: error("No Toggl API token saved.")
            fetchAccount(TogglProtocol.authHeader(token))
        }
    }

    /** Reads `/me` and remembers the default workspace, so this happens once rather than per tap. */
    private fun fetchAccount(auth: String): TogglAccount {
        val body = request("GET", TogglProtocol.ME_PATH, auth, null)
        val dto = json.decodeFromString(MeDto.serializer(), body)
        dto.defaultWorkspaceId?.let(config::setWorkspaceId)
        return TogglAccount(
            fullName = dto.fullName?.takeIf { it.isNotBlank() } ?: dto.email.orEmpty(),
            workspaceId = dto.defaultWorkspaceId,
        )
    }

    /** Null when nothing is running -- Toggl answers `null` rather than 404. */
    private fun currentEntry(auth: String): EntryDto? {
        val body = request("GET", TogglProtocol.CURRENT_ENTRY_PATH, auth, null)
        if (body.isBlank() || body.trim() == "null") return null
        return json.decodeFromString(EntryDto.serializer(), body)
    }

    private fun start(
        auth: String,
        workspaceId: Long,
        description: String,
        tags: List<String>,
        projectId: Long?,
    ) {
        val nowSeconds = System.currentTimeMillis() / 1_000
        val payload = TogglProtocol.startBody(workspaceId, description, tags, projectId, nowSeconds)
        request("POST", TogglProtocol.startPath(workspaceId), auth, payload)
    }

    private fun stop(auth: String, workspaceId: Long, entryId: Long) {
        request("PATCH", TogglProtocol.stopPath(workspaceId, entryId), auth, null)
    }

    private fun request(method: String, path: String, auth: String, body: String?): String {
        val url = URL(TogglProtocol.BASE_URL + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            // HttpURLConnection refuses PATCH outright; the override header is how Toggl and most
            // servers accept it over a POST.
            if (method == "PATCH") {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                requestMethod = method
            }
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Authorization", auth)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", TogglProtocol.USER_AGENT)
            doInput = true
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // The error stream carries Toggl's own explanation; the status code alone rarely
                // distinguishes a bad token from a bad workspace id.
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Toggl returned HTTP $code${if (detail.isBlank()) "" else ": ${detail.take(200)}"}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    private data class MeDto(
        @SerialName("fullname") val fullName: String? = null,
        @SerialName("email") val email: String? = null,
        @SerialName("default_workspace_id") val defaultWorkspaceId: Long? = null,
    )

    @Serializable
    private data class EntryDto(
        @SerialName("id") val id: Long? = null,
        @SerialName("workspace_id") val workspaceId: Long? = null,
        @SerialName("description") val description: String? = null,
    )

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
