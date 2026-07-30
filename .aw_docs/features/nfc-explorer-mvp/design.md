# Design — NFC Explorer (Phase 1 MVP)

## Design Intent

A developer instrument, not a consumer app. Dense, monospaced where data is binary,
information-first, no decorative imagery. Material 3 provides the chrome; the content
reads like a good debugger pane. Nothing is hidden behind a spinner that could instead
show partial truth — a half-read dump is more useful than a loading state.

## Navigation

Single-activity, `NavHost` with a bottom nav bar of four destinations, plus a modal
write sheet. Bottom nav rather than tabs because destinations are peers the user moves
between repeatedly during a session.

```
ScanScreen (start)
  └── on tag captured ──> TagInfoScreen
Bottom nav: Tag | Memory | Locks | Log
Write: modal bottom sheet launched from MemoryExplorerScreen (per-page action)
```

Session state survives navigation between the four destinations; it is held in a
session-scoped holder, so leaving Memory and returning does not force a re-tap.

## Screens and States

### ScanScreen

States:
- `NfcUnsupported` — device has no NFC adapter. Terminal explanatory state, no retry.
- `NfcDisabled` — adapter present but off. Button deep-links to NFC settings via
  `Settings.ACTION_NFC_SETTINGS`.
- `WaitingForTag` — reader mode active. Slow pulsing concentric-ring animation around
  an NFC glyph; text "Hold a tag against the back of the device".
- `Reading` — determinate progress as pages are dumped (`page 7 / 16`).
- `Captured` — auto-navigates to Tag; also shows a compact summary card so a repeat tap
  is visibly acknowledged.
- `Failed(reason)` — named failure with the diagnostic, plus Retry.

### TagInfoScreen

Expandable `SectionCard`s, first two expanded by default:

1. **Identity** — UID (hex, colon-grouped), UID length, cascade levels, BCC0 / BCC1 each
   with a pass/fail `StatusChip` showing computed vs. stored value, ATQA, SAK.
2. **Chip** — vendor, chip name, family, total bytes, page count, page size.
3. **Capabilities** — each `ChipCapability` as a chip: supported (filled) or
   "not supported by this chip" (outlined, muted). Absence is shown, never blank.
4. **Technologies** — one row per `android.nfc.tech.*` with `maxTransceiveLength` and
   timeout where the API exposes it.

### MemoryExplorerScreen

The centrepiece. A monospaced table, one row per page:

```
PAGE   HEX           ASCII   ACCESS
00     04 A2 55 71   ·¢Uq    read-only (UID)
01     18 39 FF 22   ·9·"    read-only (UID)
02     E1 48 00 00   ·H··    lock control
03     00 00 00 00   ····    OTP
04     03 2A D1 01   ·*··    locked
...
```

- Row expands to show per-byte binary (`0000 0100`) and per-byte decimal.
- `ACCESS` column is colour-coded from the lock analysis: writable (primary),
  locked (error), OTP (tertiary), read-only (muted).
- A page that failed to read shows its `ReadStatus` in place of bytes, in the error
  color, with the reason — never `00 00 00 00`, which would be a lie.
- A view toggle switches ASCII / binary / decimal as the secondary column.
- Long-press or trailing icon on a writable row opens the write sheet for that page.
- Horizontally scrollable within its own container so the page body never scrolls sideways.

### LockAnalysisScreen

1. **Raw** — `LOCK0` and `LOCK1` shown as bit grids, each bit labelled with its name
   (`BL_OTP`, `L_4`, …) and set/clear state. This is the teaching surface.
2. **Per-page verdict** — pages 0–15 with `WriteVerdict` and a one-line plain-English
   explanation ("locked by `L_7`; cannot be unlocked").
3. **Dynamic lock bits** — an explicit "Not supported by MF0ICU1 — introduced in
   Ultralight EV1 / NTAG21x" panel rather than an empty section.
4. **Block-locking** — the three `BL_*` bits with what each freezes.

### Write sheet

Modal bottom sheet, deliberately high-friction:

1. Target page (fixed, from the invoking row) and its current bytes.
2. Four hex byte inputs, validated to `00`–`FF`, with a live diff against current bytes.
3. The `WriteDecision` rendered before any action is possible:
   - `Allowed` → **Arm** button, then a distinct **Write** confirm. Two taps, never one.
   - `RequiresExpertMode` → warning panel naming the irreversibility, with a link to
     enable expert mode; the write path stays disabled until it is on.
   - `Blocked` → reason shown, no write control rendered at all.
4. Result panel: success with re-read verification, or the exception class, message, and
   a plain-English interpretation.

Expert mode is a session-scoped toggle in the app bar overflow, defaulting to off every
launch. It never persists — an irreversible capability should not be silently armed.

### SessionLogScreen

Reverse-chronological list; each entry shows timestamp (ms precision), level, category,
and message, expandable to its structured payload. Filter chips by level. Export action
in the app bar offering JSON or TXT, routed through `ACTION_CREATE_DOCUMENT`.

## Interaction Rules

- Tag I/O never blocks the UI thread; screens render from `StateFlow` only.
- Partial data always renders. Absence is labelled, never implied by a zero.
- Irreversible actions require two deliberate taps and a non-default toggle.
- Every error surfaces a name and an interpretation, not a raw stack trace.
- Re-tapping a tag starts a new session and appends to the same log.

## Typography and Color

- Binary data: `FontFamily.Monospace`, `bodyMedium`, `letterSpacing = 0.5.sp`.
- Labels: `labelMedium`, muted `onSurfaceVariant`.
- M3 dynamic color where available; a hand-tuned fallback scheme otherwise.
- Semantic roles are consistent app-wide: `error` = locked/failed, `primary` = writable,
  `tertiary` = OTP/caution, `onSurfaceVariant` = read-only/absent.
- Dark theme is the default appearance for a tool of this kind; light is fully supported.

## Animation

Restrained and purposeful only: the scan pulse, `AnimatedVisibility` on card expansion,
and a brief content fade when a new dump replaces an old one. No decorative motion.

## Accessibility

- All icon-only controls carry `contentDescription`.
- Hex bytes get a `semantics` reading that spells values ("zero four, A two") since
  screen readers mangle raw hex.
- Colour is never the sole carrier of the access verdict — text always states it.
- Minimum 48 dp touch targets; table rows meet this despite the dense look.
- Contrast verified against WCAG AA in both themes.

## Strings

All user-facing copy lives in `res/values/strings.xml` from the first screen — no
hardcoded literals in composables, per platform rules.

## `designs/`

No static mockups produced. The screens are specified structurally above and built
directly in Compose; a mockup layer would add drift without adding clarity for a
data-dense developer tool.
