# Device evidence — hotel key card, Task 2.5

Captured on a Pixel 10 (Android 17) with NFC Explorer, reader mode with
`FLAG_READER_SKIP_NDEF_CHECK`. Transcribed from the on-device render; the summary lines are
corroborated by logcat (`adb logcat -d -s NfcExplorer:V`).

```
[0] session: tag discovered  {uid=04 0E 66 A2 F0 7B 81, technologies=2,
                              chip=MIFARE Ultralight, geometryConfirmed=false}
[1] read: dump finished      {pagesRead=16, pagesTotal=16, complete=true}
```

## Identity

| Field | Value |
|---|---|
| UID | `04 0E 66 A2 F0 7B 81` (7 bytes) |
| Cascade levels | 2 |
| Manufacturer | NXP Semiconductors (UID byte 0 = `0x04`) |
| ATQA | `44 00` |
| SAK | `00` |
| BCC0 | stored `E4`, computed `E4` — **valid** |
| BCC1 | stored `A8`, computed `A8` — **valid** |
| Family | MIFARE Ultralight |
| Chip | not confirmed (see note) |
| Geometry | 16 pages × 4 B (floor, unconfirmed) |

Both check bytes validate, which cross-confirms the UID from anticollision against the bytes
stored in pages 0 and 2. The read is clean, not a partial or corrupted one.

## Technologies

Only two, because reader mode skipped the platform NDEF probe:

```
NfcA              maxTx=253  timeout=618ms  {atqa=44 00, sak=0}
MifareUltralight  maxTx=253  timeout=618ms  {variant=ULTRALIGHT}
```

## Memory — 16/16 pages read

```
PAGE  HEX            ASCII  ACCESS
00    04 0E 66 E4    ··f·   hardware read only   SN0 SN1 SN2 BCC0
01    A2 F0 7B 81    ··{·   hardware read only   SN3..SN6
02    A8 48 00 00    ·H··   lock control         BCC1, internal, LOCK0=00, LOCK1=00
03    46 0D AE 11    F···   otp one way          <-- NOT an NDEF capability container
04    E2 42 1B 5E    ·B·^   writable
05    36 56 3A 96    6V:·   writable
06    CA C7 C4 88    ····   writable
07    C2 BD D7 19    ····   writable
08    67 03 03 FC    g···   writable
09    4D D4 BF 32    M··2   writable
0A    00 00 00 00    ····   writable
0B    00 00 00 00    ····   writable
0C    00 00 00 00    ····   writable
0D    00 00 00 00    ····   writable
0E    00 00 00 00    ····   writable
0F    00 00 00 00    ····   writable
```

## Lock analysis

```
Lock bytes    00 00
Locked pages  none
Writable      [4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]
Dynamic lock  NotSupportedByChip(introducedIn=MIFARE Ultralight EV1 / NTAG21x)
```

## Findings

**1. The card is not locked. At all.** `LOCK0 = 0x00`, `LOCK1 = 0x00`: no `L_*` bit is set and no
`BL_*` block-locking bit is set. Every user page 4–15 is writable, and the lock bits themselves
are still free to change. This **refutes hypothesis 1** from the original brief ("the hotel
permanently locked the tag").

**2. There is no NDEF capability container.** A NDEF-formatted Type 2 tag carries its CC in page
`0x03`: `E1 10 06 00` — magic `E1`, version 1.0, size 48 B, read/write. This card's page 3 holds
`46 0D AE 11`. No CC, so no NDEF, so nothing for an NDEF-oriented reader to parse.

**3. That also explains why *formatting* failed, which is the more interesting half.** The CC must
live in the OTP page, and OTP bits are **OR-ed on write — they can be set but never cleared**.
Page 3 already holds `46 0D AE 11`, so writing `E1 10 06 00` over it would produce:

```
  46 0D AE 11
| E1 10 06 00
= E7 1D AE 11        <-- magic byte E7, not E1: not a valid CC, and irreversible
```

Android will not do that, and is right not to. The tag can never be NDEF-formatted, no matter
which app tries — this is a property of the silicon plus what the hotel wrote, not a limitation of
any particular tool. So **hypothesis 2 is confirmed** (proprietary formatting), and hypothesis 3
is partly true: NFC Tools is NDEF-centric and had nothing to show.

**4. The payload is 24 bytes of high-entropy data in pages 4–9**, with pages `0A`–`0F` untouched.
No ASCII, no TLV structure, no repeating pattern. Consistent with an encrypted or MAC'd credential
rather than a plaintext room/folio number.

**5. Chip identification is deliberately left unconfirmed.** Android reports `TYPE_ULTRALIGHT` for
MF0ICU1, Ultralight EV1 and every NTAG21x alike. NXP TagInfo reported MF0ICU1, which is almost
certainly right, but this app will not assert it without a `GET_VERSION` probe (Phase 2). All 16
pages read cleanly and the geometry floor held.

## Answer to the original question

The card **cannot be reused as a hotel key**. Pages 4–9 are almost certainly a cryptographic
credential validated server-side or against a door controller's key; the UID is fixed in silicon
and cannot be cloned onto another tag.

It **can** be reused as a general-purpose NFC tag for your own raw data, because pages 4–15 are
fully writable — with two caveats:

- Writing over pages 4–9 destroys the room credential permanently. Do it only once the stay is over.
- It can never be made NDEF-formatted, so phone-friendly URL/text payloads are out. Raw page
  read/write only.

## Reproduce

```
adb logcat -c
# tap the card
adb logcat -d -s NfcExplorer:V
```
