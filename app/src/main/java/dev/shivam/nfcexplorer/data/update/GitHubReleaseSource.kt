package dev.shivam.nfcexplorer.data.update

import dev.shivam.nfcexplorer.domain.update.AppRelease
import dev.shivam.nfcexplorer.domain.update.ReleaseSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.shivam.nfcexplorer.di.IoDispatcher
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the newest release from the GitHub API.
 *
 * `HttpURLConnection` rather than a new HTTP dependency: this is one unauthenticated GET against a
 * public endpoint, and adding OkHttp or Ktor to the build for it would cost more than it returns.
 *
 * Uses `/releases` rather than `/releases/latest`, because the latter excludes prereleases and every
 * build published so far has been one — asking the wrong endpoint would report "no releases" forever.
 *
 * Both timeouts are set explicitly. A check that hangs on a captive-portal Wi-Fi would otherwise sit
 * there indefinitely with a spinner on screen.
 */
@Singleton
class GitHubReleaseSource @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReleaseSource {

    override suspend fun latest(): Result<AppRelease?> = withContext(io) {
        runCatching {
            val body = fetch("$API_BASE/repos/$REPO/releases?per_page=10")
            // GitHub returns newest first, so the head is the newest published build.
            json.decodeFromString(ListSerializer, body).firstOrNull()?.toDomain()
        }
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub rejects requests with no User-Agent.
            setRequestProperty("User-Agent", "NfcExplorer")
        }
        try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "GitHub returned HTTP $code" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun ReleaseDto.toDomain() = AppRelease(
        tag = tagName,
        name = name?.takeIf { it.isNotBlank() } ?: tagName,
        pageUrl = htmlUrl,
        apkUrl = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.downloadUrl,
        prerelease = prerelease,
    )

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("name") val name: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        @SerialName("prerelease") val prerelease: Boolean = false,
        @SerialName("assets") val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val REPO = "shivam1410/nfc-explorer"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 10_000

        val json = Json { ignoreUnknownKeys = true }
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(ReleaseDto.serializer())
    }
}
