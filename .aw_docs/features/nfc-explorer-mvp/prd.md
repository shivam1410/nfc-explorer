# PRD — NFC Explorer (Phase 1 MVP)

## Goal

A developer-grade Android NFC inspection tool: connect to a tag, dump every readable
byte, decode the memory layout and access-control bits, attempt guarded writes, and
export a full session log. The immediate concrete objective is to determine what is
actually stored on a MIFARE Ultralight MF0ICU1 hotel key card, and why third-party
apps (NXP TagInfo, NFC Tools) refuse to dump or format it.

The long-term objective is a permanent utility on the device — closer to `adb` or
Wireshark than to a demo app.

## Scope (Phase 1)

1. **Tag detection** via reader mode, not intent filters.
2. **Tag identity**: UID, UID length, cascade levels, BCC0/BCC1 verification,
   ATQA, SAK, manufacturer derived from UID byte 0, chip profile inference.
3. **Technology inventory**: every `android.nfc.tech.*` the tag reports, with
   per-technology `maxTransceiveLength` and timeout values where the API exposes them.
4. **Memory explorer**: read every page, per-page read status, hex / binary / ASCII
   rendering, and honest reporting of pages that refused to read.
5. **Lock analysis**: decode MF0ICU1 static lock bytes (page `0x02` bytes 2–3) plus
   the three block-locking bits, and classify every page's write access.
6. **Guarded write**: write user pages behind arm-and-confirm; refuse writes to lock
   bytes and OTP unless expert mode is explicitly enabled. Surface every exception
   type with a diagnostic explanation, not a stack trace.
7. **Session log + export**: append-only structured log of every operation, exportable
   as JSON and TXT through the system file picker.

## Non-Goals (Phase 1)

- MIFARE Classic, DESFire, NTAG21x, IsoDep, NFC Forum Type 1/3/4/5 — Phase 2.
- Raw transceive console, hex editor, NDEF payload decoding, protocol inspector — Phase 3.
- Card emulation / HCE. Cloning or replaying access credentials.
- Cracking authentication on protected tags. This tool reads and reports; it does not
  attack cryptographic protection.
- Cloud sync, accounts, analytics.

## Assumptions and Constraints

- **A1** Target chip for Phase 1 is MIFARE Ultralight MF0ICU1: 64 bytes, 16 pages ×
  4 bytes, command set limited to `READ` (0x30), `WRITE` (0xA2), `COMPATIBILITY_WRITE`
  (0xA0). No `GET_VERSION`, no `FAST_READ`, no `PWD_AUTH`, **no dynamic lock bits**.
  Dynamic lock bits are an Ultralight EV1 / NTAG21x feature and are reported as
  *absent for this chip* rather than fabricated.
- **A2** `MifareUltralight.readPages(offset)` returns **16 bytes = 4 pages** and
  **wraps around** past the last page. The reader must account for roll-over or it
  will silently report page 0–1 content as page 16–17.
- **A3** NFC hardware does not exist in the Android emulator. Every tag I/O path can
  only be proven on the physical Pixel 10 over adb. All decode logic must therefore sit
  behind a fake-able transport so it is provable on the JVM.
- **A4** Build environment: JDK 17 (Temurin 17.0.18), Android SDK with platforms
  android-35/36/36.1, build-tools up to 37.0.0, cached Gradle 8.14.3 at
  `~/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle`.
  No `gradle` on PATH — the wrapper is bootstrapped from that cached binary once.
- **C1** Single `:app` Gradle module. Clean Architecture enforced by package boundary
  discipline: nothing under `domain/` may import `android.*`.
- **C2** No deprecated NFC APIs. Reader mode (`NfcAdapter.enableReaderMode`) over
  `enableForegroundDispatch`. SAF (`ACTION_CREATE_DOCUMENT`) for export, so no storage
  permission is requested.
- **C3** Writes to page `0x02` (lock bytes) and page `0x03` (OTP) are irreversible in
  hardware. Lock bits are set-only; OTP bits are OR-ed on write. These are gated.

## Acceptance Criteria

- **AC1** Tapping the MF0ICU1 card populates identity, technology list, and chip
  profile with no crash and no blocking of the UI thread.
- **AC2** All 16 pages are attempted; each page shows either its 4 bytes or a named
  failure reason. Reported page indices are correct across the `readPages` wrap.
- **AC3** BCC0 and BCC1 are computed and reported as valid/invalid against the UID.
- **AC4** Static lock bytes decode into a per-page write verdict for pages 0–15,
  and dynamic lock bits are reported as unsupported-by-chip rather than as zeros.
- **AC5** A write to a locked page surfaces a named diagnostic; a write to page 2 or 3
  is refused unless expert mode is on; a write to page 0 or 1 is always refused.
- **AC6** The session log captures every connect / read / write / error with timestamps
  and exports to valid JSON and readable TXT via the system picker.
- **AC7** `./gradlew :app:testDebugUnitTest` is green, covering identity decoding, lock
  decoding, page-access classification, memory rendering, and write-guard rules
  against a synthetic MF0ICU1 fake.
- **AC8** Dark and light Material 3 themes both render every screen legibly.

## Risks, Mitigations, Dependencies

| Risk | Impact | Mitigation |
|---|---|---|
| `readPages` wrap-around misattributes page indices | Silently wrong memory dump — the worst class of bug for this tool | Fake transport in unit tests implements wrap-around explicitly; a test asserts page 14 read returns 14,15,0,1 and that the reader discards the wrapped tail |
| Hotel card is UID-only with no reusable payload | The original motivating question answers "nothing to reuse" | Acceptable — the tool still delivers the answer with evidence. Not a build failure |
| Tag moves mid-session → `TagLostException` | Partial dumps | Per-page read status; partial dumps are first-class, not an error state |
| A stray write bricks the card or a blank tag | Irreversible hardware loss | Write guard as a pure, unit-tested function; page 0–1 hard-refused; page 2–3 expert-gated |
| Pixel 10 dropped off adb during planning | Cannot run device proof | Re-check cable/unlock before Phase 2 device validation; JVM proof is unaffected |
| Guessed AGP/Kotlin/Compose versions fail to resolve | Phase 0 stall | Phase 0 acceptance is a real `assembleDebug`; versions are corrected against actual resolution output, not assumed |

## Dependencies

- Physical NFC device (Pixel 10) reachable over adb for Phase 2 onward.
- The MF0ICU1 hotel card, plus ideally one blank NTAG/Ultralight tag for write testing
  so the hotel card is never the write target.
