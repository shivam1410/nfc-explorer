package dev.shivam.nfcexplorer.data.secret

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.secret.SecretStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secrets encrypted with a key held in the Android Keystore.
 *
 * The important property is where the key is *not*: it never leaves the secure hardware, cannot be
 * read by this app or any other, and is destroyed if the app is uninstalled. That is what makes this
 * meaningfully different from writing the token to preferences, or to an NFC tag -- a Mifare
 * Ultralight page has no read authentication at all, so anything written there is readable by any
 * phone that touches the card, silently and without trace.
 *
 * The same property has a consequence worth stating rather than discovering: because the key stays on
 * this device, an encrypted token cannot be restored onto a new phone from any backup. The token is
 * entered once per device. That is the cost of it being safe.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecretStore {

    /**
     * Created lazily and cached.
     *
     * Keystore initialisation is slow enough to be worth doing once, and it happens on whichever
     * thread first needs a secret rather than at startup, so a cold launch does not pay for it.
     */
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotBlank() }

    override fun has(key: String): Boolean = read(key) != null

    override fun write(key: String, value: String) {
        prefs.edit().putString(key, value.trim()).apply()
    }

    override fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val FILE_NAME = "nfc-explorer-secrets"
    }
}
