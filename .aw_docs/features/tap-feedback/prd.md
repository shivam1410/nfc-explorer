# PRD — Tap Feedback

## Goal

Give a tag tap an app-owned response the user controls: an optional sound of their choosing at a
volume they set, and a toast naming what just ran and which card ran it. Today an assigned tap is
completely silent from this app's side — the only thing the user hears is Android's own discovery
beep, which is loud, not ours, and not configurable from here.

## Scope

1. **Settings section "Tap feedback"** holding four preferences, persisted on the device:
   - **Sound when an action runs** — any notification tone on the phone, or Silent
   - **Sound when a tap fails** — same, independently chosen
   - **Volume** — 0–100%, applied to whichever tone plays
   - **Show a toast** — on/off
2. **Tone selection through Android's own ringtone picker** (`RingtoneManager.ACTION_RINGTONE_PICKER`,
   `TYPE_NOTIFICATION`, silent entry shown). No audio files are bundled.
3. **A preview button per tone**, so the volume can be judged without hunting for a card.
4. **A toast after a tap that did something**: the assignment's label and the tag's UID, e.g.
   `Desk card — 041c4e52ce7c80`.
5. **A distinct toast when an assigned tap fails**, naming the reason in the user's words rather than
   the exception's.
6. **Unassigned tags stay completely silent** — no toast, no tone, exactly as now.

## Non-goals

- **Silencing Android's own NFC discovery beep.** See A1. Nothing in scope can affect it.
- Sound or toast on the in-app scan screen. That surface already shows the dump and buzzes; a second
  channel there is noise. `ScanHaptics` is untouched by this feature.
- A notification (as in the shade) rather than a toast. A tap is transient and needs no history —
  the session log already keeps one.
- Per-assignment sounds. One pair of tones for the whole app; per-tag tones can follow if asked for.
- Custom animation on the trigger surface. `TagActionActivity` draws no frame by design (see the
  `Theme.NoDisplay` note in `tag-actions/state.json`); the toast is the visual.

## Assumptions and constraints

- **A1** Android's NFC discovery sound is played by the platform's `NfcService` when it dispatches
  the tag, before `TagActionActivity` exists. `FLAG_READER_NO_PLATFORM_SOUNDS` suppresses it **only**
  in foreground reader mode, which is why in-app scans are already silent and background taps are
  not. There is no app-side API for the dispatch path. The beep follows the device notification
  volume stream, so lowering that is the user's only lever, and it is system-wide. This must be
  stated in the settings UI, or the feature reads as broken when the beep persists.
- **A2** Both tones default to **Silent** and toasts default to **on**. The complaint that prompted
  this feature is "too loud"; shipping a second sound on by default would make the thing worse.
- **A3** The chosen tone is stored as a content URI string. A tone can be deleted or live on a
  removed SD card, so playback failure is expected, not exceptional, and must degrade to silence
  rather than crash a tap.
- **A4** `ActionPerformer.perform` is deliberately not awaited by `TagActionActivity` — it runs in the
  application scope so it survives the activity finishing. A failure therefore arrives **after** the
  activity is gone, so the failure toast must be posted from that scope with the application context
  on the main thread.
- **C1** No new permission. `RingtoneManager`, `MediaPlayer` and `Toast` need none. In particular
  this feature does **not** add `VIBRATE`.
- **C2** `domain/` stays framework-free (`DomainPurityTest`, ADR 0001). Every `android.media.*` and
  `android.widget.Toast` reference lives in `data/feedback/`, behind an interface.
- **C3** `minSdk 26`. `Ringtone.setVolume` is API 28, so playback uses `MediaPlayer`, which has had
  `setVolume` since API 1.

## Acceptance criteria

- **AC1** Tapping an assigned tag with the app closed shows a toast naming the assignment's label and
  the tag's UID.
- **AC2** Tapping an unassigned tag shows no toast and plays no tone.
- **AC3** An assigned tag that fails the presence check shows a failure toast naming the reason, and
  plays the failure tone rather than the success tone.
- **AC4** A chosen tone survives an app restart, and a tone set to Silent plays nothing.
- **AC5** The volume setting changes how loud the app's own tone is, verified by preview at 10% and
  100%.
- **AC6** A tone URI that no longer resolves degrades to silence; the toast and the action still
  happen.
- **AC7** Turning the toast off leaves the tone behaviour untouched, and turning both off restores
  today's behaviour exactly.
- **AC8** The settings section states, in words, that Android's own discovery beep is not ours and
  follows the notification volume.
- **AC9** `./gradlew :app:testDebugUnitTest` and `:app:detekt` stay green.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| User sets a tone and still hears the loud OS beep, concludes the setting is broken | AC8 — say so in the section subtitle, not only in the README |
| Toast outlives the finished activity and is dropped | Post through the application context, never the activity's; failure path posts from `ApplicationScope` on `Dispatchers.Main` |
| `MediaPlayer` leaks on a tap that fires often | Release on completion **and** on error; a tap is short-lived and there is at most one player per outcome |
| Toast on every tap becomes noise for a card tapped repeatedly | It is a preference, off in one tap. Not rate-limited: a suppressed toast after a real tap is worse than a repeated one |
