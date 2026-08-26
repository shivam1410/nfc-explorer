# NFC Explorer

A developer-grade NFC inspection tool for Android. Closer to `adb` or a protocol analyser than to a
consumer tag app: it dumps every readable byte, decodes the access-control bits, performs guarded
writes, and exports the whole session as text or JSON.

Built to answer a specific question — *what is actually on a hotel key card, and why do other apps
refuse to read it?* — and generalised from there. See
[the card it was built for](.aw_docs/features/nfc-explorer-mvp/evidence/hotel-card-dump.md).

## Status

Phase 1 MVP complete. **406 unit tests, 0 failures.**

| Feature | State |
|---|---|
| Tag detection via reader mode | done |
| Identity: UID, ATQA, SAK, cascade levels, BCC verification | done |
| Technology inventory with per-technology metadata | done |
| Memory explorer: hex / binary / decimal / ASCII, per-page status | done |
| Lock analysis: static lock bits, block-locking, per-page verdicts | done |
| Guarded write: text, hex, bulk wipe, arm-and-confirm | done |
| Session log and JSON/TXT export via the system file picker | done |
| Tag actions: launch, link, intent, media | done |
| Sleep Cycle start/stop toggle on one tag | done, verified on device |
| MIFARE Classic, DESFire, NTAG21x, IsoDep | not started |
| NDEF decode, raw transceive console, hex editor | not started |

Currently models **MIFARE Ultralight MF0ICU1** in full. The type system is built so other chips slot
in behind `ChipProfileResolver` and the transport interface without reshaping the domain.

## Requirements

- A **physical Android device with NFC**. This is not optional — see below.
- JDK 17, Android SDK with platform 36. `minSdk 26`.

