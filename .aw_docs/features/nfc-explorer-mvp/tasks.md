# Tasks — NFC Explorer (Phase 1 MVP)

## Spec Brief

**Feature goal.** A developer-grade Android NFC inspection tool that dumps and decodes
MIFARE Ultralight MF0ICU1 memory, decodes its access-control bits, performs guarded
writes, and exports a session log — built so the decode logic is provable on the JVM
without NFC hardware.

**Spec brief.** Single `:app` Gradle module, `dev.shivam.nfcexplorer`, Clean Architecture
by package boundary. All tag I/O behind `UltralightTransport`; `FakeUltralightTransport`
emulates MF0ICU1 semantics (wrap-around reads, OR-on-write OTP, set-only lock bits,
`IOException` on locked write) so decoders, page-access classification and the write
guard are unit-tested for real. Reader mode over foreground dispatch. SAF for export.

**Architecture summary.** `ui -> domain <- data`; `domain` has zero `android.*` imports,
enforced by `DomainPurityTest`. Hilt for DI. `StateFlow` for all UI state. Tag pipeline
on `Dispatchers.IO`.

**Execution route.** `/aw:build`
**Expected execution mode.** Sequential through Phase 2; Phase 3 UI slices are a bounded
parallel wave.
**Expected chunk review mode.** Per-phase review; `kotlin-reviewer` at the end of
Phase 1 and Phase 4.
**`max_parallel_subagents`.** 3

---

## File Structure

### Create — build and manifest

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | Single `:app` module, repositories |
| `build.gradle.kts` | Root plugin declarations (`apply false`) |
| `gradle/libs.versions.toml` | Version catalog — AGP, Kotlin, KSP, Hilt, Compose BOM |
| `app/build.gradle.kts` | Android config, sdk levels, deps, `testOptions` |
| `app/proguard-rules.pro` | Release keep rules |
| `app/src/main/AndroidManifest.xml` | NFC permission + required feature, single activity |
| `.gitignore` | Standard Android ignores |

### Create — domain (pure Kotlin)

| File | Responsibility |
|---|---|
| `domain/model/TagIdentity.kt` | UID bytes, length, cascade levels, ATQA, SAK, `BccCheck` |
| `domain/model/ChipProfile.kt` | Geometry + `ChipCapability` set |
| `domain/model/TagTechnologies.kt` | `TechnologyInfo` list |
| `domain/model/MemoryDump.kt` | `PageSnapshot`, `ReadStatus` |
| `domain/model/LockAnalysis.kt` | Lock bytes, `BlockLockBit`, `PageRole`, `WriteVerdict`, `DynamicLockSupport` |
| `domain/model/WriteDecision.kt` | `Allowed` / `RequiresExpertMode` / `Blocked` |
| `domain/transport/TagTransport.kt` | `transceive`, `maxTransceiveLength`, `connect`, `close` |
| `domain/transport/UltralightTransport.kt` | `readPages(offset)` 16B wrapping, `writePage` |
| `domain/decoder/UidDecoder.kt` | BCC0/BCC1 computation, cascade levels |
| `domain/decoder/ManufacturerDecoder.kt` | UID byte-0 vendor table |
| `domain/decoder/ChipProfileResolver.kt` | ATQA/SAK/tech-set -> `ChipProfile` |
| `domain/decoder/StaticLockDecoder.kt` | `LOCK0`/`LOCK1` -> per-page `WriteVerdict` |
| `domain/decoder/MemoryRenderer.kt` | hex / binary / printable-ASCII |
| `domain/writer/WriteGuard.kt` | Pure write policy |
| `domain/repository/TagRepository.kt` | Interface over domain models |
| `domain/usecase/ReadTagUseCase.kt` | Full dump + decode orchestration |
| `domain/usecase/WritePageUseCase.kt` | Guard -> write -> verify re-read |
| `domain/usecase/ExportSessionUseCase.kt` | Session -> JSON/TXT bytes |

### Create — data, logging, ui, di, util

