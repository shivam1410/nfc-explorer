# Driving Sleep Cycle from a tag

Sleep Cycle (`com.northcube.sleepcycle`) publishes no automation API: no documented intent, no URL
scheme for tracking, no Tasker plugin, no Assistant action. This document records what is actually
reachable, how it was established, and — as importantly — the four routes that look promising and do
not work, so nobody has to rediscover them.

Everything below was read out of the installed APK (`4.26.32-production`) and then confirmed on a
Pixel 10 running Android 16. Findings are from **one** app version. Treat the gesture in particular as
something that can break on any update.

## What is reachable

### Starting a session — an exported activity

```
com.northcube.sleepcycle.ui.StartupAlarmActivity
  android:exported="true"
  no android:permission
  action: com.northcube.sleepcycle.STARTUP_ALARM
```

Any app may launch it. It starts a genuine session, not merely a screen: `SleepAnalysisService` comes
up as a foreground service (microphone and location types) and the ongoing notification appears.

```bash
adb shell am start -a com.northcube.sleepcycle.STARTUP_ALARM
```

Two behaviours worth knowing:

- It works **with the phone locked**. The activity shows over the keyguard and the session starts.
- With a session already running it does **not** start a second one and does **not** toggle. It
  navigates to `SleepActivity`, the live sleep screen. That is what makes it usable as "put the
  slider in front of me" in the stop sequence.

### Detecting a running session — the ongoing notification

```
package  com.northcube.sleepcycle
id       105
channel  CHANNEL_SLEEP_NOTIFICATION
text     "Analysis in progress"
```

Match on the **channel id**, never the text. Channel ids are chosen by developers and never
translated; "Analysis in progress" only matches on an English phone.

This is the only trustworthy source of state. A flag remembered by the automation drifts within a day,
because Sleep Cycle ends the session itself every morning when the alarm fires — after which a stored
flag still reads "running" and the toggle is inverted from then on.

## What does not work

| Route | Result |
|---|---|
| `ACTION_STOP_ALARM` to the sleep service | Silences a ringing alarm only. Session keeps running. |
| Firing `STARTUP_ALARM` again | No toggle. Opens the live sleep screen. |
| Notification action buttons | There are none — only a content intent. |
| Forging the wear "session end" message | Rejected silently. |

The wear route is the one that looks most promising, so it is worth spelling out. `ConnectedWearService`
is `exported="true"` with no permission, and the app clearly has a clean session-stop path behind it:

```
/sleepcycle/connected_device/request/sleep_session_end    -> "Wear requested sleep session stop"
/sleepcycle/connected_device/request/sleep_session_start  -> "Wear requested sleep session start"
```

Sending that intent locally produces **no app-side log at all**. `WearableListenerService` verifies the
caller is Google Play Services and drops anything else before the app's own code runs. There is no way
around it from an ordinary uid.

For completeness, the sleep service's entire action vocabulary is `ACTION_START_NEW_SESSION`,
`ACTION_MAINTAIN`, `ACTION_STOP_ALARM`, `ACTION_BROADCAST_CURRENT_ALARM`. None of them ends a session.

## Stopping a session — the gesture

With no intent available, the only route is the slider on `SleepActivity`. In the view tree it is
`slideHint` with `clickable="false"`, so a tap does nothing; it must be dragged upward.

Two things were established the hard way:

**A smooth swipe does not work.** `input swipe 540 1950 540 1100 800` was tried twice, from a clean
fullscreen state, and the session kept running. What works is a press that dwells briefly and then
moves in discrete increments — the shape of a real finger rather than an interpolated line.

**The travel must be long.** A stepped drag ending at y=1300 did nothing. The same drag continued to
y=1100 ended the session. On a 1080x2424 screen that is roughly a third of the display, which is why
the preset stores ratios of `0.805 -> 0.454` rather than a modest nudge.

The working sequence, for reference:

