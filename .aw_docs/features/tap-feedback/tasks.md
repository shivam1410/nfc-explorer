# Tasks — Tap Feedback

## Spec Brief

**Feature goal.** Give a tag tap an app-owned response the user controls: an optional tone of their
choosing at a volume they set, and a toast naming the assignment and the UID. Unassigned tags stay
silent.

**Spec brief.** A pure `TapFeedback` restates the three outcomes `TagActionActivity` already
branches on into a `TapOutcome`. A `FeedbackAnnouncer` seam turns that into a `MediaPlayer` tone and
a `Toast`, both honouring `FeedbackSettings` held in `SharedPreferences`. Settings gains one
`SectionCard` whose tone rows launch Android's own ringtone picker.

**Architecture summary.** `domain/feedback/` is pure and unit-tested; `data/feedback/` is the thin
Android adapter and is device-verified, per ADR 0001. No new permission.

**Execution route.** `/aw:build`
**Execution mode.** sequential — every phase depends on types the previous one creates
**Chunk review mode.** per-phase, at each save-point commit

## File structure

### Create

| File | Responsibility |
|---|---|
| `domain/feedback/TapOutcome.kt` | Sealed `Ran` / `Failed` / `Ignored` |
| `domain/feedback/TapFeedback.kt` | Dispatch state and action failure → `TapOutcome` |
| `domain/feedback/FeedbackVolume.kt` | Percent clamping, gain conversion |
| `domain/feedback/FeedbackSettings.kt` | Interface over the four preferences |
| `domain/feedback/FeedbackAnnouncer.kt` | Interface: `announce(TapOutcome)` |
| `data/feedback/PreferencesFeedbackSettings.kt` | `SharedPreferences("nfc-explorer-feedback")` |
| `data/feedback/AndroidFeedbackAnnouncer.kt` | `MediaPlayer` tone + `Toast` |
| `di/FeedbackModule.kt` | Binds both interfaces |
| `test/domain/feedback/TapFeedbackTest.kt` | Outcome table |
| `test/domain/feedback/FeedbackVolumeTest.kt` | Clamp and gain |

### Modify

| File | Change |
|---|---|
| `TagActionActivity.kt` | Inject the announcer; announce on all four paths |
| `ui/settings/SettingsViewModel.kt` | Four fields in `SettingsUiState`, four setters |
| `ui/settings/SettingsScreen.kt` | "Tap feedback" `SectionCard`, picker launcher, slider |
| `ui/navigation/NfcExplorerNavHost.kt` | Thread the new callbacks |
| `res/values/strings.xml` | Section, row and toast strings |
| `test/ui/settings/SettingsViewModelTest.kt` | Fake `FeedbackSettings`, setter coverage |
| `README.md` | Feature row, and why the OS beep is not ours |

---

## Phase 1 — The pure decision

**Outcome.** What a tap should say is decided in framework-free Kotlin and proven by tests, before
anything can make a sound.

### 1.1 `TapOutcome` and `TapFeedback` — RED

- type: `code` · size: `S`
- files: `domain/feedback/TapOutcome.kt`, `domain/feedback/TapFeedback.kt`,
  `test/domain/feedback/TapFeedbackTest.kt`

- [ ] Write `TapOutcome` as the sealed interface in `spec.md`, and `TapFeedback` with both functions
      returning `TapOutcome.Ignored` unconditionally — a stub that compiles and is wrong.
- [ ] Write `TapFeedbackTest` covering all five cases: assigned+permitted+Live → `Ran` carrying label
      and `uidKey`; assigned+refused+`Absent(cause)` → `Failed` reading
      "the card left the field before it could answer"; assigned+refused+`Absent(null)` → `Failed`
      reading "this card speaks nothing the app can open"; `assignment == null` → `Ignored`;
      `onActionFailure` → `Failed` whose reason names the exception class and message.
- [ ] Run the RED command; confirm four of the five fail for the stated reason, not on a compile
      error.

