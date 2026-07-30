# Device evidence — write proof, Task 4.3

Captured on a Pixel 10 (Android 17), 2026-07-30. Written through the Write screen in Text mode.

## Target

A **second card from the same provider**, distinct from the one in `hotel-card-dump.md`:

| | card A (read proof) | card B (write proof) |
|---|---|---|
| UID | `04 0E 66 A2 F0 7B 81` | `04 1C 4E 52 CE 7C 80` |
| BCC0 / BCC1 | `E4` / `A8`, both valid | `DE` / `60`, both valid |
| OTP (page `03`) | `46 0D AE 11` | `8E 00 1C 0C` |
| Lock bytes | `00 00` | `00 00` |

Both are fully unlocked, and both have a **dirty OTP page** — neither is `E1 10 06 00`, so neither
can ever be NDEF-formatted. That is a property of the silicon plus what the issuer wrote, not of any
app.

## Result

Wrote the text `shivam` to pages `04`–`05`:

```
04:73 68 69 76    "shiv"
05:61 6D 00 00    "am" + zero padding
06..0F:00 00 00 00
```

- Write accepted, read-back verified byte-for-byte.
- Zero padding is expected and correct: a page is the smallest writable unit, so a 6-byte payload
  necessarily fills 8 bytes.
- Pages `00`–`03` untouched. The OTP page still reads `8E 00 1C 0C`.

## Acceptance

AC5 demonstrated on hardware. Both halves of the loop — a guarded write and a verifying read-back —
now have device evidence, and **no tag was destroyed**: no lock bit was set, no OTP bit was set, and
expert mode was never enabled.
