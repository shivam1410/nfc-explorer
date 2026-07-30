# PRD — Tag Actions

## Goal

Assign an action to a specific tag by UID, so tapping that tag performs it on the phone without
opening the app's UI. Turns the two unlocked cards — which can never carry NDEF — into working
physical triggers.

## Scope

1. **Assignments**: map a tag UID to one action, with a user-supplied label. Persisted across launches.
2. **Actions**, all permission-free:
   - **Launch app** — pick from installed launchable apps
   - **Open URI** — any `http(s)://` or app deep link (covers a YouTube Music playlist)
   - **Send intent** — explicit action string, optional data URI, optional string extras. The escape
     hatch that covers Sleep as Android and anything else exposing a documented intent
   - **Media command** — play/pause, next, previous
3. **Trigger on tap while the app is closed**, via a transparent no-UI activity.
4. **Unmapped tags finish silently** — no UI, no action.
5. **Management UI**: list assignments, create from the last scanned tag, edit, delete, and a
   "test now" button that runs an action without needing a tag.

## Non-goals

- Toggle Do Not Disturb / focus mode. Needs Notification Policy Access; deliberately deferred.
- Toggl Track via HTTP. Needs `INTERNET` plus API-token storage, which is a credential-handling
  surface worth its own phase rather than a bundled extra.
- Multiple actions per tag, conditions, scheduling, delays. MacroDroid does these well; this does not
  attempt to compete.
- Writing anything to the tag. Actions key off the **UID only**, which is why they work on tags that
  can never hold NDEF.

## Assumptions and constraints

- **A1** An Android app cannot execute code on a tag tap unless the system launches it. There is no
  background NFC listener. The trigger is therefore an exported activity with an NFC intent filter.
- **A2** `TECH_DISCOVERED` filtered to `NfcA`/`MifareUltralight` is narrower than `TAG_DISCOVERED`,
  but still claims most compatible tags. Unmapped UIDs must exit instantly and invisibly.
- **A3** The trigger activity **must be exported** for NFC dispatch to reach it. That makes it
  callable by any app on the device, so it must refuse to act unless launched by a genuine NFC intent
  carrying a tag whose UID is assigned. See the security note in `spec.md`.
- **A4** Listing installed apps on API 30+ needs a `<queries>` manifest declaration for
  `MAIN`/`LAUNCHER`. That is the sanctioned route and does **not** require the restricted
  `QUERY_ALL_PACKAGES` permission.
- **C1** No new runtime permission is requested. The manifest gains a `<queries>` element and one
  activity; nothing else.
- **C2** Reader mode still wins while NFC Explorer is in the foreground, so actions fire when the app
  is closed or backgrounded — which is the normal case for this feature.

## Acceptance criteria

- **AC1** An assignment persists across app restart and device reboot.
- **AC2** Tapping an assigned tag with the app closed performs the action and shows no app UI.
- **AC3** Tapping an unassigned tag performs nothing and shows no UI.
- **AC4** Each action type produces the correct `Intent`, verified by unit tests on the mapping.
- **AC5** An action naming an app that is not installed reports a clear failure rather than crashing.
- **AC6** The trigger activity ignores any launch that is not a genuine NFC dispatch, even when the
  UID in the extras matches an assignment.
- **AC7** Assignments can be created from the last scanned tag, edited, deleted, and tested from the UI.
- **AC8** `./gradlew :app:testDebugUnitTest` and `:app:detekt` stay green.

## Risks

| Risk | Mitigation |
|---|---|
| Collides with MacroDroid — Android shows a chooser on every tap | Documented in the README: use one or the other, not both |
| The app launches on unrelated tags encountered in the world | Narrow `TECH_DISCOVERED` filter plus silent exit on unmapped UIDs |
| An exported activity that runs stored intents is an attack surface | Refuse anything that is not an NFC dispatch; UID must be assigned. Covered by AC6 and a test |
| A silent no-op looks like a broken app | The management screen states the behaviour, and "test now" proves an action works without a tag |
| Stored intent extras are attacker-controlled if the store is ever writable by another app | `DataStore` lives in private app storage; only this app can write it |
