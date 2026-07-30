# MIFARE Ultralight MF0ICU1 — decoder reference

Authoritative reference for the Phase 1 decoders. Derived from the NXP MF0ICU1 product
data sheet. `StaticLockDecoder` and `ReadTagUseCase` must agree with the tables here; if
real tag behaviour contradicts this document, update the document first and the decoder
second.

## Identity

| Property | Value |
|---|---|
| Vendor | NXP Semiconductors (UID byte 0 = `0x04`) |
| Family | MIFARE Ultralight |
| Chip | MF0ICU1 |
| EEPROM | 64 bytes |
| Organisation | 16 pages × 4 bytes |
| User data | 48 bytes (pages `0x04`–`0x0F`) |
| UID | 7 bytes, double-size, 2 cascade levels |
| ATQA | `0x0044` |
| SAK | `0x00` |
| Authentication | none |

## Command set

Only three commands. Anything richer belongs to a different chip.

| Command | Code | Notes |
|---|---|---|
| `READ` | `0x30` | Returns **16 bytes = 4 pages**, wrapping past the last page |
| `WRITE` | `0xA2` | Writes one 4-byte page |
| `COMPATIBILITY_WRITE` | `0xA0` | 16-byte frame, only the first 4 bytes are stored |

**Not supported by this chip** — these arrived with Ultralight EV1 / NTAG21x and must be
reported as absent rather than decoded as zeros:

- `GET_VERSION`
- `FAST_READ`
- `PWD_AUTH` / password protection
- Dynamic lock bits
- NFC counters

## Memory map

| Page | Bytes | Role |
|---|---|---|
| `0x00` | `SN0 SN1 SN2 BCC0` | UID part 1 + check byte |
| `0x01` | `SN3 SN4 SN5 SN6` | UID part 2 |
| `0x02` | `BCC1 internal LOCK0 LOCK1` | Check byte 1, internal byte, static lock bytes |
| `0x03` | `OTP0 OTP1 OTP2 OTP3` | One-time programmable |
| `0x04`–`0x0F` | user data | 48 bytes |

### Check bytes

```
BCC0 = 0x88 XOR SN0 XOR SN1 XOR SN2
BCC1 = SN3 XOR SN4 XOR SN5 XOR SN6
```

`0x88` is the cascade tag for the first cascade level of a double-size UID. A mismatch
means either a corrupted read or a non-compliant tag — both worth surfacing.

### Write semantics

- Pages `0x00` and `0x01` are **not writable**. The UID is fixed at production.
- Page `0x02`: `BCC1` and the internal byte are not writable. `LOCK0`/`LOCK1` bits are
  **OR-ed** on write — a bit set to 1 can never return to 0.
- Page `0x03` (OTP): bits are **OR-ed** on write. Same one-way behaviour.
- Pages `0x04`–`0x0F`: normal read/write until their lock bit is set.

## Static lock bytes

`LOCK0` = page `0x02`, byte 2.

| Bit | Mask | Name | Effect |
|---|---|---|---|
| 0 | `0x01` | `BL_OTP` | Block-locking: freezes the `L_OTP` bit |
| 1 | `0x02` | `BL_9_4` | Block-locking: freezes lock bits for pages 4–9 |
| 2 | `0x04` | `BL_15_10` | Block-locking: freezes lock bits for pages 10–15 |
| 3 | `0x08` | `L_OTP` | Locks page `0x03` (OTP) |
| 4 | `0x10` | `L_4` | Locks page `0x04` |
| 5 | `0x20` | `L_5` | Locks page `0x05` |
| 6 | `0x40` | `L_6` | Locks page `0x06` |
| 7 | `0x80` | `L_7` | Locks page `0x07` |

`LOCK1` = page `0x02`, byte 3.

| Bit | Mask | Name | Effect |
|---|---|---|---|
| 0 | `0x01` | `L_8` | Locks page `0x08` |
| 1 | `0x02` | `L_9` | Locks page `0x09` |
| 2 | `0x04` | `L_10` | Locks page `0x0A` |
| 3 | `0x08` | `L_11` | Locks page `0x0B` |
| 4 | `0x10` | `L_12` | Locks page `0x0C` |
| 5 | `0x20` | `L_13` | Locks page `0x0D` |
| 6 | `0x40` | `L_14` | Locks page `0x0E` |
| 7 | `0x80` | `L_15` | Locks page `0x0F` |

### Two distinct concepts

- A **lock bit** (`L_*`) makes its page permanently read-only.
- A **block-locking bit** (`BL_*`) freezes a *range of lock bits*, so the
  locked-or-unlocked state of those pages can never change again. An unlocked page whose
  lock bit is frozen stays writable forever; a locked one stays locked forever.

Both are set-only. There is no unlock path, no key, and no reset.

## READ wrap-around

`READ` and Android's `MifareUltralight.readPages(offset)` both return 4 pages and roll
over past the end of memory. On a 16-page tag:

```
readPages(14) -> page 14, page 15, page 0, page 1
```

A reader that appends blindly will report pages 0 and 1 as pages 16 and 17. The dump
pipeline must clamp to `pageCount` and discard the wrapped tail. This will be covered by `ReadTagUseCaseTest`
(Phase 2, Task 2.2), which does not exist yet.

## Behaviour on refusal

A tag that will not serve a read responds with a NAK rather than data, which surfaces
through Android as `IOException`. A locked page rejects `WRITE` the same way. Neither is
an app error, and neither should be rendered as `00 00 00 00` — that would be
indistinguishable from a page of real zeros.