| File | Responsibility |
|---|---|
| `data/nfc/AndroidUltralightTransport.kt` | Adapter over `MifareUltralight` |
| `data/nfc/AndroidTagTransport.kt` | Adapter over `NfcA` for raw transceive |
| `data/nfc/TagTechnologyInspector.kt` | `Tag` -> `TagTechnologies` |
| `data/nfc/NfcReaderModeController.kt` | `enableReaderMode` -> `callbackFlow<Tag>` |
| `data/repository/TagRepositoryImpl.kt` | Transport + decoders, on `Dispatchers.IO` |
| `data/export/JsonSessionExporter.kt` | Structured JSON serialisation |
| `data/export/TextSessionExporter.kt` | Human-readable TXT + hex dump |
| `logging/LogEntry.kt` | Timestamp, level, category, message, payload |
| `logging/SessionLogger.kt` | Append-only, `StateFlow<List<LogEntry>>` |
| `ui/theme/*` (3 files) | `Color.kt`, `Type.kt`, `Theme.kt` — dark + light M3 |
| `ui/component/*` (4 files) | `SectionCard`, `HexPageRow`, `StatusChip`, `KeyValueRow` |
| `ui/scan/ScanScreen.kt`, `ScanViewModel.kt` | Reader-mode states, dump progress |
| `ui/taginfo/TagInfoScreen.kt` | Identity / chip / capabilities / technologies |
| `ui/memory/MemoryExplorerScreen.kt` | Monospaced page table |
| `ui/locks/LockAnalysisScreen.kt` | Bit grids + per-page verdicts |
| `ui/write/WriteSheet.kt`, `WriteViewModel.kt` | Arm-and-confirm write flow |
| `ui/log/SessionLogScreen.kt` | Log list + SAF export |
| `ui/navigation/NfcExplorerNavHost.kt` | Bottom-nav destinations |
| `MainActivity.kt`, `NfcExplorerApplication.kt` | Reader-mode host, `@HiltAndroidApp` |
| `di/AppModule.kt`, `di/NfcModule.kt` | Hilt wiring |
| `util/Hex.kt` | Hex parse/format |
| `res/values/strings.xml` | All user-facing copy |

### Create — tests

| File | Responsibility |
|---|---|
| `test/.../fake/FakeUltralightTransport.kt` | MF0ICU1 emulator: wrap-around, OTP OR, set-only locks, locked-write `IOException` |
| `test/.../fake/Mf0icu1Fixtures.kt` | Blank, fully-locked, block-locked, hotel-card-like dumps |
| `test/.../decoder/UidDecoderTest.kt` | Valid + corrupted BCC0/BCC1, cascade levels |
| `test/.../decoder/ManufacturerDecoderTest.kt` | NXP `0x04` + unknown vendor |
| `test/.../decoder/StaticLockDecoderTest.kt` | All-unlocked / all-locked / block-locked / mixed |
| `test/.../decoder/MemoryRendererTest.kt` | Hex, binary, printable vs non-printable ASCII |
| `test/.../writer/WriteGuardTest.kt` | Every rule branch incl. bad size and out-of-range |
| `test/.../usecase/ReadTagUseCaseTest.kt` | Wrap-around correctness, partial dump on tag loss |
| `test/.../usecase/WritePageUseCaseTest.kt` | Guard-gated write, read-back verification, blocked writes never reach transport |
| `test/.../export/JsonSessionExporterTest.kt` | JSON structure and round-trip equivalence |
| `test/.../export/TextSessionExporterTest.kt` | Hex-dump block formatting |
| `test/.../logging/SessionLoggerTest.kt` | Append-only, emission order |
| `test/.../architecture/DomainPurityTest.kt` | Fails on any `import android.` under `domain/` |
| `test/.../ui/ScanViewModelTest.kt` | State transitions via Turbine |
| `test/.../ui/WriteViewModelTest.kt` | Guard-gated state, expert-mode gating |

### Docs

| File | Responsibility |
|---|---|
| `README.md` | What it is, build/run, device requirement, architecture map |
| `docs/adr/0001-fakeable-tag-transport.md` | The transport-seam decision |
| `docs/mf0icu1-reference.md` | Memory map + lock-bit tables used by the decoder |

---

## Phase 0 — Toolchain proven

**Outcome:** an empty Compose app builds, installs, and launches on the Pixel 10. Every
version pin is validated against real Gradle resolution, not memory.

