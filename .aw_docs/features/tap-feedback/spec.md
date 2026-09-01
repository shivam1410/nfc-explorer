# Spec — Tap Feedback

## Current state

406 unit tests, Detekt gating, `domain/` framework-free and enforced by `DomainPurityTest`.

Relevant existing shape:

- `TagActionActivity` decides what a tap does in one `when` block over
  `(assignment, TagActionDispatch.shouldAct, TagPresence.Answer)`, and logs each of the three
  outcomes. This feature announces the same three outcomes; it must not invent a fourth.
- `PreferencesTogglConfig` is the house pattern for a small device-local preference: a `domain`
  interface, a `SharedPreferences` implementation in `data/`, bound in a Hilt module.
- `ScanHaptics` already owns in-app feedback and is **not** touched.
- `SectionCard` is the settings section primitive.

## Architecture

```
domain/feedback/
  TapOutcome.kt        sealed: Ran | Failed | Ignored — what the user should be told
  TapFeedback.kt       pure: dispatch outcome -> TapOutcome, and action failure -> Failed
  FeedbackVolume.kt    pure: percent clamping and the 0f..1f gain MediaPlayer wants
  FeedbackSettings.kt  interface: the four preferences
  FeedbackAnnouncer.kt interface: announce(TapOutcome)
data/feedback/
  PreferencesFeedbackSettings.kt  SharedPreferences "nfc-explorer-feedback"
  AndroidFeedbackAnnouncer.kt     MediaPlayer + Toast, application context only
di/FeedbackModule.kt              binds both
```

`TagActionActivity` gains one injected `FeedbackAnnouncer` and three call sites — one per existing
branch of the `when` — plus one inside the `onFailure` of the un-awaited action.

`SettingsViewModel` gains the four preferences in `SettingsUiState` and setters; `SettingsScreen`
gains one `SectionCard`. The ringtone picker is launched from the composable via
`rememberLauncherForActivityResult`, because only an Activity can start it — the same shape the sync
consent `PendingIntent` already uses.

## Interfaces

```kotlin
/** What a tap should tell the user, decided without touching the framework. */
sealed interface TapOutcome {
    /** An assigned tag was accepted and its action dispatched. */
    data class Ran(val label: String, val uidKey: String) : TapOutcome

    /** An assigned tag did not run its action. [reason] is user-facing prose, not an exception. */
    data class Failed(val label: String, val uidKey: String, val reason: String) : TapOutcome

    /** Nothing to say: an unassigned tag, or a launch that was not a genuine dispatch. */
    data object Ignored : TapOutcome
}

object TapFeedback {
    fun onDispatch(
        assignment: TagAssignment?,
        permitted: Boolean,
        presence: TagPresence.Answer,
    ): TapOutcome

    fun onActionFailure(assignment: TagAssignment, failure: Throwable): TapOutcome.Failed
}

object FeedbackVolume {
    const val DEFAULT_PERCENT = 70
    fun clamp(percent: Int): Int          // coerced into 0..100
    fun gain(percent: Int): Float         // clamp(percent) / 100f
}

interface FeedbackSettings {
    /** Content URI of the tone, or null for silent. */
    fun ranTone(): String?
    fun setRanTone(uri: String?)
    fun failedTone(): String?
    fun setFailedTone(uri: String?)
    fun volumePercent(): Int
    fun setVolumePercent(percent: Int)
    fun toastsEnabled(): Boolean
    fun setToastsEnabled(enabled: Boolean)
}

interface FeedbackAnnouncer {
    /** Plays the configured tone and shows the toast, honouring the settings. Never throws. */
    fun announce(outcome: TapOutcome)
}
```

## Technical approach

### Deciding what to say

`TapFeedback.onDispatch` is a pure restatement of the branch `TagActionActivity` already takes:

| assignment | permitted | presence | outcome |
|---|---|---|---|
| non-null | true | Live | `Ran(label, uidKey)` |
| non-null | false | `Absent(cause)` | `Failed(label, uidKey, "the card left the field before it could answer")` |
| non-null | false | `Absent(null)` | `Failed(label, uidKey, "this card speaks nothing the app can open")` |
| null | any | any | `Ignored` |

The reason strings are deliberately not the exception's message. `presenceDetail` in the activity
already produces the diagnostic form for the log; this is the other audience.

`onActionFailure` produces `Failed(label, uidKey, "<ExceptionName>: <message>")`. Here the exception
**is** the useful thing: an action failing is a configuration problem the user has to fix, and
"something went wrong" would not help them fix it.

### Playing the tone

`MediaPlayer`, not `Ringtone`: `Ringtone.setVolume` arrived in API 28 and `minSdk` is 26, so the
volume slider would silently do nothing on the two oldest supported releases.