## Build and run

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:testDebugUnitTest    # 406 unit tests, no device needed
./gradlew :app:installDebug         # install on a connected device
```

Session activity mirrors to logcat, which is the quickest way to capture a dump as text:

```bash
adb logcat -c && adb logcat -s NfcExplorer:V
```

## There is no NFC in the Android emulator

Worth stating plainly, because it shapes the whole architecture: **no emulator can present a tag.**
There is no shadow, no mock, no host-side substitute.

The naive design — decoders calling `MifareUltralight` directly — would make every correctness claim
depend on a physical card being physically present. Bit-level logic would be "verified" by holding a
tag against a phone and reading the screen. That cannot run in CI, cannot cover a fully-locked tag
without permanently locking a real one, and cannot reproduce a mid-read tag loss.

So all tag I/O sits behind `domain/transport/TagTransport`, and the test suite drives a
`FakeUltralightTransport` that **enforces the chip's real semantics** — `READ` wraps past the last
page, UID pages reject writes, lock bytes and OTP are OR-ed so bits set but never clear, locked pages
NAK. A permissive stub would let tests pass while real hardware failed; that is the failure mode the
seam exists to prevent. Full reasoning in
[ADR 0001](docs/adr/0001-fakeable-tag-transport.md).

## ⚠️ Write safety

Some NFC writes are **irreversible in hardware**. There is no key, no unlock command, no factory reset.

- Pages `00`–`01` hold the UID and are never writable.
- Page `02` holds the lock bytes. Bits are **OR-ed** — a set lock bit can never be cleared, and a
  block-locking bit permanently freezes whether a page is locked at all.
- Page `03` is **OTP**. Bits are OR-ed. Writing it is also what makes a tag permanently
  un-NDEF-formattable.
- Pages `04`–`0F` are ordinary user data until their lock bit is set.

The app enforces this in layers:

- `WriteGuard` is a pure function of page, lock state and expert mode. Pages `00`–`01` are always
  refused; pages `02`–`03` require expert mode; a locked page or an **unknown** lock state is always
  refused. Expert mode can only convert `RequiresExpertMode` into `Allowed` — never a `Blocked`.
- Lock state is **re-read from the tag inside the write session**, never carried over from an earlier
  scan, which could be stale.
- Every write is followed by a **read-back**. Success is demonstrated, not assumed — and on OTP or
  lock pages a write can legitimately succeed while storing something different, which the result
  reports rather than hides.
- Writing requires **arm-then-tap**. Arming is the confirmation step, because a tag is only in range
  for a moment; the exact bytes are shown before arming, and any edit to the payload or range disarms.
- **Expert mode resets to off on every launch** and is never persisted.

## Architecture

Single Gradle module, Clean Architecture enforced by package boundary.

```
dev.shivam.nfcexplorer
├── domain/          pure Kotlin — zero android.* imports, enforced by DomainPurityTest
│   ├── model/       ByteBlock, TagIdentity, ChipProfile, MemoryDump, LockAnalysis, WriteOutcome
│   ├── transport/   TagTransport / UltralightTransport — the seam
│   ├── decoder/     UID/BCC, static lock bits, chip profile, memory rendering
│   ├── writer/      WriteGuard, PageEncoder
│   ├── usecase/     ReadTagUseCase, WritePageUseCase, WritePagesUseCase
│   ├── export/      JSON and TXT serialisation
│   └── repository/  TagRepository interface (+ opaque TagHandle)
├── data/
│   ├── nfc/         Android adapters over MifareUltralight / NfcA, reader-mode controller
│   ├── repository/  TagRepositoryImpl — runs the pipeline on Dispatchers.IO
│   └── export/      SafDocumentWriter
├── logging/         append-only SessionLogger + logcat mirror
├── ui/              Compose Material 3: theme, components, five screens
├── di/              Hilt modules
└── util/            hex/binary formatting
```

`ui -> domain <- data`. `domain` depends on nothing but the Kotlin and JVM standard libraries, which
is what keeps it testable without a device.

## Design rules the code holds to

These are invariants, not preferences, and most have tests attached:

- **Absence is never rendered as a zero.** An unread page carries `null` bytes, so it cannot be shown
  or exported as `00 00 00 00` — indistinguishable from a page of genuine zeros. The JSON export emits
  `"hex": null`; the text export emits `??`.
- **Partial results are first class.** A tag pulled away mid-dump produces a report, not an error. The
  in-flight stride is `TAG_LOST` (it was attempted); everything after is `NOT_ATTEMPTED` (it never
  was). Those are different facts.
- **Unconfirmed is stated, not rounded off.** Chip geometry reports a safe floor with
  `geometryConfirmed = false` rather than naming a chip that ATQA and SAK cannot distinguish.
- **Reason codes are enums, not prose.** The domain layer carries structured reasons; user-facing
  wording lives in `strings.xml`.
- **Colour never carries a verdict alone.** Every access verdict is also stated in text.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

406 JVM tests, no hardware. Covered: UID/BCC arithmetic, every documented lock and block-lock bit,
page-access classification, `READ` wrap-around, memory rendering, the write guard swept across all
pages × lock states × expert mode, page encoding, batch writes, the session logger under concurrent
writers, JSON escaping, export null-vs-zeros, and both ViewModels.

`DomainPurityTest` fails the build on any `android.*` or `androidx.*` import under `domain/`, and
tests its own detector so it cannot pass vacuously.

**What tests do not cover:** Compose screens and Hilt wiring are verified on-device instead. That
exemption is not free — a visual inspection pass found **eleven presentation defects that a green
suite could not see**, recorded in
[state.json](.aw_docs/features/nfc-explorer-mvp/state.json). Look at the screens.

## Documentation

- [docs/nfc-primer.md](docs/nfc-primer.md) — pages, OTP, lock bits explained
- [docs/mf0icu1-reference.md](docs/mf0icu1-reference.md) — authoritative chip tables for the decoders
- [docs/adr/0001-fakeable-tag-transport.md](docs/adr/0001-fakeable-tag-transport.md) — the transport seam
- [docs/sleep-cycle-automation.md](docs/sleep-cycle-automation.md) — what is reachable in Sleep Cycle,
  what is not, and why stopping a session needs a gesture
- [.aw_docs/features/nfc-explorer-mvp/](.aw_docs/features/nfc-explorer-mvp/) — PRD, spec, tasks,
  execution log, and device evidence

## Licence

[MIT](LICENSE). Do what you like with it; there is no warranty.

One thing worth saying plainly, given what this app does: it writes to NFC tags, and some of those
writes are physically irreversible. OTP bits and lock bits can be set but never cleared, so a mistake
here is not undone by reinstalling. The write path guards against that and explains itself before
acting, as the Write safety section above describes, but the licence's "as is, without warranty" applies to
your tags as much as to the code.
