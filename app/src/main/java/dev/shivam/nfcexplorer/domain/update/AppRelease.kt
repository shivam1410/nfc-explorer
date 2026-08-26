package dev.shivam.nfcexplorer.domain.update

/** A release published on GitHub. */
data class AppRelease(
    val tag: String,
    val name: String,
    val pageUrl: String,
    val apkUrl: String?,
    val prerelease: Boolean,
) {
    init {
        require(tag.isNotBlank()) { "tag must not be blank" }
    }
}

/** What a check found. */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus

    /** Running the newest published build. */
    data class UpToDate(val current: String) : UpdateStatus

    data class Available(val current: String, val release: AppRelease) : UpdateStatus

    /**
     * The check could not be completed.
     *
     * A distinct state rather than silently showing "up to date" — claiming you are current when
     * nobody managed to ask is the kind of quiet lie this codebase avoids elsewhere.
     */
    data class Failed(val reason: String) : UpdateStatus
}

/** Where releases are read from. Implemented in `data/`. */
fun interface ReleaseSource {
    suspend fun latest(): Result<AppRelease?>
}

/** The version this build reports. Implemented in `data/`, because only the platform knows. */
fun interface InstalledVersion {
    fun name(): String
}