```
MediaPlayer().apply {
    setAudioAttributes(USAGE_NOTIFICATION_EVENT + CONTENT_TYPE_SONIFICATION)
    setDataSource(context, uri)
    setVolume(gain, gain)
    setOnCompletionListener { it.release() }
    setOnErrorListener { player, _, _ -> player.release(); true }
    prepareAsync(); setOnPreparedListener { it.start() }
}
```

`prepareAsync`, not `prepare`: a tap is dispatched on the main thread and a content URI on slow
storage would block it. Every terminal path releases — completion, error, and a throwing
`setDataSource` (a deleted tone, per A3), which is caught and dropped to silence.

### Showing the toast

`Toast.makeText(applicationContext, ...)`, always. The activity finishes immediately after
dispatch, and a toast tied to a finishing activity's context is the classic way to lose it. The
failure path additionally hops to `Dispatchers.Main` because it runs in `ApplicationScope` on an
arbitrary dispatcher.

`Toast.LENGTH_SHORT` for `Ran`, `LENGTH_LONG` for `Failed` — a failure carries a reason worth
reading.

### Storing the settings

`SharedPreferences("nfc-explorer-feedback")`, matching `PreferencesTogglConfig`. Not `DataStore`:
these are read synchronously on a tap, on a path that must not suspend before the action fires, and
there is no flow consumer for them. Not synced to Drive either — a tone URI is device-local and a
volume is a property of this phone, so pushing them to another device would be wrong.

## Failure modes

| Failure | Behaviour |
|---|---|
| Tone URI no longer resolves | Caught, silence, tap proceeds (AC6) |
| Tone is set but device is in silent mode | Platform decides; `USAGE_NOTIFICATION_EVENT` respects it |
| Toast posted after the activity finished | Application context makes this safe |
| Volume preference written out of range | `FeedbackVolume.clamp` — the store never holds an out-of-range value |
| `announce` throws for any reason | It must not. The whole body is guarded; a broken tone must never break a tap |

## Invariants

- **I1** `domain/feedback/` imports nothing from `android.*` or `androidx.*` (`DomainPurityTest`).
- **I2** An unassigned tag produces `TapOutcome.Ignored` and `announce` does nothing observable.
- **I3** `announce` never throws and never blocks the main thread on I/O.
- **I4** With both tones Silent and toasts off, behaviour is byte-for-byte today's behaviour.

## Testing strategy

JVM unit tests cover everything above the framework seam:

- `TapFeedbackTest` — the four rows of the table above, plus `onActionFailure` naming the exception.
- `FeedbackVolumeTest` — clamping below 0, above 100, and the gain conversion.
- `SettingsViewModelTest` — extended with a fake `FeedbackSettings`: each setter persists, the volume
  setter clamps, and a null tone means silent.

`AndroidFeedbackAnnouncer` is device-verified, not unit-tested, consistent with ADR 0001 and with how
`TagActionRunner` is treated: it is the thin adapter, and everything worth testing was hoisted out of
it.

## Expected changed files

Created:

```
app/src/main/java/dev/shivam/nfcexplorer/domain/feedback/TapOutcome.kt
app/src/main/java/dev/shivam/nfcexplorer/domain/feedback/TapFeedback.kt
app/src/main/java/dev/shivam/nfcexplorer/domain/feedback/FeedbackVolume.kt
app/src/main/java/dev/shivam/nfcexplorer/domain/feedback/FeedbackSettings.kt
app/src/main/java/dev/shivam/nfcexplorer/domain/feedback/FeedbackAnnouncer.kt
app/src/main/java/dev/shivam/nfcexplorer/data/feedback/PreferencesFeedbackSettings.kt
app/src/main/java/dev/shivam/nfcexplorer/data/feedback/AndroidFeedbackAnnouncer.kt
app/src/main/java/dev/shivam/nfcexplorer/di/FeedbackModule.kt
app/src/test/java/dev/shivam/nfcexplorer/domain/feedback/TapFeedbackTest.kt
app/src/test/java/dev/shivam/nfcexplorer/domain/feedback/FeedbackVolumeTest.kt
```

Modified:

```
app/src/main/java/dev/shivam/nfcexplorer/TagActionActivity.kt      announce on all four paths
app/src/main/java/dev/shivam/nfcexplorer/ui/settings/SettingsViewModel.kt   state + setters
app/src/main/java/dev/shivam/nfcexplorer/ui/settings/SettingsScreen.kt      one SectionCard
app/src/main/java/dev/shivam/nfcexplorer/ui/navigation/NfcExplorerNavHost.kt  new callbacks
app/src/main/res/values/strings.xml                                 section + toast strings
app/src/test/java/dev/shivam/nfcexplorer/ui/settings/SettingsViewModelTest.kt
README.md                                                           feature row + the beep note
```

## Acceptance criteria

Inherited from `prd.md`, AC1–AC9.

## Verification targets

- `./gradlew :app:testDebugUnitTest` — green, count risen from 406
- `./gradlew :app:detekt` — green
- Device: AC1–AC8 against an assigned card and an unassigned card
