package dev.shivam.nfcexplorer.data.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.action.IntentSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Dispatches drag gestures, for controls that no intent can reach.
 *
 * An accessibility service is the only way an app may inject touches into another app, so this is the
 * price of Sleep Cycle's slide-to-stop. It is declared with the narrowest capability that still
 * works — it requests gesture dispatch and nothing else, and deliberately does not ask to read window
 * content beyond the frontmost package name it needs for the safety check.
 *
 * Like the notification listener, the platform constructs this, so the bound instance is published
 * through the companion rather than injected.
 */
class GestureDispatchService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        bound = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        bound = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bound = null
        super.onDestroy()
    }

    /** Nothing is observed. The service exists to *send* gestures, not to watch the user. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** The package owning the frontmost window, or null when it cannot be determined. */
    internal fun foregroundPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /**
     * Runs [spec] as a chain of continued strokes.
     *
     * Not one smooth swipe. A single interpolated stroke of exactly this length was tested twice
     * against Sleep Cycle's slider and did nothing; what works is a press that dwells briefly, then
     * moves in discrete increments. Each continuation must be dispatched only after the previous one
     * completes, which is why this awaits every segment rather than queueing them.
     */
    internal suspend fun drag(spec: IntentSpec.Drag, width: Int, height: Int): Result<Unit> {
        val startX = spec.startXRatio * width
        val startY = spec.startYRatio * height
        val endX = spec.endXRatio * width
        val endY = spec.endYRatio * height

        // A stroke needs a path with some length; a point-to-itself path is rejected outright.
        val hold = Path().apply {
            moveTo(startX, startY)
            lineTo(startX, startY - HOLD_NUDGE_PX)
        }

        var stroke = GestureDescription.StrokeDescription(hold, 0, spec.holdMillis, true)
        if (!dispatch(stroke)) return Result.failure(IllegalStateException("gesture rejected at press"))

        val segment = (spec.travelMillis / spec.steps).coerceAtLeast(MIN_SEGMENT_MILLIS)
        var fromX = startX
        var fromY = startY - HOLD_NUDGE_PX

        for (step in 1..spec.steps) {
            val fraction = step.toFloat() / spec.steps
            val toX = startX + (endX - startX) * fraction
            val toY = startY + (endY - startY) * fraction
            val path = Path().apply {
                moveTo(fromX, fromY)
                lineTo(toX, toY)
            }
            val willContinue = step < spec.steps
            stroke = stroke.continueStroke(path, 0, segment, willContinue)
            if (!dispatch(stroke)) {
                return Result.failure(IllegalStateException("gesture rejected at step $step"))
            }
            fromX = toX
            fromY = toY
        }
        return Result.success(Unit)
    }

    /** Dispatches one stroke and waits for the platform to say whether it landed. */
    private suspend fun dispatch(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val accepted = runCatching { dispatchGesture(gesture, callback, null) }.getOrDefault(false)
            // dispatchGesture returning false means the callback will never fire.
            if (!accepted && continuation.isActive) continuation.resume(false)
        }

    companion object {
        @Volatile
        internal var bound: GestureDispatchService? = null

        /** One pixel of travel, so the initial press is a legal path rather than an empty one. */
        private const val HOLD_NUDGE_PX = 1f

        private const val MIN_SEGMENT_MILLIS = 16L
    }
}

/**
 * Performs [IntentSpec.Drag] against the bound [GestureDispatchService].
 *
 * Every refusal is a distinct failure message rather than a silent no-op, because a gesture that does
 * nothing looks exactly like a broken app from the outside — and during development that is precisely
 * what happened when another app quietly held the foreground.
 */
@Singleton
class ScreenGestureDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun perform(spec: IntentSpec.Drag): Result<Unit> {
        val service = GestureDispatchService.bound
            ?: return Result.failure(
                IllegalStateException("Accessibility service is not enabled for NFC Explorer"),
            )

        spec.requireForegroundPackage?.let { required ->
            val actual = awaitForeground(service, required, spec.awaitForegroundMillis)
            if (actual != required) {
                return Result.failure(
                    IllegalStateException(
                        "expected $required in the foreground but found ${actual ?: "nothing"}",
                    ),
                )
            }
        }

        val metrics = context.resources.displayMetrics
        return service.drag(spec, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Polls until [required] is frontmost, or the budget runs out.
     *
     * Two things make this necessary and they pull in opposite directions. The screen this drag
     * targets is usually being launched by the step immediately before it, so it may not be up yet;
     * and another app can take the foreground at any moment -- during development a tracker app did
     * exactly that, three times, absorbing drags aimed elsewhere. Polling handles the first without
     * making the second worse: it never drags at the wrong app, it just waits a little for the right
     * one.
     *
     * Returns whatever is frontmost when it gives up, so the caller can name it in the failure.
     */
    private suspend fun awaitForeground(
        service: GestureDispatchService,
        required: String,
        budgetMillis: Long,
    ): String? {
        var waited = 0L
        var actual = service.foregroundPackage()
        while (actual != required && waited < budgetMillis) {
            delay(FOREGROUND_POLL_MILLIS)
            waited += FOREGROUND_POLL_MILLIS
            actual = service.foregroundPackage()
        }
        return actual
    }

    private companion object {
        const val FOREGROUND_POLL_MILLIS = 150L
    }
}
