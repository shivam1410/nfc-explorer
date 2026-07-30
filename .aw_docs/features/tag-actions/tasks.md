# Tasks — Tag Actions

## Spec Brief

Assign an action to a tag UID; tapping that tag runs it with the app closed and no UI. Four
permission-free action types. Unmapped tags exit silently. Persistence via `DataStore` with
kotlinx.serialization DTOs in the data layer, keeping `domain/` annotation-free.

**Route:** `/aw:build` · **Execution:** sequential · **Review:** `kotlinx-reviewer` at Task 4.
`max_parallel_subagents: 1` — the slices share types and are too small to fan out usefully.

The one security-critical slice is Task 3: an exported activity that runs stored intents.

---

## Phase 1 — Action model and Intent mapping

**Outcome:** every action type is described in pure Kotlin and provably maps to the right `Intent`,
with no Android needed to prove it.

### Task 1.1 — Action types
- Files: `domain/action/TagAction.kt`, `TagAssignment.kt`, `TagActionRepository.kt`
- [ ] Sealed `TagAction` with the four variants from `spec.md`; `MediaKey` enum
- [ ] `TagAssignment(uid, label, action)`; repository interface
- Acceptance: `./gradlew :app:compileDebugKotlin` green; `DomainPurityTest` still passes, so no
  `android.*` and no serialization annotations reached `domain/`
- Validation: `./gradlew :app:testDebugUnitTest --tests '*DomainPurityTest*'`
- Commit: `feat: tag action model`

### Task 1.2 — Intent mapping behind a seam
- Files: `data/action/TagActionRunner.kt`, `IntentSpec.kt`, + `TagActionRunnerTest`
- [ ] **RED** — `TagActionRunnerTest` asserting, for each type, the resulting action string, data URI,
      package and extras: `LaunchApp` → launch intent for the package; `OpenUri` → `ACTION_VIEW` with
      the URI; `SendIntent` → the given action, URI and string extras verbatim;
      `MediaCommand` → the matching media key
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*TagActionRunnerTest*'` — **confirm it fails**
- [ ] **GREEN** — map `TagAction` to a pure `IntentSpec` data class; a thin Android adapter turns
      `IntentSpec` into a real `Intent`. The mapping is what carries risk, so the mapping is what is
      tested; the adapter is thin by design, per ADR 0001
- [ ] A blank or malformed URI is rejected by the mapper rather than reaching `startActivity`
- Acceptance: all four types mapped and asserted; no Android class needed in the test
- Commit: `feat: map tag actions to intent specs`

---

## Phase 2 — Persistence

**Outcome:** assignments survive restart, and a corrupt store degrades to empty instead of throwing.

### Task 2.1 — Serialization
- Files: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `data/action/TagActionSerializer.kt`,
  DTOs, + `TagActionSerializerTest`
- [ ] Add the kotlinx.serialization plugin and runtime; run `assembleDebug` and **correct any version
      mismatch from the real error**, not from memory
- [ ] **RED** — round-trip each of the four action types; a malformed document returns an empty list;
      an unknown action type in stored JSON is skipped rather than throwing
- [ ] Run the `--tests '*TagActionSerializerTest*'` command — **confirm it fails**
- [ ] **GREEN** — `@Serializable` DTOs in `data/`, mapped to and from the domain types
- Acceptance: round-trip exact for all four types; forward-compatible with an unknown type
- Commit: `feat: tag assignment serialization`

### Task 2.2 — DataStore-backed repository
- Files: `data/action/TagActionStore.kt`, `di/ActionModule.kt`, + `TagActionStoreTest`
- [ ] **RED** — save then `find` by UID; overwrite the same UID keeps one entry; delete removes it;
      `observeAll` emits after each change (Turbine); an unreadable store yields an empty list
- [ ] Run the `--tests '*TagActionStoreTest*'` command — **confirm it fails**
- [ ] **GREEN** — `DataStore<Preferences>` holding one JSON document; UID key is lowercase hex
- [ ] Hilt wiring binds `TagActionRepository`
- Acceptance: AC1's persistence proven on the JVM; device reboot check is Task 5
- Commit: `feat: persist tag assignments`

---

## Phase 3 — The trigger

**Outcome:** tapping an assigned tag with the app closed runs the action; anything else does nothing.
**This is the security-critical slice.**

### Task 3.1 — Dispatch guard
- Files: `TagActionActivity.kt`, `data/action/TagActionDispatcher.kt`, + `TagActionDispatchTest`
- [ ] **RED** — `TagActionDispatchTest` over a pure `shouldAct(action: String?, hasTag: Boolean,
      assignment: TagAssignment?)`: acts only for a real NFC action **with** a tag **and** a matching
      assignment. Explicitly asserts the abuse case — a non-NFC caller supplying a matching UID must
      **not** act (AC6). Also: NFC action but no `EXTRA_TAG` → no act; NFC action, tag, no assignment
      → no act
- [ ] Run the `--tests '*TagActionDispatchTest*'` command — **confirm it fails**
- [ ] **GREEN** — implement the predicate; `TagActionActivity` consults it and finishes immediately
      either way, with no theme and no content view so no frame is drawn
- Acceptance: AC6 covered by test; unmapped taps silent (AC3)
- Commit: `feat: guarded NFC action dispatch`

### Task 3.2 — Manifest wiring
- Files: `AndroidManifest.xml`, `res/xml/nfc_tech_filter.xml`
- [ ] `TagActionActivity` with `exported="true"`, `NoDisplay`-style theme, `excludeFromRecents`,
      `TECH_DISCOVERED` filter and a tech-list of `NfcA` + `MifareUltralight`
