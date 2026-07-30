# NFC primer — pages, OTP, lock bits

Written for someone using this app on a MIFARE Ultralight family tag. Chip-specific tables live in
[mf0icu1-reference.md](mf0icu1-reference.md); this explains what the concepts *mean*.

## What a page is

Ultralight memory is addressed in **pages of 4 bytes**. A page is the smallest unit you can write —
there is no way to change one byte and leave the other three alone. Write a page, or write nothing.

An MF0ICU1 has **16 pages = 64 bytes** total, of which 48 bytes are user data.

Reading is asymmetric and catches people out: the `READ` command always returns **four pages
(16 bytes) at once**, and it **wraps around** past the end of memory. On a 16-page tag,
`readPages(14)` returns pages 14, 15, 0, 1. A reader that appends blindly reports early pages under
late indices — a silently scrambled dump. This app clamps every read to the chip's page count and
discards the wrapped tail.

## The four regions

| Pages | What | Writable |
|---|---|---|
| `00`–`01` | Serial number (UID) + check byte | **Never.** Fixed at manufacture |
| `02` | Check byte, internal byte, **2 lock bytes** | Last 2 bytes only |
| `03` | **OTP** — one-time programmable | Yes, but one-way |
| `04`–`0F` | User data | Yes |

## OTP: one-way, not write-once

"One-time programmable" is a slight misnomer. You can write page `03` many times — but writes are
**OR-ed** into what is already there. Each of the 32 bits can go `0 → 1` and **never `1 → 0`**.

So the page only ever gets fuller. This matters far beyond the page itself, because **NDEF stores its
capability container here**. A tag whose OTP page holds anything other than a valid CC can never be
NDEF-formatted by any app, ever:

```
  46 0D AE 11     what is already on the page
| E1 10 06 00     the capability container NDEF needs
= E7 1D AE 11     magic byte E7, not E1 — invalid, and irreversible
```

Android refuses rather than permanently corrupt it. That is the correct behaviour, and it is why
NDEF-oriented apps report such a tag as "not supported" — they are not broken, the tag genuinely
cannot carry NDEF.

## Lock bits: permanent, no key, no reset

The two lock bytes at page `02` control write access. Each `L_*` bit makes one page **permanently
read-only**:

```
LOCK0        7   6   5   4    3      2         1        0
           L_7 L_6 L_5 L_4  L_OTP  BL_15_10  BL_9_4  BL_OTP

LOCK1      L_15 … L_8
```

Two distinct concepts, and conflating them is the classic mistake:

- a **lock bit** (`L_*`) makes its page read-only forever;
- a **block-locking bit** (`BL_*`) freezes a *range of lock bits*, so whether those pages are locked
  can never change again. An unlocked page with a frozen lock bit stays writable **forever**.

Both are set-only, exactly like OTP. There is no unlock command, no key, no factory reset. Setting a
lock bit is the single most consequential thing this app can do, which is why writes to page `02`
are gated behind an expert-mode toggle that resets to off on every launch.

## Will my writable pages stay writable?

**Yes, indefinitely.** Writing data to page 7 does not touch the lock bytes at page 2. There is no
timer, no expiry, and reading never wears anything out.

Three real caveats:

1. **Writing page `02` yourself** is the only way to lose it. No undo.
2. **Whoever else can write the tag** can still lock it, if the lock bits are not yet frozen.
3. **EEPROM endurance** is on the order of 10,000 write cycles per page with roughly 10 years
   retention. A physical limit of the silicon, not a permission — irrelevant for normal use, but not
   literally infinite.

## Why "geometry unconfirmed"

Android reports the same technology, ATQA (`0x0044`) and SAK (`0x00`) for MF0ICU1, Ultralight EV1 and
every NTAG21x. Distinguishing them requires `GET_VERSION`, which the original Ultralight does not
implement.

So this app reports a **16-page floor** and flags it unconfirmed rather than naming a chip it cannot
prove. Sixteen pages is readable on every family member, so a dump can never run past the end of a
tag — but it may under-read an NTAG216, which has 231. Claiming MF0ICU1 outright would silently hide
852 bytes.

Usefully, a NAK from `GET_VERSION` is itself positive evidence that a tag *is* an original Ultralight.

## What a tag like this can and cannot do

**Can:**
- Store 48 bytes of arbitrary data, readable by any app that speaks raw pages
- Act as an automation trigger by **UID** — no NDEF needed, and the UID is broadcast to every reader
  before any authentication

**Cannot, if the OTP page is dirty:**
- Carry NDEF, so no tap-to-open-URL, no WiFi handoff, no contact cards, nothing a stock phone reacts to

**Cannot, ever:**
- Change its UID. It is fixed in silicon, which is why a tag cannot be cloned onto another tag.

For NDEF work, use blank **NTAG213/215/216** — clean OTP page, properly formattable, and
`GET_VERSION` support means this app can confirm their real geometry instead of guessing a floor.
