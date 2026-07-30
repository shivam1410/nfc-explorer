package dev.shivam.nfcexplorer

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.shivam.nfcexplorer.data.action.TagActionRunner
import dev.shivam.nfcexplorer.domain.action.TagActionDispatch
import dev.shivam.nfcexplorer.domain.action.TagActionRepository
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.logging.SessionLogger
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Runs a tag's assigned action, then finishes. No UI at all.
 *
 * This activity **must** be exported for NFC dispatch to reach it, which means any app on the device
 * can start it while it runs stored intents. [TagActionDispatch.shouldAct] is the guard, and it is
 * consulted before anything else happens. See `TagActionDispatchTest` for the swept proof that all
 * three of its conditions are load-bearing.
 *
 * It draws no frame: no theme, no `setContent`, and `finish()` on every path. A tag with no assignment
 * therefore does nothing visible at all, which is the behaviour the user chose — random tags
 * encountered in the world must not pop this app open.
 */
@AndroidEntryPoint
class TagActionActivity : ComponentActivity() {

    @Inject lateinit var repository: TagActionRepository

    @Inject lateinit var runner: TagActionRunner

    @Inject lateinit var logger: SessionLogger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tag = extractTag(intent)
        val uid = tag?.id?.let(ByteBlock::copyOf)

        if (uid == null) {
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
            val assignment = repository.find(uid)
            val permitted = TagActionDispatch.shouldAct(
                intentAction = intent?.action,
                hasTagExtra = true,
                assignment = assignment,
            )

            if (permitted && assignment != null) {
                logger.info(
                    category = CATEGORY,
                    message = "running assigned action",
                    payload = mapOf("uid" to uid.toString(), "label" to assignment.label),
                )
                runner.run(assignment.action)
                    .onFailure { failure ->
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
            } else {
                // Unassigned tag, or a caller that is not a genuine dispatch. Silent either way.
                logger.info(
                    category = CATEGORY,
                    message = "no action taken",
                    payload = mapOf(
                        "uid" to uid.toString(),
                        "action" to (intent?.action ?: "none"),
                        "assigned" to (assignment != null).toString(),
                    ),
                )
            }
            finish()
        }
    }

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
