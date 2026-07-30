package dev.shivam.nfcexplorer.data.action

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.shivam.nfcexplorer.domain.action.IntentSpec
import dev.shivam.nfcexplorer.domain.action.IntentSpecMapper
import dev.shivam.nfcexplorer.domain.action.TagAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Performs an action.
 *
 * Deliberately thin: [IntentSpecMapper] decides *what* to fire and is unit-tested, while this class
 * only turns a spec into a platform call. Same split as the transport adapters — the logic is above
 * the seam, the delegation below it, verified on device.
 *
 * Returns [Result] rather than throwing. This runs from an activity with no UI, triggered by a tap, so
 * a missing app or a bad URI has to be reported to the log rather than surfaced as a crash the user
 * cannot see the cause of.
 */
@Singleton
class TagActionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun run(action: TagAction): Result<Unit> = runCatching {
        when (val spec = IntentSpecMapper.map(action)) {
            is IntentSpec.LaunchPackage -> launch(spec.packageName)
            is IntentSpec.ActivityIntent -> startActivity(spec)
            is IntentSpec.MediaKeyEvent -> dispatchMediaKey(spec.keyCode)
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
