package dev.shivam.nfcexplorer.data.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Bundle
import dev.shivam.nfcexplorer.domain.repository.TagHandle
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/** Whether this device can do NFC at all, and whether it is switched on. */
sealed interface NfcAvailability {
    data object Available : NfcAvailability

    /** Adapter exists but is switched off. Recoverable by the user in system settings. */
    data object Disabled : NfcAvailability

    /** No NFC hardware. Terminal — there is nothing to offer the user. */
    data object Unsupported : NfcAvailability
}

/**
 * Exposes tag discovery as a [Flow].
 *
 * Uses reader mode rather than foreground dispatch, which matters for more than modernity:
 * [NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK] stops the platform performing its own NDEF probe on
 * discovery. That probe both disturbs the tag before this app gets to it and is the very
 * behaviour that makes NDEF-oriented apps report a non-NDEF tag as unsupported. A hotel card
 * with proprietary data in its user pages is exactly that case.
 *
 * `onTagDiscovered` is invoked on a binder thread, not the main thread, so consumers must not
 * assume otherwise. The flow only emits handles; all I/O happens in the repository on the IO
 * dispatcher.
 */
@Singleton
class NfcReaderModeController @Inject constructor() {

    fun availability(activity: Activity): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return NfcAvailability.Unsupported
        return if (adapter.isEnabled) NfcAvailability.Available else NfcAvailability.Disabled
    }

    /**
     * Enables reader mode for as long as the returned flow is collected, and disables it on
     * cancellation.
     *
     * The flow is [conflate]d: if taps arrive faster than the pipeline drains, the newest tag is
     * the one the user is actually holding against the phone, and an older handle is stale anyway
     * because its tag has already left the field.
     */
    fun tagHandles(activity: Activity): Flow<TagHandle> = callbackFlow {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) {
            close()
            return@callbackFlow
        }

        val callback = NfcAdapter.ReaderCallback { tag ->
            // Binder thread. trySend never blocks it.
            trySend(AndroidTagHandle(tag))
        }

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS)
        }

        adapter.enableReaderMode(activity, callback, READER_FLAGS, extras)

        awaitClose { adapter.disableReaderMode(activity) }
    }.conflate()

    private companion object {
        /**
         * All four RF technologies, no platform NDEF probe, and no discovery sound.
         *
         * SKIP_NDEF_CHECK is the important one: it leaves the tag untouched until this app
         * decides what to do with it.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        /**
         * Longer than the default so a hand-held card that shifts slightly mid-dump is not
         * reported as lost. Costs a little latency on genuine removal.
         */
        const val PRESENCE_CHECK_DELAY_MS = 250
    }
}
