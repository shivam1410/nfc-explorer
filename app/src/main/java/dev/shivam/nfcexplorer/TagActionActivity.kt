package dev.shivam.nfcexplorer

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shivam.nfcexplorer.data.nfc.AndroidNfcATransport
import dev.shivam.nfcexplorer.data.nfc.AndroidUltralightTransport
import dev.shivam.nfcexplorer.di.ApplicationScope
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.TagActionDispatch
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.action.TagPresence
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.transport.TagTransport
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Runs a tag's assigned action, then finishes. No UI at all.
 *
 * This activity **must** be exported for NFC dispatch to reach it, which means any app on the device
 * can start it while it runs stored intents — `adb shell am start -n .../TagActionActivity -a
 * android.nfc.action.TECH_DISCOVERED` does exactly that, from an ordinary third-party uid.
 * [TagActionDispatch.shouldAct] is the guard, consulted before anything else happens, and
 * `TagActionDispatchTest` sweeps its three conditions.
 *
 * The load-bearing one is [TagPresence.isLive]: the action string is copyable and a `Tag` parcel is
 * forgeable, but a tag that answers a connection is not. Passing a literal `true` there — as this
 * activity once did, having already null-checked the tag — made the condition restate the null-check
 * and prove nothing.
 *
 * It draws no frame: no theme, no `setContent`, and `finish()` on every path. A tag with no assignment
 * therefore does nothing visible at all, which is the behaviour the user chose — random tags
 * encountered in the world must not pop this app open.
 */
@AndroidEntryPoint
class TagActionActivity : ComponentActivity() {

    @Inject lateinit var repository: TagActionRepository

    @Inject lateinit var performer: ActionPerformer

    @Inject lateinit var logger: SessionLogger

    /**
     * Actions run here, not in `lifecycleScope`.
     *
     * This activity finishes as soon as it has decided what to do, and anything still suspended in
     * its own scope dies with it. Ending a sleep session takes a couple of seconds -- raise the
     * screen, wait, drag -- so running it here is the difference between the tag working and the log
     * reading `JobCancellationException`.
     */
    @Inject @ApplicationScope lateinit var actionScope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tag = extractTag(intent)
        val uid = tag?.id?.let(ByteBlock::copyOf)

        if (tag == null || uid == null) {
            // No tag means this was not a platform dispatch, whatever the action string claimed.
            logger.warn(
                category = CATEGORY,
                message = "trigger invoked without a tag; ignoring",
                payload = mapOf("action" to (intent?.action ?: "none")),
            )
            finish()
            return
        }

        lifecycleScope.launch {
            act(tag, uid)
            finish()
        }
    }

    /**
     * Decides whether this launch may run an action, and runs it.
     *
     * Separate from `onCreate` so the guard, the store lookup and the three outcomes read as one
     * sequence instead of being buried in lifecycle code.
     */
    private suspend fun act(tag: Tag, uid: ByteBlock) {
        // Proven before the store is even consulted, so a caller that cannot produce a live tag learns
        // nothing about what is assigned. On Dispatchers.IO because this is a blocking exchange with
        // the tag, the same as every other read in this app.
        val presence = withContext(Dispatchers.IO) { TagPresence.check(transportFor(tag)) }
        val assignment = repository.find(uid)
        val permitted = TagActionDispatch.shouldAct(
            intentAction = intent?.action,
            presence = presence,
            assignment = assignment,
        )

        when {
            permitted && assignment != null -> {
                logger.info(
                    category = CATEGORY,
                    message = "running assigned action",
                    payload = mapOf("uid" to uid.toString(), "label" to assignment.label),
                )
                // Deliberately not awaited: this activity is about to finish, and awaiting here
                // would put the wait back inside the scope that dies with it.
                actionScope.launch {
                    performer.perform(assignment.action).onFailure { failure ->
                        logger.error(
                            category = CATEGORY,
                            message = "action failed",
                            payload = mapOf(
                                "label" to assignment.label,
                                "exception" to (failure::class.simpleName ?: "Throwable"),
                                "message" to (failure.message ?: ""),
                            ),
                        )
                    }
                }
            }

            // An assignment existed and was refused, so presence failed. Distinguished from the
            // unassigned case and raised to a warning on purpose: this is the one way the presence
            // check can spoil a legitimate tap, and it must be obvious in the log rather than
            // inferable from it.
            assignment != null -> logger.warn(
                category = CATEGORY,
                message = "refused a tag that has an assignment",
                payload = mapOf(
                    "uid" to uid.toString(),
                    "label" to assignment.label,
                    "action" to (intent?.action ?: "none"),
                    "presence" to presenceDetail(presence),
                ),
            )

            // Unassigned tag, or a caller that is not a genuine dispatch. Silent either way.
            else -> logger.info(
                category = CATEGORY,
                message = "no action taken",
                payload = mapOf(
                    "uid" to uid.toString(),
                    "action" to (intent?.action ?: "none"),
                    "presence" to presenceDetail(presence),
                ),
            )
        }
    }

    /**
     * Why the tag did not answer, in a form worth reading in the log.
     *
     * A tap that quietly does nothing is indistinguishable from a broken app, so the reason has to be
     * recoverable afterwards — "left the field" points at how the card was waved, while "no usable
     * technology" or a bare parcel points somewhere else entirely.
     */
    private fun presenceDetail(answer: TagPresence.Answer): String = when (answer) {
        TagPresence.Answer.Live -> "answered"
        is TagPresence.Answer.Absent -> answer.cause
            ?.let { "did not answer: ${it::class.simpleName}: ${it.message}" }
            ?: "no technology this app can open"
    }

    /**
     * A transport to prove the tag answers, or null when it speaks nothing this app can open.
     *
     * Ultralight first because that is the family this app decodes; plain `NfcA` second because the
     * trigger's tech filter admits it, and without the fallback an assignment on such a tag would fail
     * the presence check and silently stop working.
     */
    private fun transportFor(tag: Tag): TagTransport? =
        MifareUltralight.get(tag)?.let(::AndroidUltralightTransport)
            ?: NfcA.get(tag)?.let(::AndroidNfcATransport)

    /**
     * The typed `EXTRA_TAG` accessor arrived in API 33; below that the deprecated form is the only
     * option, and `minSdk` is 26.
     */
    private fun extractTag(intent: Intent?): Tag? = when {
        intent == null -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        else -> {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
    }

    private companion object {
        const val CATEGORY = "trigger"
    }
}
