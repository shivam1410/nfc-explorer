# Verification — NFC Explorer (Phase 1 MVP)

Evidence for AC1–AC8 from `prd.md`. **JVM evidence and device evidence are kept separate**, because
they prove different things: a green suite says the logic is right, and only hardware says the app
works. Eleven defects found by looking at real screens while the suite was green is the reason that
distinction is enforced here rather than assumed.

**Suite:** `./gradlew :app:testDebugUnitTest` → **284 tests, 0 failures**, across 20 suites.
**Build:** `./gradlew :app:assembleDebug` → green.
**Device:** Pixel 10, Android 17, serial `64111VDCR000BJ`.

## Acceptance criteria

### AC1 — a tap populates identity, technologies and chip profile, without crashing or blocking

**PASS.** Device-verified.

- Read of two real cards. `evidence/hotel-card-dump.md`, logcat sequence
  `tag discovered → dump finished → memory image → lock state`.
- Identity, technology inventory and chip profile all rendered. `evidence/f-tag-dark.png`,
  `evidence/vl-tag.png`.
- No `FATAL EXCEPTION` in logcat across the session; process survived repeated taps.
- Main thread not blocked: every exchange runs inside `withContext(ioDispatcher)` in
  `TagRepositoryImpl`. Structural, not measured — see *Not verified* below.

### AC2 — all 16 pages attempted, each showing bytes or a named failure, with correct indices

**PASS.** JVM + device.

- JVM: `ReadTagUseCaseTest` (19 tests) covers a full dump, per-page status, a NAK marking its
  window and the dump continuing, and tag loss splitting `TAG_LOST` from `NOT_ATTEMPTED`.
- **Wrap-around (invariant I2)** tested with chip profiles claiming 14 and 15 pages over the 16-page
  fake. This matters: striding by four across exactly 16 pages never wraps, so an MF0ICU1-only test
  would have proven nothing.
- Device: `16/16 pages read, complete=true`, indices `00`–`0F` in order, contents matching the
  logged image byte for byte. `evidence/memory-screen-dark.png`, `evidence/export-sample.txt`.
- An unreadable page carries null bytes and cannot render as `00 00 00 00` —
  `MemoryRendererTest`, plus an export test asserting a refused page emits `"hex":null` **while** a
  genuinely blank page keeps its zeros.

### AC3 — BCC0 and BCC1 computed and reported valid/invalid

**PASS.** JVM + device.

- JVM: `UidDecoderTest` covers both formulas, corrupted values retaining stored *and* computed, and
  null for a single-size UID that carries no BCC in page 0.
- Device: card A `BCC0 E4 / BCC1 A8`, card B `DE / 60`, all four computed values matching the bytes
  on the tags. Independently confirms the arithmetic against real silicon, not just the fake.

### AC4 — static lock bytes decode to per-page verdicts; dynamic lock bits reported absent

**PASS.** JVM + device.

- JVM: `StaticLockDecoderTest` (27 tests) asserts every documented bit individually, keeps locking
  distinct from block-locking, cross-checks decoder output against `FakeUltralightTransport`'s
  independent lock logic, and covers the lock-page frame offsets.
- Dynamic lock bits report `NotSupportedByChip`, never `Present` with zeros.
- Device: `LOCK0 = 00, LOCK1 = 00`, 13 lock bits shown as `clear`, pages 4–15 `writable`, pages 0–1
  `read only`, page 2 `lock control`, page 3 `OTP, one way`. `evidence/f-locks-dark.png`.

### AC5 — locked-page writes diagnose; pages 2–3 gated; pages 0–1 always refused

**PARTIAL PASS.** JVM complete; device evidence covers the success path only.

- JVM: `WriteGuardTest` (20) sweeps every page `-1..16` × three lock states × expert mode and
  asserts expert mode never converts a `Blocked`. `WritePageUseCaseTest` (15) and
  `WritePagesUseCaseTest` (11) assert a refused write **never reaches the transport**, checked
  against the fake's record of attempts rather than merely against unchanged memory.
- Device: wrote `shivam` then `Shivam Garg` to pages `04`–`06`, read-back verified.
  `evidence/write-proof.md`.
- **Not device-verified:** refusal of pages 0–1, of a locked page, and expert-mode gating. Doing so
  needs a tag with lock bits set, and setting them is irreversible. Deliberately not done to a real
  tag; the JVM sweep is the evidence for these branches.

### AC6 — session log captured and exported as valid JSON and readable TXT via the picker

