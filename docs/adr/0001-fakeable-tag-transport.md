# ADR 0001 — Tag I/O behind a fake-able transport interface

- **Status:** accepted
- **Date:** 2026-07-30
- **Applies to:** every phase; Phase 2 chip support must respect it

## Context

This app's entire value is reporting accurately what is on an NFC tag. The logic that
earns that trust is bit-level: UID check-byte arithmetic, static lock-bit decoding, page
access classification, `READ` wrap-around handling, and the write guard that stands between
a mistaken tap and permanent hardware damage.

Two facts about the platform shape how any of that can be verified:

1. **NFC hardware does not exist in the Android emulator.** There is no way to present a
   synthetic tag to `android.nfc` on an emulated device.
2. **The framework classes are terminal.** `android.nfc.Tag` and
   `android.nfc.tech.MifareUltralight` are final, constructed by the platform from a binder
   parcel, and offer no seam for substituting behaviour.

Taken together, the naive architecture — decoders calling `MifareUltralight` directly — makes
every correctness claim depend on a physical tag being physically present. Bit-level logic
would then be verified by holding a card against a phone and reading the screen. That is not
a test; it is a demonstration, and it cannot be run in CI, cannot cover a fully-locked tag
without permanently locking a real one, and cannot cover a mid-read tag loss reproducibly.

## Decision

All tag I/O sits behind `domain/transport/TagTransport` and `UltralightTransport`. Nothing
above that seam references the Android framework, and `DomainPurityTest` fails the build if
an `android.*` or `androidx.*` import appears under `domain/`.

Two implementations exist:

- `data/nfc/AndroidUltralightTransport` — a thin adapter over `MifareUltralight`, holding no
  logic worth testing, translating framework exceptions into domain ones.
- `test/fake/FakeUltralightTransport` — an in-memory MF0ICU1 that **enforces the chip's real
  semantics** rather than permitting everything: `READ` wraps past the last page, UID pages
  reject writes, lock bytes and OTP are OR-ed so bits set but never clear, and a locked page
  NAKs.

The transport also defines its own exception types (`TagFieldLostException`,
`TagNakException`, `TagNotConnectedException`). The read pipeline must distinguish "the tag
left the field" from "the tag refused" to assign per-page status, and it cannot import
`android.nfc.TagLostException` to do so.

## Consequences

**Good.**

- 87 JVM tests cover the decode layer with no hardware, including cases that are impractical
  or destructive on real tags: a fully-locked tag, a block-locked tag, a tag pulled away
  mid-dump, and every write-guard branch swept across all pages and lock states.
- The fake enforcing chip semantics turns those tests into evidence. A permissive stub would
  let them pass while real hardware failed — the failure mode this decision exists to avoid.
- The fake and the decoder derive lock state independently, so
  `StaticLockDecoderTest` can cross-check two separate implementations against each other
  rather than one implementation against its own assumptions.
- Phase 2 chips (NTAG21x, Ultralight EV1, DESFire) extend the seam instead of reshaping the
  domain.

**Costs.**

- Two implementations of the page-access surface to keep in step. Mitigated by the
  cross-check test, which fails if they diverge.
- `AndroidUltralightTransport` itself is not unit-tested. That is deliberate: it is a thin
  delegation over final framework classes, and mocking `MifareUltralight` would assert only
  that the mock was called. It is verified on the device instead, and kept thin enough that
  there is nothing else to get wrong.
- The fake is a model of the chip, so it can be wrong in the same direction as the decoder.
  Its own 16 self-tests and the device proof in Phase 2 Task 2.5 exist to catch that.

## Alternatives considered

**Robolectric shadows for `android.nfc`.** Robolectric ships no meaningful NFC shadow, so
this would mean writing one — the same fake, plus a heavyweight test runtime, plus the
pretence of testing the framework. Strictly worse.

**Instrumented tests on the device.** Requires a physical tag for every run, cannot be
automated in CI, and cannot cover destructive states without destroying tags. Retained as a
complement for the I/O path, not as a substitute for unit tests.

**Decoders taking `ByteArray` with no transport abstraction.** Tempting, and it would test
the pure decoders fine. But it leaves the read pipeline — where `READ` wrap-around lives, the
single most dangerous silent-corruption bug in this app — on the untestable side of the line.
Rejected for that reason.

## Compliance

- `DomainPurityTest` fails the build on any framework import under `domain/`.
- New chip support adds a transport implementation and a fake; it does not add framework
  imports above the seam.
- Any logic worth testing that appears in an Android adapter is a signal it belongs above the
  seam instead.
