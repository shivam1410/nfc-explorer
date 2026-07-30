# Execution — NFC Explorer (Phase 1 MVP)

Route `/aw:build`, mode `code`. Approved inputs: `prd.md`, `spec.md`, `design.md`, `tasks.md`.
Execution sequential (no parallel waves used yet; the `decoders` wave was run in order because
each slice was small and the review gate sits at the end of the phase).

## Phase 0 — Toolchain proven ✅

| Task | Status | Pre-change proof | Validation | Commit |
|---|---|---|---|---|
| 0.1 Bootstrap wrapper | done | no `gradle` on PATH; cached 8.14.3 dist located | `./gradlew --version` → Gradle 8.14.3, JVM 17.0.18 | `3f4454d` |
| 0.2 Skeleton + catalog | done | empty non-git directory | `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL | `72a24f6` |
| 0.3 Device install proof | **deferred** | — | blocked: no adb device | — |

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
| 1.8 Review gate | in progress | — | `kotlin-reviewer` running | — |

**Suite: 87 tests, 0 failures** (`./gradlew :app:testDebugUnitTest`).

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

### Honest gaps

- `ManufacturerTest` is characterisation-only, **not** RED-first: the lookup shipped with the
  models in Task 1.1. Recorded rather than presented as TDD.
- Tests use `!!` in several places, which `~/.claude/rules/kotlin/coding-style.md` forbids.
  Production code is `!!`-free. Deferred to the Phase 1 review pass to avoid churn mid-slice.
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

## Remaining build scope

Phase 2 (transport + read pipeline), Phase 3 (UI), Phase 4 (guarded write), Phase 5 (export),
Phase 6 (verification and closeout). Task 0.3 still owed once a device is attached.

## Next

Apply `kotlin-reviewer` findings, then Phase 2 Task 2.1 (session logger).