```bash
adb shell 'input motionevent DOWN 540 1950; \
  for y in 1900 1850 1800 1700 1600 1500 1400 1300 1200 1100; do input motionevent MOVE 540 $y; done; \
  input motionevent UP 540 1100'
```

**A drag is blind.** It lands on whatever is frontmost. During this investigation an unrelated app
took the foreground and silently absorbed several drags aimed at Sleep Cycle, which looked exactly
like the gesture not working. `DragGesture.requireForegroundPackage` exists because of that hour.

## How this app models it

Nothing above is hard-coded into a Sleep Cycle code path. It is expressed in the general action
vocabulary, and `SleepCycle` is a preset built from it:

```
WhileNotificationShowing(com.northcube.sleepcycle, CHANNEL_SLEEP_NOTIFICATION)
  ├─ absent  -> SendIntent(com.northcube.sleepcycle.STARTUP_ALARM)
  └─ showing -> Steps
                 ├─ SendIntent(STARTUP_ALARM)   raise the sleep screen
                 └─ DragGesture(0.5, 0.805 -> 0.5, 0.454, guarded on the package)
```

Any other app with a start intent and a slide-to-stop control can be described the same way without
new code.

## What it costs

Two grants the user must make by hand, both revocable, both inert until granted:

- **Notification access**, to read whether a session is running. Refused rather than guessed when
  absent — see `ActionResolver`, which returns `Refused` instead of defaulting to "not running".
- **Accessibility**, because injecting a touch into another app is not possible any other way.

If the state cannot be read the tap does nothing and says why. That is deliberate: a toggle that
guesses has even odds of starting a second recording or ending a real night's sleep tracking.

## Verification status

| Claim | How verified |
|---|---|
| `STARTUP_ALARM` starts a real session | On device: foreground service + notification observed |
| Works from the lock screen | On device, keyguard showing |
| Firing it again opens the sleep screen | On device |
| `ACTION_STOP_ALARM` does not end a session | On device |
| Forged wear message is dropped | On device: no app-side log |
| Smooth swipe does not move the slider | On device, twice |
| Stepped drag to y=1100 ends the session | On device, twice |
| This app's gesture implementation | On device: session ended via `dispatchGesture` |
| Tag dispatch needs an unlocked screen | On device: `NfcService screenState = ON_LOCKED` refuses |

All verified. The observed stop, driven through the app's own code path:

```
t+1s  session=1  top=com.northcube.sleepcycle/.ui.SleepActivity   intent raised the sleep screen
t+3s  session=0  top=com.northcube.sleepcycle/.ui.MainActivity    drag landed, session ended
```

If the stop half ever misbehaves after a Sleep Cycle update, `SleepCycle.STOP_*_RATIO` and
`DragGesture.holdMillis`/`steps` are the dials.

## Two things that bit during verification

**The trigger activity must not run the action in its own scope.** `TagActionActivity` is `noHistory`
and draws nothing, so it is destroyed the moment it raises another app's screen. With
`ActionPerformer.perform` suspending, that cancelled the action mid-flight and the log filled with
`action failed {exception=JobCancellationException}` on a tag that had visibly started a session
seconds earlier. Starting worked, because a single intent completes before the activity dies; stopping
never did, because it waits between its two steps. Actions now run on an application-scoped
`CoroutineScope` (see `@ApplicationScope`), which the process keeps alive anyway because this app hosts
a notification listener and an accessibility service.

**A tag cannot be tapped while the phone is locked.** Android's NFC service reports
`applyScreenState: screenState = ON_LOCKED` and dispatches nothing to apps. Sleep Cycle's start intent
works fine over the keyguard, but that does not help, because the tap never reaches this app to send
it. In practice the phone must be woken and unlocked before either tap — at bedtime and again in the
morning. Worth knowing before wiring a tag to the bedside table.

**A charging phone dreams.** The screensaver re-engaged repeatedly during testing and it locks the
device, which is the state above. Anything automated around a charging phone has to account for it.