- validation (RED): `./gradlew :app:testDebugUnitTest --tests '*TapFeedbackTest*'`
- acceptance: four failures, each an assertion failure naming the expected outcome

### 1.2 `TapFeedback` — GREEN

- type: `code` · size: `S` · files: `domain/feedback/TapFeedback.kt`

- [ ] Implement the table in `spec.md`. `uidKey` comes from `TagAssignment.uidKey`, so the toast and
      the store name a card identically.
- [ ] Rerun the same command; confirm all five pass.

- validation (GREEN): `./gradlew :app:testDebugUnitTest --tests '*TapFeedbackTest*'`
- acceptance: five passing, zero failing

### 1.3 `FeedbackVolume` — RED → GREEN

- type: `code` · size: `XS`
- files: `domain/feedback/FeedbackVolume.kt`, `test/domain/feedback/FeedbackVolumeTest.kt`

- [ ] Write `FeedbackVolumeTest`: `clamp(-5) == 0`, `clamp(140) == 100`, `clamp(70) == 70`,
      `gain(0) == 0f`, `gain(100) == 1f`, `gain(50) == 0.5f`, and `gain(-5) == 0f` proving gain
      clamps rather than trusting its caller.
- [ ] Run RED, confirm failures, implement, rerun GREEN.

- validation: `./gradlew :app:testDebugUnitTest --tests '*FeedbackVolumeTest*'`
- acceptance: seven passing

### 1.4 Refactor and commit

- type: `code` · size: `XS`

- [ ] Read `TagActionActivity.act` beside `TapFeedback.onDispatch` and confirm the branches line up
      one-to-one. If the activity has a case the decision lacks, the decision is wrong.
- [ ] `./gradlew :app:testDebugUnitTest :app:detekt`
- [ ] Save-point commit: `feat: decide what a tap should say, in the domain`

- validation: full suite green, count 406 → 418

---

## Phase 2 — Preferences

**Outcome.** The four settings persist across restart and are reachable from the settings ViewModel.

### 2.1 `FeedbackSettings` and its store

- type: `code` · size: `S`
- files: `domain/feedback/FeedbackSettings.kt`, `data/feedback/PreferencesFeedbackSettings.kt`,
  `di/FeedbackModule.kt`

- [ ] Write the interface exactly as in `spec.md`.
- [ ] Implement over `getSharedPreferences("nfc-explorer-feedback", MODE_PRIVATE)`. Defaults:
      both tones `null`, volume `FeedbackVolume.DEFAULT_PERCENT`, toasts `true`.
- [ ] `setVolumePercent` routes through `FeedbackVolume.clamp`, so an out-of-range value can never
      reach the store — the invariant lives at the write, not at every read.
- [ ] A null tone `remove`s the key rather than writing an empty string; "" and null must not be two
      spellings of silent.
- [ ] Bind in `FeedbackModule` as `@Singleton`, matching `ActionModule`.
- [ ] `./gradlew :app:assembleDebug` to prove Hilt resolves the graph.

- validation: `./gradlew :app:assembleDebug`
- acceptance: builds; no test yet — `SharedPreferences` cannot run in a JVM test, same as
  `DataStoreAssignmentDocuments`, and the logic worth testing is already in `FeedbackVolume`

### 2.2 ViewModel state and setters — RED → GREEN

- type: `code` · size: `M`
- files: `ui/settings/SettingsViewModel.kt`, `test/ui/settings/SettingsViewModelTest.kt`

- [ ] Add a `FakeFeedbackSettings` to the existing test file's fakes.
- [ ] Write the RED tests: initial state mirrors the store; `onRanToneChosen(uri)` persists and
      updates state; `onRanToneChosen(null)` clears it; `onVolumeChange(140)` stores 100;
      `onToastsChange(false)` persists.
