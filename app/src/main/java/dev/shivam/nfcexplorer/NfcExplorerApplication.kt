package dev.shivam.nfcexplorer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.shivam.nfcexplorer.di.ApplicationScope
import dev.shivam.nfcexplorer.data.log.ActivityLogRecorder
import dev.shivam.nfcexplorer.logging.SessionLogcatMirror
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Attaches the logcat mirror for the whole process.
 *
 * It used to be attached in `MainActivity`, which meant a tap that started the process on its own --
 * the normal case for tag automation, with the app closed -- mirrored nothing. The entries were in
 * the in-app log the whole time, but `adb logcat` looked empty, which is the wrong place to lose
 * visibility: a tap-triggered run is the hardest thing here to observe any other way.
 */
@HiltAndroidApp
class NfcExplorerApplication : Application() {

    @Inject lateinit var logcatMirror: SessionLogcatMirror

    @Inject lateinit var activityRecorder: ActivityLogRecorder

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        logcatMirror.attach(scope)
        activityRecorder.attach(scope)
    }
}
