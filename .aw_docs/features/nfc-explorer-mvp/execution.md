# Execution — NFC Explorer (Phase 1 MVP)

Route `/aw:build`, mode `code`. Approved inputs: `prd.md`, `spec.md`, `design.md`, `tasks.md`.
Execution sequential (no parallel waves used yet; the `decoders` wave was run in order because
each slice was small and the review gate sits at the end of the phase).

## Phase 0 — Toolchain proven ✅

| Task | Status | Pre-change proof | Validation | Commit |
|---|---|---|---|---|
| 0.1 Bootstrap wrapper | done | no `gradle` on PATH; cached 8.14.3 dist located | `./gradlew --version` → Gradle 8.14.3, JVM 17.0.18 | `3f4454d` |
| 0.2 Skeleton + catalog | done | empty non-git directory | `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL | `72a24f6` |
| 0.3 Device install proof | done | device re-appeared on adb | installed + launched on Pixel 10 (Android 17), PID 22850, no FATAL in logcat, screenshot captured | verification only |

**Deviations.**

- Gradle 8.14 refuses to run the `wrapper` task without a settings file, so `settings.gradle.kts`
  was written before bootstrapping. Same outcome, order swapped.
- `gradle.properties` was missing from the plan's file list. `assembleDebug` failed on
  `android.useAndroidX` not being set; added and re-validated.
- Dropped `material-icons-extended`. It is deprecated and no longer BOM-managed, resolving to
  1.7.8 against Compose 1.9.0. The user's brief explicitly asks to avoid deprecated APIs, so
  nav icons will be local vector drawables (Task 3.1). Verified all `androidx.compose`
  artifacts now resolve at 1.9.0 / material3 1.3.2 with no skew.

**Version pins validated by real resolution, not assumption:** AGP 8.11.0, Kotlin 2.2.10,
KSP 2.2.10-2.0.2, Hilt 2.57, Compose BOM 2025.08.00, `compileSdk`/`targetSdk` 36, `minSdk` 26.

**Task 0.3 blocker.** The Pixel 10 (`64111VDCR000BJ`) enumerated once during planning and then
dropped off adb; it was absent at Phase 0. Per the plan's blocker rule, execution continued to
Phase 1 (entirely JVM) and the gap is recorded rather than papered over. No device evidence has
been claimed. Reseat the cable and unlock the phone before Phase 2 Task 2.5.

## Phase 1 — Domain model and pure decoders ✅ (pending review findings)

| Task | Status | RED proof | GREEN | Commit |
|---|---|---|---|---|
| 1.1 Models + chip reference | done | n/a (type definitions) | `compileDebugKotlin` + purity grep | `1101464` |
| 1.2 Fake transport + fixtures | done | n/a (test infrastructure) | 16/16 self-tests | `abe2b66` |
| 1.3 UID + manufacturer | done | **10/10 failed** on `NotImplementedError` | 10/10 + 5/5 | `4fd0859` |
| 1.4 Static lock decoding | done | **21/21 failed** on `NotImplementedError` | 22/22 | `376602e` |
| 1.5 Memory rendering | done | **16/16 failed** on `NotImplementedError` | 16/16 | `42455f6` |
| 1.6 Write guard | done | **18/18 failed** on `NotImplementedError` | 18/18 | `8dd3345` |
| 1.7 Purity guard + ADR | done | injected `import android.util.Log` → suite failed naming `ByteBlock.kt:3` | 4/4, green on restore | `5d218e2` |
| 1.8 Review gate | done | reviewer verdict BLOCK (0 crit, 0 high, 3 med, 3 low) | 132/132 after fixes | `81eec6c` |

**Suite after review fixes: 132 tests, 0 failures.**

RED was made genuine rather than nominal: stub bodies threw `NotImplementedError` so the
failures were real runtime failures, not compile errors that merely prove a symbol is absent.

### Deviations from `spec.md`, each deliberate

1. **`ByteBlock` instead of raw `ByteArray` in models.** A `ByteArray` in a `data class`
   compares by identity and stays mutable after construction — model equality would be
   silently wrong in tests and a caller could rewrite a value object in place. `ByteBlock`
   copies on every entry and exit.
2. **Transport-level exception types** (`TagFieldLostException`, `TagNakException`,
   `TagNotConnectedException`). The read pipeline must tell "tag left the field" from "tag
   refused" to assign per-page status, and `domain` cannot import
   `android.nfc.TagLostException`. Without these, `ReadStatus.TAG_LOST` would be unreachable.
3. **`WriteVerdict.UNKNOWN_LOCK_STATE` added.** An unreadable page `0x02` must not report
   pages as writable. Guessing invites a write that silently fails, or one that unexpectedly
   succeeds.
4. **`WriteDecision.Allowed` carries `acknowledgedRisk`.** Enabling expert mode must not make
   the danger invisible; the reason travels with the approval so the UI keeps warning.
5. **`TagIdentity.cascadeLevels` widened to `Int?`.** An off-standard UID length reports
   "unknown" rather than 0, consistent with absence being explicit everywhere else.
6. **No separate `ManufacturerDecoder.kt`.** The lookup is a table on `Manufacturer` itself;
   a separate class would add a layer around one map access.
7. **`MemoryRenderer` returns null for unreadable pages instead of a status label.** Status
   wording is user-facing text belonging in `strings.xml`, and `domain` has no resource
   access. This also makes rendering an unread page as `00 00 00 00` structurally impossible.
8. **Purity guard extended to `androidx.*`** and made self-testing, since a guard that can
   pass vacuously is worse than none.

### Review outcome (Task 1.8)

`kotlin-reviewer` cross-checked every `LOCK0`/`LOCK1` mask, page mapping and block-lock range
against `docs/mf0icu1-reference.md` and **found no bit-math defect** — the highest-risk area
came back clean, and independently confirmed the decoder/fake cross-check as strong evidence.
Verdict was BLOCK on 3 MEDIUM findings. All six findings fixed:

1. **Behaviour change (filed LOW, treated as most important).** Page `0x02` was writable under
   expert mode even when the lock page could not be read, contradicting `WriteGuard`'s own
   documented rule; the invariant sweep excluded page 2 and so never caught it. Reproduced RED,
   then fixed: an unreadable lock page now yields `UNKNOWN_LOCK_STATE` for page 2 as well.
   Sweep widened to `page >= 2`.
2. **Tautological `ManufacturerTest`.** `fromUidByte0(code).code == code` holds for both `Known`
   and `Unknown`, so it could never catch a bad table key. Replaced with tests iterating the
   table's own keys via a new `Manufacturer.knownCodes`.
3. **`!!` in test code.** Replaced with named `requireNotNull` helpers that report which page or
   field was missing. Production and test code are now both `!!`-free (closes deferred D1).
4. **Untested logic-bearing models.** Added `MemoryDumpTest` (16), `ChipProfileTest` (4),
   `TagTechnologiesTest` (5). `MemoryDump` now *enforces* page ordering and page width via
   `init` requires rather than assuming them, so `readableBytes()` cannot silently scramble.
5. Removed dead `ByteBlock.slice`/`hasMask`.
6. Named `LOCK0_LAST_USER_PAGE`; softened a doc forward reference.

### Honest gaps

- `ManufacturerTest` is characterisation-only, **not** RED-first: the lookup shipped with the
  models in Task 1.1. Recorded rather than presented as TDD.
- No ktlint/Detekt configured yet, though the Kotlin rules call for it. Candidate for the
  review pass or Phase 6.
- `Manufacturer`'s vendor table is a deliberate subset of the ISO/IEC 7816-6 registry;
  unlisted codes report `Unknown(code)` with the raw value rather than a guess.

## Simplification applied per slice

- Task 0.2: removed a deprecated, version-skewed dependency rather than carrying it.
- Task 1.2: added `Mf0icu1Fixtures.bytes(vararg Int)` after `0x90` failed to compile as a
  signed `Byte` — the alternative was `.toByte()` noise through a file that is almost entirely
  hex literals, which would hide real mistakes.
- Task 1.4: the plan suggested refactoring the bit mapping into a declarative table; the
  `buildList` + `when` form is already declarative and further indirection would not pay for
  itself. Judged unnecessary rather than skipped.
- Task 1.5: `MemoryRenderer` delegates to `util/Hex.kt` rather than re-implementing hex and
  binary formatting.

## Phase 2 — Android NFC transport and read pipeline (in progress)

| Task | Status | RED proof | GREEN | Commit |
|---|---|---|---|---|
| 2.1 Session logger | done | **11/11 failed** on `NotImplementedError` | 11/11 | `c916557` |
| 2.2 Read pipeline (I2) | done | **16/16 failed** on `NotImplementedError` | 16/16 | `58c6434` |
| 2.3 Android transport adapters | done | ChipProfileResolver **10/10 failed** on `NotImplementedError` | 160/160, `assembleDebug` green, Hilt codegen OK | `351c937` |
| 2.4 Reader mode controller | done | n/a (framework wiring) | `assembleDebug` green, installed and launched | `a182824` |
| 2.5 Device read proof | **blocked** | — | needs the phone unlocked and a tag tapped | — |

## Phase 4 — Guarded write (started out of plan order)

| Task | Status | RED proof | GREEN | Commit |
|---|---|---|---|---|
| 4.1 Write use case | done | **15/15 failed** on `NotImplementedError` | 15/15 | `61e9f12` |

Taken out of order deliberately: Task 2.5 is blocked on human action and every Phase 3 slice is
UI whose verification also needs the phone unlocked. Task 4.1 is pure JVM logic with full proof
available now, so it was the only remaining slice that could be finished honestly.

**Suite: 175 tests, 0 failures. `assembleDebug` green.**

**Invariant I2 closed.** The wrap-around clamp is tested with chip profiles claiming 14 and 15
pages over the 16-page fake — striding by 4 across exactly 16 pages never wraps, so an
MF0ICU1-only test would have proven nothing. Partial dumps distinguish `TAG_LOST` (stride was
attempted) from `NOT_ATTEMPTED` (never asked for).

Added `TagReport`/`TagPresentation` (not in `spec.md`): the read pipeline needs one value
carrying identity + dump + locks, and it must exist in partial form, so there is deliberately
no "failed" variant — a partial answer is still an answer.

## Remaining build scope

Phase 2 Tasks 2.3–2.5, Phase 3 (UI), Phase 4 (guarded write), Phase 5 (export), Phase 6
(verification and closeout).

## Blocker B2 — Task 2.5 needs human action

The app is installed and running on the Pixel 10 (PID 13904) with reader mode wired, but
`dumpsys nfc` reports `mScreenState=ON_LOCKED`. Reader mode is only enabled while the activity is
RESUMED, which a locked screen prevents, so no tag can be read. Nothing has been captured and no
device evidence is claimed.

To clear it: unlock the phone with NFC Explorer in front, hold the tag against the upper-middle
back for ~2 seconds. Logcat has a ring buffer, so `adb logcat -d -s NfcExplorer:V` recovers the
dump afterwards — no live capture needs to be running.

Also recorded: a first attempt at a live capture used `timeout`, which does not exist on macOS,
so it produced an empty file. Superseded by the ring-buffer approach above.

## Chip identification honesty (Task 2.3)

`ChipProfileResolver` deliberately does **not** claim MF0ICU1. Android reports `TYPE_ULTRALIGHT`
for MF0ICU1, Ultralight EV1 *and* every NTAG21x — same ATQA `0x0044`, same SAK `0x00` — and
separating them needs `GET_VERSION`, which the original Ultralight does not implement. So it
returns a 16-page **floor** with `geometryConfirmed = false`. Sixteen pages is readable on every
family member so a dump can never over-read; it may under-read an NTAG216, which is exactly what
the flag tells the UI to say. Claiming MF0ICU1 outright would have silently hidden 852 bytes on
an NTAG216. Phase 2's `GET_VERSION` probe resolves it, and a NAK from that probe is itself
evidence of an original Ultralight.

## Next

Task 2.5 once the phone is unlocked and a tag is tapped. Then Phase 3 (UI), the rest of Phase 4,
and Phase 5.