- [ ] Run RED — expect compile failure first, then assertion failures once the members exist as stubs.
- [ ] Add `ranTone`, `failedTone`, `volumePercent`, `toastsEnabled` to `SettingsUiState` and the four
      setters, each writing through to `FeedbackSettings` and then updating `backing`.
- [ ] Rerun GREEN.

- validation: `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest*'`
- acceptance: five new tests passing, existing ones untouched and still green

### 2.3 Commit

- [ ] `./gradlew :app:testDebugUnitTest :app:detekt`
- [ ] Save-point commit: `feat: persist tap feedback preferences`

---

## Phase 3 — Making the sound and the toast

**Outcome.** An assigned tap announces itself; an unassigned one still does nothing.

### 3.1 `AndroidFeedbackAnnouncer`

- type: `code` · size: `M`
- files: `domain/feedback/FeedbackAnnouncer.kt`, `data/feedback/AndroidFeedbackAnnouncer.kt`,
  `di/FeedbackModule.kt`, `res/values/strings.xml`

- [ ] Add the toast strings: `feedback_toast_ran` = `"%1$s — %2$s"`,
      `feedback_toast_failed` = `"%1$s didn't run — %2$s"`.
- [ ] Implement `announce`: `Ignored` returns immediately; otherwise toast when
      `settings.toastsEnabled()`, then play the tone for that outcome when one is set.
- [ ] Playback exactly as `spec.md` describes — `prepareAsync`, release on completion **and** on
      error, `USAGE_NOTIFICATION_EVENT` + `CONTENT_TYPE_SONIFICATION`, `setVolume(gain, gain)`.
- [ ] Wrap the whole body so nothing escapes: a deleted tone URI throws from `setDataSource`, and a
      tap must not die for it. Log the failure through `SessionLogger` at warn — silently swallowing
      it would violate `no-empty-catch` and would also make AC6 unobservable.
- [ ] Bind in `FeedbackModule`.

- validation: `./gradlew :app:assembleDebug :app:detekt`
- acceptance: builds and lints; behaviour proven on device in Phase 5

### 3.2 Wire the trigger

- type: `code` · size: `S` · files: `TagActionActivity.kt`

- [ ] Inject `FeedbackAnnouncer`.
- [ ] In `act`, compute `TapFeedback.onDispatch(assignment, permitted, presence)` once, next to the
      existing `permitted` line, and `announce` it. One call covers all three branches; do not
      scatter three.
- [ ] Inside the existing `performer.perform(...).onFailure`, announce
      `TapFeedback.onActionFailure(assignment, failure)` — inside `actionScope`, after the existing
      log call, since that block already outlives the activity.
- [ ] Confirm by reading that the `Ignored` path reaches no framework call, preserving AC2.

- validation: `./gradlew :app:testDebugUnitTest :app:detekt`
- acceptance: full suite green

### 3.3 Commit

- [ ] Save-point commit: `feat: announce a tap with a tone and a toast`

---

## Phase 4 — Settings UI

**Outcome.** The four preferences are reachable, and the section says what the app cannot control.

### 4.1 Strings

- type: `code` · size: `XS` · files: `res/values/strings.xml`

- [ ] `settings_feedback_title` = `"Tap feedback"`.
- [ ] `settings_feedback_subtitle` = `"What this app does when you tap a tag that has an action.
      Android plays its own discovery beep first, before this app is even started — that one follows
      your notification volume and cannot be switched off from here."` (AC8.)
- [ ] `settings_feedback_ran`, `settings_feedback_failed`, `settings_feedback_volume`,
      `settings_feedback_toast`, `settings_feedback_silent` = `"Silent"`,
      `settings_feedback_choose` = `"Choose"`, `settings_feedback_preview` = `"Preview"`.

### 4.2 The section

- type: `code` · size: `M`
- files: `ui/settings/SettingsScreen.kt`, `ui/navigation/NfcExplorerNavHost.kt`

- [ ] Add a `SectionCard` after Permissions, before Deleted tags.
- [ ] Two tone rows, each showing the tone's display title — resolved through
      `RingtoneManager.getRingtone(context, uri)?.getTitle(context)` — or `settings_feedback_silent`,
      with a Choose button and a Preview button.
