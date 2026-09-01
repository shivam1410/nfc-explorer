package dev.shivam.nfcexplorer.data.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.feedback.FeedbackAnnouncer
import dev.shivam.nfcexplorer.domain.feedback.FeedbackSettings
import dev.shivam.nfcexplorer.domain.feedback.FeedbackVolume
import dev.shivam.nfcexplorer.domain.feedback.TapFailure
import dev.shivam.nfcexplorer.domain.feedback.TapOutcome
import dev.shivam.nfcexplorer.logging.SessionLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Says what a tap did: a toast, and a tone the user chose.
 *
 * ## What this cannot do
 *
 * It cannot silence Android's own NFC discovery sound. That is played by the platform's `NfcService`
 * when it dispatches the tag, before this app's process exists, so there is nothing here to
 * intercept. `FLAG_READER_NO_PLATFORM_SOUNDS` covers foreground reader mode only, which is why
 * scanning inside the app is silent and tapping a trigger is not. The platform beep follows the
 * device's notification volume; [FeedbackVolume] applies only to the tone this class plays.
 *
 * ## Why the application context, always
 *
 * `TagActionActivity` finishes the instant it has decided what to do, and an action that fails does
 * so afterwards, from `ApplicationScope`. A toast tied to a finishing activity's context is the
 * classic way to lose one, so this never takes an `Activity`.
 *
 * ## Why `MediaPlayer` rather than `Ringtone`
 *
 * `Ringtone.setVolume` arrived in API 28 and `minSdk` is 26. A `Ringtone` implementation would leave
 * the volume slider silently doing nothing on the two oldest supported releases — a setting that
 * appears to work and does not, which is worse than no setting.
 *
 * Not unit-tested, consistent with `TagActionRunner` and ADR 0001: everything worth testing was
 * hoisted into `domain/feedback`, and what remains cannot run without a device.
 */
@Singleton
class AndroidFeedbackAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: FeedbackSettings,
    private val logger: SessionLogger,
) : FeedbackAnnouncer {

    /** Toasts and `MediaPlayer` both want a Looper, and callers arrive on whatever thread they like. */
    private val main = Handler(Looper.getMainLooper())

    override fun announce(outcome: TapOutcome) {
        val message = messageFor(outcome) ?: return
        val tone = toneFor(outcome)

        main.post {
            if (settings.toastsEnabled()) {
                Toast.makeText(context, message, lengthFor(outcome)).show()
            }
            tone?.let(::play)
        }
    }

    /**
     * The toast text, or null when there is nothing to say.
     *
     * [TapOutcome.Ignored] returning null is what keeps an unassigned tag completely silent — and it
     * returns before any setting is even read, so the quiet path costs nothing.
     */
    private fun messageFor(outcome: TapOutcome): String? = when (outcome) {
        TapOutcome.Ignored -> null
        is TapOutcome.Ran -> context.getString(R.string.feedback_toast_ran, outcome.label, outcome.uidKey)
        is TapOutcome.Failed ->
            context.getString(R.string.feedback_toast_failed, outcome.label, reasonFor(outcome))
    }

    /**
     * A failure code turned into words.
     *
     * This is the `ui/labels/Labels.kt` mapping in a class that is not a composable: the domain
     * carries codes so it stays free of translatable prose, and this is where the two meet.
     * [TapFailure.ACTION_FAILED] is the exception summary, which is diagnostic text rather than
     * something to translate — an action that fails is a configuration the user has to go and fix.
     */
    private fun reasonFor(failed: TapOutcome.Failed): String = when (failed.failure) {
        TapFailure.CARD_LEFT_FIELD -> context.getString(R.string.feedback_reason_left_field)
        TapFailure.CARD_NOT_READABLE -> context.getString(R.string.feedback_reason_unreadable)
        TapFailure.ACTION_FAILED -> failed.detail.orEmpty()
    }

    /** A failure carries a reason worth reading; a success is one line the user already expected. */
    private fun lengthFor(outcome: TapOutcome): Int =
        if (outcome is TapOutcome.Failed) Toast.LENGTH_LONG else Toast.LENGTH_SHORT

    private fun toneFor(outcome: TapOutcome): String? = when (outcome) {
        TapOutcome.Ignored -> null
        is TapOutcome.Ran -> settings.ranTone()
        is TapOutcome.Failed -> settings.failedTone()
    }

    /**
     * Plays [uri] once at the configured volume, releasing the player on every terminal path.
     *
     * `prepareAsync` rather than `prepare`: this runs on the main thread, on a tap, and a tone
     * sitting on slow storage would block it.
     *
     * A tone whose file has been deleted, or which lives on media that is no longer mounted, throws
     * from `setDataSource`. That is expected rather than exceptional — a content URI is a reference
     * to something the user can remove at any time — so it costs silence and a log line, never the
     * tap. Logged rather than swallowed, because a tone that stopped working with no trace is
     * indistinguishable from a broken setting.
     */
    private fun play(uri: String) {
        val gain = FeedbackVolume.gain(settings.volumePercent())
        if (gain == 0f) return

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setDataSource(context, Uri.parse(uri))
            player.setVolume(gain, gain)
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { failing, what, extra ->
                failing.release()
                logger.warn(
                    category = CATEGORY,
                    message = "tap tone failed to play",
                    payload = mapOf("what" to what.toString(), "extra" to extra.toString()),
                )
                true
            }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        } catch (failure: RuntimeException) {
            // setDataSource throws IllegalArgumentException / IllegalStateException / SecurityException
            // for a URI that no longer resolves or was never readable. All three are RuntimeException.
            player.release()
            logger.warn(
                category = CATEGORY,
                message = "tap tone could not be opened; falling back to silence",
                payload = mapOf(
                    "exception" to (failure::class.simpleName ?: "RuntimeException"),
                    "message" to (failure.message ?: ""),
                ),
            )
        } catch (failure: java.io.IOException) {
            player.release()
            logger.warn(
                category = CATEGORY,
                message = "tap tone could not be read; falling back to silence",
                payload = mapOf("message" to (failure.message ?: "")),
            )
        }
    }

    private companion object {
        const val CATEGORY = "feedback"
    }
}
