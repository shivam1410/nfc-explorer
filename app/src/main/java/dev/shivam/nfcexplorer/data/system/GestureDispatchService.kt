package dev.shivam.nfcexplorer.data.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
     * Presses the first control matching any of [viewIds], or failing that [contentDescription].
     *
     * Ids first and in order, because they are never translated where a description is; several are
     * accepted because a view id carries its package, so one app shipping under two package names
     * has two ids for the same button. Walks up from the matched node to the nearest clickable
     * ancestor, because the thing carrying the label is frequently an icon inside the button rather
     * than the button itself.
     *
     * The id route needs `flagReportViewIds` on the service, without which the platform reports no
     * ids at all and every lookup here quietly returns nothing -- see the service's XML.
     */
    internal fun clickNode(viewIds: List<String>, contentDescription: String?): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false

        // Every candidate in priority order, and the first that is actually pressable wins.
        //
        // Taking the first *match* instead was a trap, and a live one: enabling flagReportViewIds
        // made the id route resolve for the first time, so an id landing on a node with no clickable
        // ancestor would have returned false and never tried the description -- breaking the
        // fallback that every send had in fact been going through until now. Lazy, so the extra
        // tree walk only happens when the ids do not produce something pressable.
        return sequence {
            viewIds.forEach { yield(findById(root, it)) }
            yield(contentDescription?.let { findByDescription(root, it) })
        }
            .filterNotNull()
            .mapNotNull(::clickable)
            .firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
        runCatching { root.findAccessibilityNodeInfosByViewId(viewId).firstOrNull() }.getOrNull()

    private fun findByDescription(
        root: AccessibilityNodeInfo,
        description: String,
    ): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString().equals(description, ignoreCase = true)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            findByDescription(child, description)?.let { return it }
        }
        return null
    }

    /** The node itself if it handles clicks, otherwise its nearest ancestor that does. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

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

    /** Presses a control, once the app that owns it is actually in front. */
    suspend fun tap(spec: IntentSpec.TapNode): Result<Unit> {
        val service = GestureDispatchService.bound
            ?: return Result.failure(
                IllegalStateException(ACCESSIBILITY_OFF),
            )

        if (spec.requireForegroundPackages.isNotEmpty()) {
            val actual = awaitForeground(
                service,
                spec.requireForegroundPackages,
                spec.awaitForegroundMillis,
            )
            if (actual == null || actual !in spec.requireForegroundPackages) {
                return Result.failure(
                    IllegalStateException(
                        "expected ${spec.requireForegroundPackages.joinToString(" or ")} in the " +
                            "foreground but found ${actual ?: "nothing"}",
                    ),
                )
            }
        }

        // The control may not be drawn the instant its app reaches the foreground, so this polls
        // for the node rather than giving up on the first look.
        var waited = 0L
        while (waited <= spec.awaitForegroundMillis) {
            if (service.clickNode(spec.viewIds, spec.contentDescription)) return Result.success(Unit)
            delay(FOREGROUND_POLL_MILLIS)
            waited += FOREGROUND_POLL_MILLIS
        }
        val target = spec.viewIds.joinToString(" or ").ifEmpty { spec.contentDescription.orEmpty() }
        return Result.failure(IllegalStateException("no control matching $target appeared"))
    }

    suspend fun perform(spec: IntentSpec.Drag): Result<Unit> {
        val service = GestureDispatchService.bound
            ?: return Result.failure(IllegalStateException(ACCESSIBILITY_OFF))

        spec.requireForegroundPackage?.let { required ->
            val actual = awaitForeground(service, setOf(required), spec.awaitForegroundMillis)
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
        required: Set<String>,
        budgetMillis: Long,
    ): String? {
        var waited = 0L
        var actual = service.foregroundPackage()
        while (actual !in required && waited < budgetMillis) {
            delay(FOREGROUND_POLL_MILLIS)
            waited += FOREGROUND_POLL_MILLIS
            actual = service.foregroundPackage()
        }
        return actual
    }

    private companion object {
        const val FOREGROUND_POLL_MILLIS = 150L

        /**
         * Named once, because this is the failure the user actually hits.
         *
         * The grant is revoked by any reinstall -- including the app's own in-app update -- and
         * nothing about a silent tap says which of the many possible reasons applied.
         */
        const val ACCESSIBILITY_OFF = "Accessibility service is not enabled for NFC Explorer"
    }
}
