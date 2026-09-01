# Verification — Tap Feedback

## What was used

- Android 16 (API 36) emulator, `sketchseed-test`, production build (no root).
- `./gradlew :app:testDebugUnitTest` — 591 tests, 0 failures.
- `./gradlew :app:detekt` — **16 weighted issues, all pre-existing.** See "Detekt" below.

## The gap in this verification

**No physical tap was performed.** There is no NFC in the emulator (README), and no card or phone was
available to this session. Every AC below that depends on a real tag being dispatched to
`TagActionActivity` is therefore verified *through the preview path*, which runs the identical
`FeedbackAnnouncer.announce` with an identical `TapOutcome` — but does not exercise the platform
dispatch, `TagPresence.check`, or `TagActionActivity` itself.

What that leaves genuinely unproven, and what a phone and two cards would settle in about a minute:

- that the toast appears at all when the activity finishes immediately after posting it (the
  application-context reasoning is sound and the preview cannot test it, because the settings
  activity does not finish)
- that an unassigned tag is silent *in the dispatch path* rather than only in `TapFeedback`
- that the second, later toast from a failing action arrives from `ApplicationScope`
- how the app's tone sits against the platform beep in practice, which is the actual complaint

## Results

| AC | Result | Evidence |
|---|---|---|
| AC1 toast names label and UID | **Partial** — toast rendered and correct, via preview, not a tap | `evidence/preview-toast.png`: `Preview — 041c4e52ce7c80` |
| AC2 unassigned tag silent | **Unit only** — `TapFeedbackTest` covers `Ignored`; not exercised on a tag | `TapFeedbackTest` |
| AC3 failed tap names the reason | **Partial** — failure toast rendered via preview | `evidence/failure-toast.png`: `Preview didn't run — the card left the field before it could answer` |
| AC4 tone survives restart | **Pass** | `Eureka` still shown after `am force-stop` and relaunch |
| AC5 volume changes loudness | **Partial** — slider writes and persists; loudness itself not judged (emulator audio) | store shows no `volumePercent` key until moved, then the clamped value |
| AC6 dead tone degrades to silence | **Pass** | tone selected, its media row and file deleted, then previewed: `W NfcExplorer: [0] feedback: tap tone could not be read; falling back to silence {message=setDataSource failed.: status=0x80000000}`, no crash, toast still shown |
| AC7 toasts off leaves tones alone | **Pass** | with the switch off, zero `Toast` posts in logcat and `MediaPlayer` still ran |
| AC8 section states the beep is not ours | **Pass** | `evidence/settings-section.png` |
| AC9 tests and detekt green | **Tests pass. Detekt does not — and did not before this work.** | below |

## Detekt

`:app:detekt` fails with 16 weighted issues. **All 16 are pre-existing**, in files this feature never
opened: `ActionBindingsModule` (17 functions vs a threshold of 16), `TagActionSerializer.toLeafOrNull`
(complexity 18 vs 15), `IntentSpec.map` (64 lines vs 60), an unused `ACTION_ICON_SIZE` and an unused
`CloudSyncService.toDto`, a `LongParameterList` in `TagActionGestureTest`, and eight `MaxLineLength`
hits. Confirmed pre-existing by the working tree containing nothing but new, untracked files at the
time detekt was first run.

No new finding was introduced by any of the five phases; the count stayed at 16 throughout. The two
findings that name `SettingsScreen.kt` and `SettingsViewModel.kt` are the sync date-format line and
the unknown-sources intent line, both untouched, renumbered by insertions above them.

The README's claim of "Detekt gating" is therefore currently untrue on this branch. Fixing those 16
is out of scope here and wants its own change.

## Defects found and fixed during the build

1. **English prose in `domain/`.** The first draft of `TapFeedback` returned failure reasons as
   sentences, contradicting the rule `Labels.kt` states explicitly — the domain carries reason codes
   so it stays free of translatable text. Replaced with `TapFailure`, resolved in the announcer.
2. **A refused non-NFC launch would have announced itself.** The trigger is exported, so a toast on
   an arbitrary launch hands any app on the device a way to spam the screen — and to learn which UIDs
   are assigned by watching which launches produce one. `onDispatch` now returns `Ignored` unless the
   launch was a genuine NFC dispatch. Not in the plan; found reading the plan's own outcome table
   against `TagActionDispatch`.
3. **A deleted tone read as "Silent".** Found on the emulator by doing the thing AC6 describes. The
   row claimed no sound was set when one was. `getTitle` was no help — it falls back to the URI's
   last path segment, so the first fix made the row read `38`. Fixed by asking the content resolver
   whether the URI opens.

## Not verified

- Behaviour below API 36. The `MediaPlayer`-over-`Ringtone` choice exists precisely for API 26–27
  and has been exercised on neither.
- Audio actually audible at a given volume. Emulator audio was not listened to; only that
  `MediaPlayer` prepared and started.
- Interaction with the platform beep, which is the whole reason the feature exists.