### Task 0.1 — Bootstrap Gradle wrapper
- Type: `infra` · Size: XS
- Files: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`
- [ ] Run the cached Gradle once to generate the wrapper:
      `~/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle wrapper --gradle-version 8.14.3 --distribution-type bin` in `/Users/shivamhl/Shivam/nfc`
- [ ] Verify: `./gradlew --version` reports Gradle 8.14.3, JVM 17
- Acceptance: `./gradlew --version` succeeds; no `gradle` on PATH is needed afterwards.
- Validation: `./gradlew --version`
- Commit: `chore: bootstrap gradle 8.14.3 wrapper`

### Task 0.2 — Project skeleton and version catalog
- Type: `config` · Size: S
- Files: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`,
  `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `.gitignore`
- [ ] `git init` — the repo is currently untracked, and save-point commits need it
- [ ] Write the catalog pinning AGP, Kotlin, KSP, Hilt, Compose BOM, JUnit, Turbine
- [ ] `app/build.gradle.kts`: `compileSdk 36`, `minSdk 26`, `targetSdk 36`, JDK 17,
      `buildFeatures.compose true`, Hilt + KSP plugins
- [ ] Manifest: NFC permission, `uses-feature nfc required="true"`, single
      `MainActivity` with `exported="true"` and LAUNCHER intent filter
- [ ] Minimal `MainActivity` rendering a Material 3 `Scaffold` placeholder
- [ ] Run `./gradlew :app:assembleDebug`. **If a version pin fails to resolve, correct
      it from the actual error output** — do not guess a second time.
- Acceptance: `assembleDebug` green; APK produced.
- Validation: `./gradlew :app:assembleDebug`
- Commit: `chore: android project skeleton with version catalog`

### Task 0.3 — Device install proof
- Type: `infra` · Size: XS
- [ ] Confirm the device is back: `adb devices -l` shows `64111VDCR000BJ` as `device`.
      It dropped off during planning — if absent, reseat USB, unlock the phone, and
      accept the debugging prompt.
- [ ] `./gradlew :app:installDebug`
- [ ] `adb shell am start -n dev.shivam.nfcexplorer/.MainActivity`
- [ ] `adb shell pm list features | grep nfc` confirms `android.hardware.nfc`
- Acceptance: app launches on device; NFC feature present.
- Validation: the `am start` above returns no error and the placeholder renders.
- Blocker: no device -> continue to Phase 1 (JVM-only) and record the gap; do **not**
  fake device evidence.
- Commit: none (verification only — folded into 0.2's save point).

---

## Phase 1 — Domain model and pure decoders (RED -> GREEN -> REFACTOR)

**Outcome:** every MF0ICU1 decode rule is implemented and proven on the JVM with zero
hardware. This phase carries the most value and the most risk of silent wrongness.

Reference `docs/mf0icu1-reference.md` (written in Task 1.1) as the single source of truth
for the memory map and bit tables; `spec.md` holds the same tables.

### Task 1.1 — Chip reference doc and domain models
- Type: `docs` + `code` · Size: M
- Files: `docs/mf0icu1-reference.md`, all `domain/model/*.kt`, `domain/transport/*.kt`, `util/Hex.kt`
- [ ] Write `docs/mf0icu1-reference.md` with the page map and the `LOCK0`/`LOCK1` bit tables
- [ ] Define the model + transport types exactly as specified in `spec.md`
- [ ] `util/Hex.kt`: `ByteArray.toHex(separator)`, `String.parseHexByte(): Byte?`
- Acceptance: `./gradlew :app:compileDebugKotlin` green; no `android.*` import in `domain/`.
- Validation: `./gradlew :app:compileDebugKotlin`
- Commit: `feat: MF0ICU1 domain model and transport interfaces`
- `parallel_candidate`: no (everything downstream depends on these types)

### Task 1.2 — Fake transport and fixtures (RED enabler)
- Type: `code` · Size: M
- Files: `test/.../fake/FakeUltralightTransport.kt`, `test/.../fake/Mf0icu1Fixtures.kt`
- [ ] Implement the fake over a `ByteArray(64)`: `readPages(offset)` returns 16 bytes
      **with wrap-around** (`(offset*4 + i) % 64`); `writePage` throws `IOException` when
      the page's lock bit is set; page 3 writes OR into existing bytes; page 2 lock-byte
      writes OR (set-only); pages 0–1 throw `IOException`
- [ ] Add a `failAfterPage` hook to simulate `TagLostException` mid-dump
- [ ] Fixtures: `blank()`, `fullyLocked()`, `blockLocked()`, `hotelCardLike()`
      (pages 3–15 locked via `L_*` bits, opaque payload in 4–15)
- [ ] Add a self-test asserting the fake's own wrap-around: reading page 14 yields
      pages 14, 15, 0, 1
- Acceptance: fake self-test green; the fake enforces MF0ICU1 semantics, so later tests
  are meaningful rather than tautological.
- Validation: `./gradlew :app:testDebugUnitTest --tests '*FakeUltralightTransport*'`
- Commit: `test: MF0ICU1 fake transport and dump fixtures`

### Task 1.3 — UID and manufacturer decoding
- Type: `code` · Size: S
- Files: `domain/decoder/UidDecoder.kt`, `ManufacturerDecoder.kt`, + their tests
- [ ] **RED** — write `UidDecoderTest`: `BCC0 == 0x88 xor SN0 xor SN1 xor SN2`,
      `BCC1 == SN3 xor SN4 xor SN5 xor SN6`, 7-byte UID -> 2 cascade levels,
      4-byte UID -> 1, and a corrupted-BCC case reporting invalid
- [ ] **RED** — `ManufacturerDecoderTest`: `0x04` -> NXP; unknown byte -> `Unknown(0xNN)`
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*UidDecoderTest*' --tests '*ManufacturerDecoderTest*'` — **confirm it fails**
- [ ] **GREEN** — implement both decoders minimally
- [ ] Rerun the same command — green
- [ ] **REFACTOR** — extract the vendor table to a private `val` map; keep tests green
- Acceptance: BCC verification and vendor lookup correct for valid and corrupt inputs.
- Validation: the `--tests` command above
- Commit: `feat: UID/BCC and manufacturer decoding`
- `parallel_group`: `decoders` · `parallel_ready_when`: Task 1.1 merged ·
  `parallel_write_scope`: `domain/decoder/UidDecoder.kt`, `ManufacturerDecoder.kt` + tests

### Task 1.4 — Static lock decoding and page access classification
- Type: `code` · Size: M — the highest-value decoder in Phase 1
- Files: `domain/decoder/StaticLockDecoder.kt`, `StaticLockDecoderTest.kt`
- [ ] **RED** — `StaticLockDecoderTest` covering:
      `LOCK0=0x00, LOCK1=0x00` -> pages 4–15 `WRITABLE`, 0–1 `HARDWARE_READ_ONLY`,
      2 `LOCK_CONTROL`, 3 `OTP_ONE_WAY`;
      `LOCK0=0xFF, LOCK1=0xFF` -> pages 3–15 `PERMANENTLY_LOCKED`;
      `LOCK0=0x08` -> only page 3 locked (`L_OTP`);
      `LOCK0=0x10` -> only page 4 locked (`L_4`);
      `LOCK1=0x80` -> only page 15 locked (`L_15`);
      `LOCK0=0x02` -> `BL_9_4` set, pages 4–9 lock bits frozen and reported as such;
      and `DynamicLockSupport.NotSupportedByChip` for MF0ICU1
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*StaticLockDecoderTest*'` — **confirm it fails**
- [ ] **GREEN** — implement the bit mapping from `spec.md`'s table
- [ ] Rerun — green
- [ ] **REFACTOR** — express the mapping as a declarative bit->page table rather than a
      chain of ifs; tests stay green
- Acceptance: every documented bit maps to the correct page verdict; block-locking is
  reported distinctly from locking.
- Validation: the `--tests` command above
- Commit: `feat: static lock bit decoding and page access classification`
- `parallel_group`: `decoders` · `parallel_write_scope`: `StaticLockDecoder.kt` + test

### Task 1.5 — Memory rendering
- Type: `code` · Size: S
- Files: `domain/decoder/MemoryRenderer.kt`, `MemoryRendererTest.kt`
- [ ] **RED** — hex `04 A2 55 71`; binary `00000100 10100010 ...`; ASCII maps printable
      `0x20..0x7E` and substitutes `·` elsewhere; a `null`-bytes page renders its
      `ReadStatus` label, never zeros
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*MemoryRendererTest*'` — **confirm it fails**
- [ ] **GREEN** — implement
- [ ] Rerun — green
- Acceptance: unread pages are never rendered as `00 00 00 00`.
- Validation: the `--tests` command above
- Commit: `feat: hex/binary/ASCII memory rendering`
- `parallel_group`: `decoders` · `parallel_write_scope`: `MemoryRenderer.kt` + test

### Task 1.6 — Write guard
- Type: `code` · Size: S
- Files: `domain/writer/WriteGuard.kt`, `WriteGuardTest.kt`
- [ ] **RED** — `WriteGuardTest` for every branch: pages 0–1 `Blocked`; page 2
      `RequiresExpertMode`; page 3 `RequiresExpertMode`; page 7 unlocked `Allowed`;
      page 7 locked `Blocked`; `data.size = 3` `Blocked`; page 16 `Blocked`;
      expert mode does **not** unblock pages 0–1 or a locked page
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*WriteGuardTest*'` — **confirm it fails**
- [ ] **GREEN** — implement the pure policy
- [ ] Rerun — green
- Acceptance: I3 holds — no path yields `Allowed` for a UID or locked page, expert mode
  or not.
- Validation: the `--tests` command above
- Commit: `feat: guarded write policy`
- `parallel_group`: `decoders` · `parallel_write_scope`: `WriteGuard.kt` + test

### Task 1.7 — Domain purity enforcement and ADR
- Type: `code` + `docs` · Size: S
- Files: `test/.../architecture/DomainPurityTest.kt`, `docs/adr/0001-fakeable-tag-transport.md`
- [ ] **RED** — `DomainPurityTest` walks `app/src/main/java/dev/shivam/nfcexplorer/domain`,
      reads each `.kt`, fails listing any file containing `import android.`.
      Prove it works: temporarily add `import android.util.Log` to a domain file, watch
      the test fail, then remove it.
- [ ] **GREEN** — test passes against the real domain package
- [ ] Write ADR 0001: context (NFC is hardware-bound, emulator has no NFC), decision
      (transport interface + MF0ICU1-semantics fake), consequences, alternatives
      considered (Robolectric NFC shadows; device-only testing) and why they lose
- Acceptance: I1 is machine-enforced, and the constraint is documented for later phases.
- Validation: `./gradlew :app:testDebugUnitTest --tests '*DomainPurityTest*'`
- Commit: `test: enforce domain purity; docs: ADR 0001 fakeable tag transport`

### Task 1.8 — Phase 1 review gate
- [ ] `./gradlew :app:testDebugUnitTest` — full suite green
- [ ] Run `kotlin-reviewer` over `domain/` and the test sources
- [ ] Address correctness findings before Phase 2
- Acceptance: full JVM suite green; review findings resolved or explicitly deferred.
- Validation: `./gradlew :app:testDebugUnitTest`

---

## Phase 2 — Android NFC transport and read pipeline

**Outcome:** a real tap on the Pixel 10 produces a correct, wrap-aware 16-page dump.

### Task 2.1 — Session logger
- Type: `code` · Size: S
- Files: `logging/LogEntry.kt`, `SessionLogger.kt`, `SessionLoggerTest.kt`
- [ ] **RED** — `SessionLoggerTest`: entries emit in order via Turbine; the exposed list
      is append-only (no entry mutated or removed); timestamps are injected via a clock
      so tests are deterministic
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*SessionLoggerTest*'` — **confirm it fails**
- [ ] **GREEN** — implement with `MutableStateFlow<List<LogEntry>>` and an injected
      time source
- Acceptance: I4 holds and is tested.
- Validation: the `--tests` command above
- Commit: `feat: append-only session logger`

### Task 2.2 — Read pipeline use case with wrap-around handling
- Type: `code` · Size: M — this is where I2 is won or lost
- Files: `domain/usecase/ReadTagUseCase.kt`, `ReadTagUseCaseTest.kt`
- [ ] **RED** — `ReadTagUseCaseTest` against `FakeUltralightTransport`:
      a 16-page dump reports pages 0–15 with correct bytes at correct indices;
      the wrapped tail of the final `readPages` call is **discarded**, not appended as
      pages 16–17; `failAfterPage(7)` yields pages 0–7 `OK` and 8–15 `NOT_ATTEMPTED`;
      a NAKing page is `NAK_REFUSED` and the dump continues
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*ReadTagUseCaseTest*'` — **confirm it fails**
- [ ] **GREEN** — read in 4-page strides, clamp to `pageCount`, discard wrap; catch
      per-page failures into `ReadStatus`
- [ ] **REFACTOR** — extract the stride loop so Phase 2 chips with other geometries reuse it
- Acceptance: AC2 and I2 proven on the JVM before any hardware is involved.
- Validation: the `--tests` command above
- Commit: `feat: wrap-aware page read pipeline with per-page status`

### Task 2.3 — Android transport adapters and technology inspector
- Type: `code` · Size: M
- Files: `data/nfc/AndroidUltralightTransport.kt`, `AndroidTagTransport.kt`,
  `TagTechnologyInspector.kt`, `data/repository/TagRepositoryImpl.kt`,
  `domain/repository/TagRepository.kt`, `di/NfcModule.kt`
- [ ] Adapter over `MifareUltralight`: `get(tag)` null-safe, `connect`/`close`,
      `readPages`, `writePage`, `maxTransceiveLength`
- [ ] `TagTechnologyInspector`: enumerate `tag.techList`, pull `maxTransceiveLength` and
      timeout where each tech exposes them, read `NfcA.atqa`/`sak`
- [ ] `TagRepositoryImpl`: run the pipeline on `Dispatchers.IO`, log every step,
      translate `TagLostException` / `IOException` / `SecurityException` into named
      domain failures
- [ ] Hilt wiring in `NfcModule`
- Acceptance: `./gradlew :app:assembleDebug` green; no deprecated NFC API usage.
- Validation: `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`
- Commit: `feat: Android NFC transport adapters and tag repository`
- Note: thin adapters over final framework classes; correctness is proven on device in
  Task 2.5, not by mocking `MifareUltralight`.

### Task 2.4 — Reader mode controller
- Type: `code` · Size: S
- Files: `data/nfc/NfcReaderModeController.kt`, `MainActivity.kt`, `NfcExplorerApplication.kt`
- [ ] `callbackFlow<Tag>` wrapping `enableReaderMode` with
      `FLAG_READER_NFC_A|B|F|V or SKIP_NDEF_CHECK or NO_PLATFORM_SOUNDS`,
      `EXTRA_READER_PRESENCE_CHECK_DELAY = 250`, `awaitClose { disableReaderMode() }`
- [ ] Bind the flow to the activity lifecycle so reader mode is active only while resumed
- [ ] Distinguish adapter-null (unsupported) from adapter-disabled
- Acceptance: `assembleDebug` green; no `enableForegroundDispatch` anywhere.
- Validation: `./gradlew :app:assembleDebug`
- Commit: `feat: NFC reader mode controller`

### Task 2.5 — Device read proof
- Type: `infra` · Size: S — first real hardware evidence
- [ ] `./gradlew :app:installDebug`
- [ ] `adb logcat -c` then `adb logcat -s NfcExplorer:V` in a second shell
- [ ] Tap the MF0ICU1 hotel card; capture the logged dump
- [ ] Verify against the tag: UID matches NXP TagInfo's reading, BCC0/BCC1 report valid,
      16 pages attempted, page indices sane, lock bytes non-zero if the card is locked
- [ ] Record the raw dump in `.aw_docs/features/nfc-explorer-mvp/evidence/hotel-card-dump.txt`
- Acceptance: AC1–AC4 demonstrated on real hardware; the original question ("what is on
  this card?") gets a factual answer.
- Validation: captured logcat dump + saved evidence file
- Blocker: if the dump disagrees with the JVM expectation, **stop and investigate** —
  do not paper over it in the UI.
- Commit: `docs: hotel card dump evidence`

---

## Phase 3 — UI (bounded parallel wave)

**Outcome:** all four destinations render real session data in dark and light M3.

`parallel_ready_when`: Tasks 3.1 and 3.2 are merged (theme + components + nav are the
shared contract). Then 3.3–3.6 fan out. `max_parallel_subagents: 3`.

### Task 3.1 — Theme and shared components
- Type: `code` · Size: M
- Files: `ui/theme/Color.kt`, `Type.kt`, `Theme.kt`, `ui/component/*` (4), `res/values/strings.xml`
- [ ] M3 dark + light schemes with dynamic color where available; semantic roles per `design.md`
- [ ] Monospace `bodyMedium` style for binary data
- [ ] `SectionCard` (expandable, `AnimatedVisibility`), `KeyValueRow`, `StatusChip`
      (pass/fail/absent), `HexPageRow` (page, hex, secondary column, access chip)
- [ ] Seed `strings.xml`; no hardcoded literals in composables
- Acceptance: components preview in both themes; 48 dp touch targets.
- Validation: `./gradlew :app:assembleDebug`
- Commit: `feat: M3 theme and shared UI components`
- `parallel_write_scope`: `ui/theme/`, `ui/component/`, `res/values/strings.xml`

### Task 3.2 — Navigation shell and scan screen
- Type: `code` · Size: M
- Files: `ui/navigation/NfcExplorerNavHost.kt`, `ui/scan/ScanScreen.kt`, `ScanViewModel.kt`,
  `ui/ScanViewModelTest.kt`, session-scoped state holder
- [ ] **RED** — `ScanViewModelTest` via Turbine: `NfcUnsupported`, `NfcDisabled`,
      `WaitingForTag`, `Reading(page, total)`, `Captured`, `Failed(reason)` transitions
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*ScanViewModelTest*'` — **confirm it fails**
- [ ] **GREEN** — ViewModel over the reader-mode flow + `ReadTagUseCase`
- [ ] Bottom nav with Tag / Memory / Locks / Log; session state survives navigation
- [ ] Scan pulse animation; settings deep-link for the disabled state
- Acceptance: state machine tested; re-tap starts a new session and appends to the log.
- Validation: the `--tests` command above, then `./gradlew :app:assembleDebug`
- Commit: `feat: navigation shell and scan screen`
- `parallel_write_scope`: `ui/navigation/`, `ui/scan/`

### Task 3.3 — Tag info screen
- Type: `code` · Size: S
- [ ] Four `SectionCard`s per `design.md`; BCC pass/fail chips show computed vs stored
- [ ] Unsupported capabilities render as muted "not supported by this chip", never blank
- Acceptance: AC1 renders fully for the hotel card in both themes.
- Validation: `./gradlew :app:assembleDebug` + on-device render
- Commit: `feat: tag info screen`
- `parallel_group`: `ui-screens` · `parallel_write_scope`: `ui/taginfo/`

### Task 3.4 — Memory explorer screen
- Type: `code` · Size: M
- [ ] Monospaced page table, colour-coded access column, expandable per-byte detail
- [ ] Failed pages show `ReadStatus` in error color — never zeros
- [ ] ASCII / binary / decimal toggle; own `horizontalScroll` container
- Acceptance: AC2 visible; page indices match Task 2.5's evidence dump exactly.
- Validation: `./gradlew :app:assembleDebug` + on-device render
- Commit: `feat: memory explorer screen`
- `parallel_group`: `ui-screens` · `parallel_write_scope`: `ui/memory/`

### Task 3.5 — Lock analysis screen
- Type: `code` · Size: M
- [ ] `LOCK0`/`LOCK1` bit grids with per-bit names and set/clear state
- [ ] Per-page verdict list with plain-English explanation naming the responsible bit
- [ ] Explicit "dynamic lock bits not supported by MF0ICU1" panel
- [ ] Block-locking panel describing what each `BL_*` bit freezes
- Acceptance: AC4 visible; the screen teaches the bit layout, not just the result.
- Validation: `./gradlew :app:assembleDebug` + on-device render
- Commit: `feat: lock analysis screen`
- `parallel_group`: `ui-screens` · `parallel_write_scope`: `ui/locks/`

### Task 3.6 — Session log screen
- Type: `code` · Size: S
- [ ] Reverse-chronological list, level filter chips, expandable payloads
- Acceptance: every Phase 2 operation appears with ms-precision timestamps.
- Validation: `./gradlew :app:assembleDebug` + on-device render
- Commit: `feat: session log screen`
- `parallel_group`: `ui-screens` · `parallel_write_scope`: `ui/log/`

---

## Phase 4 — Guarded write

**Outcome:** a page can be written on real hardware behind arm-and-confirm, and every
refusal and exception is explained.

### Task 4.1 — Write use case with verification
- Type: `code` · Size: S
- Files: `domain/usecase/WritePageUseCase.kt`, + test
- [ ] **RED** — test against the fake: `Allowed` writes then re-reads and confirms bytes;
      a locked page surfaces `IOException` as a named failure; a `Blocked` decision never
      reaches the transport (assert the fake records no write)
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*WritePageUseCaseTest*'` — **confirm it fails**
- [ ] **GREEN** — guard -> write -> verify re-read; log all three steps
- Acceptance: I3 holds end-to-end; write success is proven by re-read, not assumed.
- Validation: the `--tests` command above
- Commit: `feat: write page use case with read-back verification`

### Task 4.2 — Write sheet and expert mode
- Type: `code` · Size: M
- Files: `ui/write/WriteSheet.kt`, `WriteViewModel.kt`, `WriteViewModelTest.kt`
- [ ] **RED** — `WriteViewModelTest`: `Allowed` requires arm-then-confirm (one tap never
      writes); `RequiresExpertMode` keeps the write path disabled until expert mode is on;
      `Blocked` renders no write control; expert mode defaults off
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*WriteViewModelTest*'` — **confirm it fails**
- [ ] **GREEN** — implement per `design.md`; hex inputs validated `00`–`FF` with live diff
- [ ] Expert-mode toggle in app-bar overflow, session-scoped, never persisted
- Acceptance: AC5 satisfied; irreversible operations need two taps and a non-default toggle.
- Validation: the `--tests` command above
- Commit: `feat: guarded write sheet with expert mode gating`

### Task 4.3 — Device write proof
- Type: `infra` · Size: S
- [ ] **Use a blank Ultralight/NTAG tag, not the hotel card**
- [ ] Write a known pattern to a writable user page; confirm read-back matches
- [ ] Attempt a write to page 0 — confirm it is refused with an explanation
- [ ] Attempt a write to a locked page on the hotel card — confirm the diagnostic names
      the lock bit responsible
- [ ] Do **not** exercise expert mode against any tag you care about; lock-bit and OTP
      writes are permanent
- [ ] Record results in `evidence/write-proof.txt`
- Acceptance: AC5 demonstrated on hardware, with no tag destroyed.
- Validation: saved evidence file
- Commit: `docs: write proof evidence`

### Task 4.4 — Phase 4 review gate
- [ ] Run `kotlin-reviewer` over `writer/`, `ui/write/`, and the write use case
- [ ] Address findings on the irreversible-operation path with priority
- Acceptance: no unresolved correctness finding on any write path.

---

## Phase 5 — Export

**Outcome:** a session leaves the device as valid JSON and readable TXT.

### Task 5.1 — Exporters
- Type: `code` · Size: S
- Files: `data/export/JsonSessionExporter.kt`, `TextSessionExporter.kt`,
  `domain/usecase/ExportSessionUseCase.kt`, + tests
- [ ] **RED** — JSON round-trips to an equivalent structure and contains identity, chip,
      full dump, lock analysis and all log entries; TXT contains a classic hex-dump block
- [ ] Run `./gradlew :app:testDebugUnitTest --tests '*Exporter*'` — **confirm it fails**
- [ ] **GREEN** — implement both
- Acceptance: AC6's format correctness proven on the JVM.
- Validation: the `--tests` command above
- Commit: `feat: JSON and TXT session exporters`

### Task 5.2 — SAF export wiring
- Type: `code` · Size: S
- [ ] `ACTION_CREATE_DOCUMENT` via `rememberLauncherForActivityResult`; no storage permission
- [ ] Default filenames `nfc-session-<uid>-<timestamp>.json` / `.txt`
- [ ] Write on `Dispatchers.IO`; report failures in the log
- Acceptance: file lands in the chosen location and opens correctly.
- Validation: on-device export, then `adb shell cat` or pull the file and inspect
- Commit: `feat: SAF-based session export`

---

## Phase 6 — Verification and closeout

**Outcome:** evidence recorded, docs written, Phase 2 handoff clean.

### Task 6.1 — Full suite and device smoke checklist
- [ ] `./gradlew :app:testDebugUnitTest` — full suite green
- [ ] `./gradlew :app:assembleDebug` — green, no NFC deprecation warnings
- [ ] On-device checklist: all four destinations render in **dark** and **light**;
      NFC-disabled state deep-links correctly; a mid-read tag pull yields a partial dump
      rather than a crash; re-tap starts a new session; TalkBack reads hex sensibly
- [ ] Record everything in `verification.md`, separating JVM evidence from device evidence
- Acceptance: AC1–AC8 each marked pass with its evidence source named.

### Task 6.2 — README and closeout
- [ ] `README.md`: purpose, architecture map, build/run, **device requirement (no emulator
      NFC)**, and the write-safety warning
- [ ] Note in `README.md` that Phase 2 chip support slots in behind `ChipProfileResolver`
      and the transport interface
- Acceptance: a fresh reader can build, run, and understand the layering.
- Commit: `docs: README and Phase 1 closeout`

---

## Blockers That Return to Planning

- The hotel card turns out to be UID-only with no readable payload → the read goal is
  *answered*, not blocked; but if you then want UID-based analysis, that is new scope.
- MF0ICU1 read behaviour contradicts the datasheet-derived tables in `spec.md` → stop,
  re-derive from the real dump, update `spec.md` before touching the decoder.
- A desire to write lock bits or OTP "to see what happens" → that is irreversible
  hardware modification and needs an explicit decision, not an implementation step.
- Any request to bypass authentication or clone a credential → out of scope by design;
  return to planning rather than extending the tool that way.