- [ ] `<queries>` element for `MAIN`/`LAUNCHER` so the app picker works on API 30+ **without**
      `QUERY_ALL_PACKAGES`
- [ ] Confirm no new `uses-permission` line appeared: `grep uses-permission AndroidManifest.xml`
      should still show only NFC
- Acceptance: `assembleDebug` green; manifest diff contains no new permission
- Commit: `feat: NFC trigger activity and app-query declaration`

---

## Phase 4 — Management UI

**Outcome:** assignments can be created from the last scan, edited, deleted and tested.

### Task 4.1 — ViewModel
- Files: `ui/actions/TagActionsViewModel.kt`, + test
- [ ] **RED** — create from `lastReport`'s UID; reject a blank label; reject a malformed URI; delete;
      "test now" runs the action without a tag present
- [ ] Run the `--tests '*TagActionsViewModelTest*'` command — **confirm it fails**
- [ ] **GREEN** — implement
- Commit: `feat: tag actions view model`

### Task 4.2 — Screen and navigation
- Files: `ui/actions/TagActionsScreen.kt`, `ui/navigation/NfcExplorerNavHost.kt`, `strings.xml`
- [ ] Sixth nav destination, or an entry from the Tag screen if six crowds the bar — decide by looking
      at it on the device, given six is beyond Material's recommended range
- [ ] List assignments with label, UID and action summary; editor per action type; delete; test button
- [x] Copy states the silent-on-unmapped behaviour, so a no-op does not read as a bug
- [x] All strings in `strings.xml`
- Acceptance: `assembleDebug` and Detekt green
- Commit: `feat: tag actions screen`
- Kept the sixth bottom-nav tab after looking at it on the device rather than guessing
  (`evidence/actions-nav.png`). Detekt's one finding was an unused `onTest`, which became
  `onTestDraft()` — testing before saving is the more useful moment.

### Task 4.3 — Review gate — DONE, verdict BLOCK, resolved
- [x] `kotlin-reviewer` over `domain/action`, `data/action`, `TagActionActivity` and the UI
- [x] Blocking findings fixed

**The note above was right for the third time running.** The reviewer's CRITICAL was in the dispatch
layer, and it was the parameter this plan invented to guard it: `shouldAct`'s `hasTagExtra` was passed a
literal `true` by the only caller, which had already null-checked the tag. Eight combinations swept, one
of the three conditions unable to vary. Full write-up in `evidence/trigger-trust-boundary.md`.

Fixed:
- `TagPresence.check` connects to the tag; a forged `Tag` parcel cannot answer. `shouldAct` now takes a
  `TagPresence.Answer`, so a literal `true` does not compile.
- Stale `message` surviving cancel/save/delete.
- A failing `DataStore` write escaping `viewModelScope` and killing the app on a button press.
- An unreadable assignment document degrading to empty with no log line.
- `MediaKey` rendered via `enum.name` in two places.

Accepted, not fixed:
- `observeAll()` collected in `init` rather than `stateIn(WhileSubscribed)`. The flow is documented never
  to throw and `DataStoreAssignmentDocuments` already has a `catch`, so this buys nothing here.
- `Column` + `forEach` rather than `LazyColumn` + `key` for the assignment list. One card per tag; a
  phone will not hold enough tags for this to matter.
- No unit tests for `TagActionRunner`, `DataStoreAssignmentDocuments`, `InstalledAppCatalog` or
  `AndroidNfcATransport`. All four are below-the-seam adapters that only delegate — ADR 0001's policy,
  device-verified instead.

### Task 4.4 — App picker (added after Phase 4, from user feedback)
- [x] The package-name text field became a searchable list of launchable apps
- [x] Label pre-filled from the chosen app only when still empty
- [x] Verified on device against the real installed list

---

## Phase 5 — Device verification

### Task 5.1 — On-device checks
- [x] Card B (`04 1C 4E 52 CE 7C 80`) already assigned by the user to open Google, surviving app
      restarts — partial AC1/AC2 evidence, and the store contents were read back off the device
- [ ] Assign "launch app" to card B (`04 1C 4E 52 CE 7C 80`), close the app, tap → app launches, no
      NFC Explorer UI (AC2)
- [ ] Tap card A, which has no assignment → nothing happens, no UI (AC3)
- [ ] Reboot the phone, tap again → assignment survived (AC1)
- [ ] Assign a bad package name, tap → clear failure, no crash (AC5)
- [ ] Assign a YouTube Music playlist URI, tap → opens in the app
- [ ] Note whether a MacroDroid chooser appears if MacroDroid is also configured
- [ ] Screenshots into `evidence/`; update `verification.md`
- [ ] **New, because of the presence check:** confirm a normal tap still works. A connection attempt now
      runs before the action, so a card whipped away the instant it is discovered will do nothing. This
      is the one regression risk the fix introduces and only hardware can settle it.
- Blocker: if the app does not launch on tap, check dispatch order before changing code — another app
  may be claiming the tag first.

### Task 5.2 — Docs
- [ ] README: the feature, the MacroDroid collision warning, and that no permission was added
- [ ] `state.json`, `execution.md`, `verification.md` for AC1–AC8

---

## Blockers that return to planning

- Wanting Do Not Disturb or Toggl. Both are explicit non-goals: one needs a special access grant, the
  other needs credential storage. Either is a new phase, not an extra task.
- Six bottom-nav destinations feeling wrong on the device. That is a design decision, not an
  implementation detail — stop and decide rather than cramming.
