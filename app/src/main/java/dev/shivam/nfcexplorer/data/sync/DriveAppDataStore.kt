package dev.shivam.nfcexplorer.data.sync

import dev.shivam.nfcexplorer.di.IoDispatcher
import dev.shivam.nfcexplorer.domain.sync.CloudStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Drive `appDataFolder`, over the REST API.
 *
 * `appDataFolder` is a per-app hidden space inside the user's own Drive. It is the narrowest thing
 * Google offers for this: the `drive.appdata` scope cannot read or write any of the user's real
 * files, only what this app put there, and uninstalling takes it with it. The data stays in an
 * account the user controls, which is the whole reason this was chosen over hosting a backend.
 *
 * `HttpURLConnection` again, rather than the `google-api-services-drive` client, whose transitive
 * dependency tree is enormous next to the four requests actually needed here.
 */
@Singleton
class DriveAppDataStore @Inject constructor(
    private val tokens: GoogleAccessTokens,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CloudStore {

    override suspend fun read(name: String): Result<String?> = withContext(io) {
        runCatching {
            val token = tokens.current() ?: error("Not signed in to Google Drive")
            val id = findId(token, name) ?: return@runCatching null
            get("$FILES/$id?alt=media", token)
        }
    }

    override suspend fun write(name: String, content: String): Result<Unit> = withContext(io) {
        runCatching {
            val token = tokens.current() ?: error("Not signed in to Google Drive")
            val existing = findId(token, name)
            if (existing == null) create(token, name, content) else update(token, existing, content)
        }
    }

    override suspend fun list(prefix: String): Result<List<String>> = withContext(io) {
        runCatching {
            val token = tokens.current() ?: error("Not signed in to Google Drive")
            val body = get(
                "$FILES?spaces=appDataFolder&pageSize=1000&fields=files(id,name)",
                token,
            )
            json.decodeFromString(FileList.serializer(), body).files
                .map { it.name }
                .filter { it.startsWith(prefix) }
        }
    }

    /**
     * The file id for [name], or null.
     *
     * Queried by name every time rather than cached: the folder is shared with this app on other
     * devices, so a cached id can refer to a file another device has since replaced.
     */
    private fun findId(token: String, name: String): String? {
        val query = URLEncoder.encode("name = '${name.replace("'", "\\'")}'", "UTF-8")
        val body = get("$FILES?spaces=appDataFolder&q=$query&fields=files(id,name)", token)
        return json.decodeFromString(FileList.serializer(), body).files.firstOrNull()?.id
    }

    private fun create(token: String, name: String, content: String) {
        // Multipart: the metadata names the file and puts it in appDataFolder, the second part is
        // the content. A plain media upload cannot carry a parent, so it would land in My Drive.
        val boundary = "nfcx${System.nanoTime()}"
        val metadata = """{"name":"$name","parents":["appDataFolder"]}"""
        val payload = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--")
        }
        send(
            url = "$UPLOAD?uploadType=multipart&fields=id",
            method = "POST",
            token = token,
            contentType = "multipart/related; boundary=$boundary",
            body = payload,
        )
    }

    private fun update(token: String, id: String, content: String) {
        send(
            url = "$UPLOAD/$id?uploadType=media&fields=id",
            method = "PATCH",
            token = token,
            contentType = "application/json",
            body = content,
        )
    }

    private fun get(url: String, token: String): String =
        send(url = url, method = "GET", token = token, contentType = null, body = null)

    private fun send(
        url: String,
        method: String,
        token: String,
        contentType: String?,
        body: String?,
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            // A real PATCH. Android's HttpURLConnection is OkHttp-backed and accepts it; only the
            // desktop JDK refuses. The X-HTTP-Method-Override header this used to send instead was
            // answered by Toggl with a flat 405, so stopping a timer never worked -- the fallback
            // stays only for a runtime that genuinely will not take the verb.
            runCatching { requestMethod = method }.onFailure {
                requestMethod = "POST"
                setRequestProperty("X-HTTP-Method-Override", method)
            }
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
            doInput = true
            if (body != null) {
                doOutput = true
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            }
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                // 401 here almost always means the cached access token expired rather than that
                // access was revoked, and the caller retries once after refreshing.
                error("Drive returned HTTP $code${if (detail.isBlank()) "" else ": ${detail.take(300)}"}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    private data class FileList(@SerialName("files") val files: List<FileDto> = emptyList())

    @Serializable
    private data class FileDto(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
    )

    private companion object {
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val TIMEOUT_MILLIS = 20_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
