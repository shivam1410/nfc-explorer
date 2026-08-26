package dev.shivam.nfcexplorer.data.action

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.data.system.ScreenGestureDispatcher
import dev.shivam.nfcexplorer.domain.action.ActionPerformer
import dev.shivam.nfcexplorer.domain.action.ActionResolver
import dev.shivam.nfcexplorer.domain.action.IntentSpec
import dev.shivam.nfcexplorer.domain.action.IntentSpecMapper
import dev.shivam.nfcexplorer.domain.action.NotificationProbe
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.toggl.TogglSession
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs an action.
 *
 * Deliberately thin: [ActionResolver] decides *which* action a composite collapses to and
 * [IntentSpecMapper] decides *what* to fire — both pure and unit-tested — while this class only turns
 * a spec into a platform call. Same split as the transport adapters: the logic is above the seam, the
 * delegation below it, verified on device.
 *
 * Returns [Result] rather than throwing. This runs from an activity with no UI, triggered by a tap, so
 * a missing app, a refused gesture or a withheld permission has to reach the log rather than surface
 * as a crash the user cannot see the cause of.
 */
@Singleton
class TagActionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: NotificationProbe,
    private val gestures: ScreenGestureDispatcher,
    private val toggl: TogglSession,
) : ActionPerformer {

    override suspend fun perform(action: TagAction): Result<Unit> =
        when (val resolution = ActionResolver.resolve(action, notifications)) {
            // The state a toggle depends on could not be read. Doing nothing and saying why beats
            // guessing, because the wrong guess ends a night's recording or starts a second session.
            is ActionResolver.Resolution.Refused ->
                Result.failure(IllegalStateException(resolution.reason))

            is ActionResolver.Resolution.Perform ->
                run(IntentSpecMapper.map(resolution.leaf))
        }

    private suspend fun run(spec: IntentSpec): Result<Unit> = runCatching {
        when (spec) {
            is IntentSpec.LaunchPackage -> launch(spec.packageName)
            is IntentSpec.ActivityIntent -> startActivity(spec)
            is IntentSpec.MediaKeyEvent -> dispatchMediaKey(spec.keyCode)
            is IntentSpec.Drag -> gestures.perform(spec).getOrThrow()
            is IntentSpec.TapNode -> gestures.tap(spec).getOrThrow()
            is IntentSpec.TogglTimer ->
                toggl.toggle(spec.description, spec.tags, spec.projectId).getOrThrow()
            is IntentSpec.Sequence -> runSequence(spec)
        }
        Unit
    }

    /**
     * Runs each step in order, stopping at the first failure.
     *
     * Stopping matters. The sequence that ends a sleep session is "raise the screen, then drag"; if
     * raising the screen fails there is nothing under the finger, and carrying on would drag across
     * whatever app is actually there.
     */
    private suspend fun runSequence(spec: IntentSpec.Sequence) {
        spec.specs.forEachIndexed { index, step ->
            if (index > 0) delay(spec.gapMillis)
            run(step).getOrThrow()
        }
    }

    private fun launch(packageName: String) {
        val intent = checkNotNull(context.packageManager.getLaunchIntentForPackage(packageName)) {
            "no launchable activity for $packageName"
        }
        context.startActivity(intent.newTask())
    }

    private fun startActivity(spec: IntentSpec.ActivityIntent) {
        val intent = Intent(spec.action).apply {
            spec.uri?.let { data = Uri.parse(it) }
            spec.extras.forEach { (key, value) -> putExtra(key, value) }
        }
        context.startActivity(intent.newTask())
    }

    /**
     * Sends the key down and up pair. A single down event leaves some media apps waiting for the up
     * and doing nothing at all.
     */
    private fun dispatchMediaKey(keyCode: Int) {
        val audio = checkNotNull(context.getSystemService(AudioManager::class.java)) {
            "no AudioManager available"
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    /** Started from a no-UI activity that finishes immediately, so a new task is required. */
    private fun Intent.newTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