- [ ] Choose launches `RingtoneManager.ACTION_RINGTONE_PICKER` via
      `rememberLauncherForActivityResult(StartActivityForResult)`, with `EXTRA_RINGTONE_TYPE =
      TYPE_NOTIFICATION`, `EXTRA_RINGTONE_SHOW_SILENT = true`, `EXTRA_RINGTONE_SHOW_DEFAULT = false`
      and `EXTRA_RINGTONE_EXISTING_URI` set to the current value. The result's
      `EXTRA_RINGTONE_PICKED_URI` is null for Silent — pass it through as null.
- [ ] One launcher per row, or one launcher plus a remembered "which row asked" — either, but not a
      shared launcher whose result lands on the wrong row.
- [ ] A `Slider` for volume, 0..100, `onValueChangeFinished` writing through so a drag is not 100
      preference writes.
- [ ] A `Switch` row for toasts.
- [ ] Preview calls the announcer with a sample `Ran`/`Failed` so the volume can be judged on the
      spot (AC5).
- [ ] Thread every callback through `NfcExplorerNavHost`.

- validation: `./gradlew :app:assembleDebug :app:detekt`
- acceptance: builds and lints; `SettingsScreen` stays under the file-length limit — extract the tone
  row into a private composable rather than inlining it twice

### 4.3 Commit

- [ ] `./gradlew :app:testDebugUnitTest :app:detekt`
- [ ] Save-point commit: `feat: choose the tap sound and toast in settings`

---

## Phase 5 — Docs and device verification

**Outcome.** AC1–AC8 evidenced on hardware, and the README states what the app cannot silence.

### 5.1 README

- type: `docs` · size: `XS` · files: `README.md`

- [ ] Add a `Tap feedback: sound, volume, toast` row to the feature table, state `done`.
- [ ] Add a short paragraph, in the register of "There is no NFC in the Android emulator": the
      discovery beep on a background tap belongs to the platform, `FLAG_READER_NO_PLATFORM_SOUNDS`
      only covers foreground reader mode, and the beep rides the notification volume stream.
- [ ] Update the test count in the status line.

### 5.2 Device verification

- type: `docs` · size: `S`
- files: `.aw_docs/features/tap-feedback/verification.md`, `evidence/`

- [ ] `./gradlew :app:installDebug`, then with the app closed: tap an assigned card (AC1), tap an
      unassigned card (AC2), tap an assigned card and pull it away instantly (AC3).
- [ ] Force-stop and reopen to confirm the tone survived (AC4).
- [ ] Preview at 10% and at 100% (AC5).
- [ ] Point a tone at a URI then delete the file, or pick one from removed media, and tap (AC6).
- [ ] Toasts off, tap (AC7). Both tones Silent and toasts off, tap — nothing but the OS beep (AC7).
- [ ] Screenshot the settings section (AC8) into `evidence/`.
- [ ] `adb logcat -s NfcExplorer:V` alongside, so a refused tap has its logged cause next to the
      toast the user saw.
- [ ] Write `verification.md` in the shape of `tag-actions/verification.md`.

- validation: `verification.md` records a result per AC, with the failures kept rather than tidied
- acceptance: AC1–AC9 each pass or carry a named reason

### 5.3 Commit

- [ ] Save-point commit: `docs: tap feedback verified on device`

---

## Blockers that send this back to planning

- If `announce` cannot be made not to throw without swallowing a real error, the seam is wrong —
  revisit before wiring it into the trigger.
- If the ringtone picker returns a URI this app cannot read on some device (a grant problem rather
  than a missing-file problem), the tone must be stored differently. That is a spec change.

## Parallelism

`max_parallel_subagents: 1`. Every phase consumes types the previous phase creates, and Phase 4 edits
the same `SettingsViewModel` Phase 2 does. Sequential.
