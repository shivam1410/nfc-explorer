package dev.shivam.nfcexplorer.data.sync

import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The outcome of asking for a Drive access token. */
sealed interface Authorization {

    data class Token(val accessToken: String) : Authorization

    /**
     * The user has not granted the scope yet. [pendingIntent] shows Google's own consent screen.
     *
     * Surfaced rather than launched here: only an Activity can start it, and consent is the user's
     * decision to make in Google's UI, not something this app should conjure from a background call.
     */
    data class NeedsConsent(val pendingIntent: PendingIntent) : Authorization

    data class Failed(val reason: String) : Authorization
}

/**
 * Obtains OAuth access tokens for the Drive appData scope.
 *
 * Uses the Identity Authorization API, which is the current path and needs no client ID in the app:
 * Google matches the request against the OAuth client registered for this package name and signing
 * certificate. That also means it fails with a bare `DEVELOPER_ERROR` (code 10) when no such client
 * exists, or when the build is signed with a certificate the client was not registered for — a debug
 * build and a release build need separate registrations.
 *
 * No token caching here. Play Services already caches and refreshes, and a token cached by this app
 * would be the one thing standing between a revoked grant and a confusing 401 much later.
 */
@Singleton
class GoogleAccessTokens @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessTokens {

    /** A token if the scope is already granted, otherwise null. Never shows UI. */
    suspend fun current(): String? = (authorize() as? Authorization.Token)?.accessToken

    override suspend fun authorize(): Authorization = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (!continuation.isActive) return@addOnSuccessListener
                val pending = result.pendingIntent
                continuation.resume(
                    when {
                        result.hasResolution() && pending != null -> Authorization.NeedsConsent(pending)
                        result.accessToken != null ->
                            Authorization.Token(requireNotNull(result.accessToken))
                        else -> Authorization.Failed("Google returned no access token")
                    },
                )
            }
            .addOnFailureListener { failure ->
                if (!continuation.isActive) return@addOnFailureListener
                continuation.resume(
                    Authorization.Failed(
                        "${failure::class.simpleName}: ${failure.message}",
                    ),
                )
            }
    }

    private companion object {
        /**
         * The narrowest Drive scope there is: a hidden folder this app owns. It grants no access to
         * anything else in the user's Drive, and cannot read their real files.
         */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

/**
 * Obtains authorization for the Drive scope.
 *
 * Lives in `data/` rather than `domain/` because [Authorization.NeedsConsent] carries a
 * `PendingIntent`, and `domain/` may not import Android. The seam still earns its place: it is what
 * lets the settings view model be tested without Play Services.
 */
fun interface AccessTokens {
    suspend fun authorize(): Authorization
}