**PASS.** JVM + device.

- JVM: `SessionLoggerTest` (11) covers append-only ordering, an injected clock, and 8×50 concurrent
  writers losing nothing. `JsonTest` (14) covers escaping including control bytes and non-string
  keys. `SessionExporterTest` (18) covers both formats.
- Device: a real export pulled back with `adb pull` and inspected — UTF-8, 2740 bytes, complete.
  `evidence/export-sample.txt`. Filename carries the UID.
- Export uses SAF, so **no storage permission is requested**; verified by the manifest containing
  only `android.permission.NFC`.
- **JSON not yet round-tripped on device** — the exported file confirmed was TXT. See *Not verified*.

### AC7 — unit suite green over decoders, lock analysis, rendering and the write guard

**PASS.**

284 tests, 0 failures. `DomainPurityTest` fails the build on any `android.*`/`androidx.*` import
under `domain/` and tests its own detector so it cannot pass vacuously — proven by injecting
`import android.util.Log` and watching the suite name `ByteBlock.kt:3`.

### AC8 — dark and light themes render every screen legibly

**PASS.** All ten screen/theme combinations captured and inspected on device.

- Dark: Tag, Memory, Locks, Write, Log.
- Light: Tag, Memory, Locks, Write, Log — the last closed by `evidence/h-log-light.png`, showing the
  filter chips, both export actions and the empty state.

This criterion is where verification paid for itself. Inspecting these screens found **11 defects
with the suite green**, including one on the irreversible write path. Details in `execution.md`.

## Summary

| AC | Verdict | Evidence |
|---|---|---|
| AC1 | PASS | device + JVM |
| AC2 | PASS | device + JVM |
| AC3 | PASS | device + JVM |
| AC4 | PASS | device + JVM |
| AC5 | PARTIAL | JVM complete; device success path only |
| AC6 | PASS | device (TXT) + JVM |
| AC7 | PASS | JVM |
| AC8 | PASS | all 10 screen/theme combinations |

## Invariants

| | | Status |
|---|---|---|
| I1 | `domain/` has no framework imports | enforced by `DomainPurityTest`, self-tested |
| I2 | reported page index equals physical page | tested at page counts 14, 15, 16 |
| I3 | no write reaches transport without `Allowed` | swept exhaustively; asserted against attempt records |
| I4 | session log is append-only | tested, including under concurrency |
| I5 | no tag I/O on the main thread | structural (`withContext`); not instrumented |

## Not verified — stated plainly

1. **Tag loss mid-read, on hardware.** 19 JVM tests cover partial dumps; no device confirmation.
   Needs a human to pull a card away mid-tap. **The last genuinely untested hardware path.**
2. **Write refusals on hardware** (pages 0–1, a locked page, expert-mode gating). Would require
   irreversibly locking a real tag.
3. **JSON export on device.** TXT confirmed end to end; JSON is JVM-tested only.
4. **Multi-window / split-screen.** Never a design target. An accidental capture during an
   app-switcher transition showed the app rendering in a narrow window without crashing, but text
   near the window edge appeared clipped. Not investigated, and not claimed either way.
5. **I5 not instrumented.** No StrictMode or main-thread assertion; the guarantee is structural.
6. **No instrumented or Compose UI tests at all.** No `androidTest` source set exists. The
   Activity-level tag routing — where the CRITICAL review finding lived — has no automated coverage
   and was caught only by review.

## Reviews

| Pass | Scope | Verdict | Outcome |
|---|---|---|---|
| Phase 1 | `domain/` + tests | BLOCK: 0 crit, 0 high, 3 med, 3 low | all fixed; no bit-math defect found |
| Phase 4 | write path | BLOCK: 1 crit, 2 high, 2 med, 1 low | all fixed |

Both reviews confirmed the pure domain logic correct and found the defect in the layer deciding
*when* that logic runs — page `0x02` under unknown lock state in Phase 1, and arming outliving its
own screen in Phase 4.

## Open findings

- **D2** ktlint attempted and reverted; 628 findings of which 5 were real. Detekt recommended.
- **D3** `ManufacturerTest` is characterisation-only, not RED-first. Accepted.
- **D5** 2 unused imports and 3 import-ordering issues surfaced by the reverted ktlint run.

## Handoff

Phase 1 MVP is complete for its stated scope. The one action worth taking before trusting this on a
tag you care about is item 1 above: **pull a card away mid-read and confirm you get a partial dump
rather than a crash or a lie.**
