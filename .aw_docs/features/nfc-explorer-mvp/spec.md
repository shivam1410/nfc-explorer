# Spec — NFC Explorer (Phase 1 MVP)

## Implementation Goal

A single-module Android app (`applicationId dev.shivam.nfcexplorer`) with strict Clean
Architecture package boundaries, whose NFC decode logic is fully provable on the JVM
because all tag I/O sits behind a fake-able transport interface.

## Current State

Greenfield. `/Users/shivamhl/Shivam/nfc` is empty and not a git repository. No existing
patterns to extend; this spec establishes them.

## Scope

Phase 1 items from `prd.md`. Ultralight MF0ICU1 is the only fully-modelled chip; the
type system is built so Phase 2 chips slot in without reshaping the domain.

## Non-Goals

As `prd.md`. Notably: no raw-transceive console in Phase 1, but `TagTransport.transceive`
exists from day one so Phase 3 needs no re-plumbing.

## Architecture

### Package layout (single `:app` module)

```
dev.shivam.nfcexplorer
├── NfcExplorerApplication.kt          @HiltAndroidApp
├── MainActivity.kt                    reader-mode host, Compose entry
├── domain/                            PURE KOTLIN — no android.* imports, enforced
│   ├── model/
│   │   ├── TagIdentity.kt             uid, atqa, sak, cascade, bcc, manufacturer
│   │   ├── ChipProfile.kt             vendor, chipName, family, geometry, capabilities
│   │   ├── TagTechnologies.kt         technology inventory + per-tech metadata
│   │   ├── MemoryDump.kt              pages + per-page ReadStatus
│   │   ├── LockAnalysis.kt            static lock bytes, block-lock bits, page verdicts
│   │   └── WriteDecision.kt           guard outcome
│   ├── transport/
│   │   ├── TagTransport.kt            generic: transceive, maxTransceiveLength
│   │   └── UltralightTransport.kt     readPages(offset): 16B wrapping, writePage
│   ├── decoder/
│   │   ├── UidDecoder.kt              BCC0/BCC1, cascade levels, uid length
│   │   ├── ManufacturerDecoder.kt     ISO/IEC 7816-6 byte-0 vendor table
│   │   ├── ChipProfileResolver.kt     ATQA/SAK/tech-set -> ChipProfile
│   │   ├── StaticLockDecoder.kt       page 0x02 bytes 2-3 -> per-page verdict
│   │   └── MemoryRenderer.kt          hex / binary / printable-ASCII
│   ├── writer/
│   │   └── WriteGuard.kt              pure policy: page + lock state + expert -> decision
│   ├── repository/
│   │   └── TagRepository.kt           interface, returns domain models
│   └── usecase/
│       ├── ReadTagUseCase.kt
│       ├── WritePageUseCase.kt
│       └── ExportSessionUseCase.kt
├── data/
│   ├── nfc/
│   │   ├── AndroidUltralightTransport.kt   wraps MifareUltralight
│   │   ├── AndroidTagTransport.kt          wraps NfcA for raw transceive
│   │   ├── TagTechnologyInspector.kt       Tag -> TagTechnologies
│   │   └── NfcReaderModeController.kt      enableReaderMode -> callbackFlow<Tag>
│   ├── repository/
│   │   └── TagRepositoryImpl.kt            orchestrates transport + decoders on IO
│   └── export/
│       ├── JsonSessionExporter.kt
│       └── TextSessionExporter.kt
├── logging/
│   ├── SessionLogger.kt               append-only, StateFlow<List<LogEntry>>
│   └── LogEntry.kt
├── ui/
│   ├── theme/                         M3 color/typography/shape, dark + light
│   ├── component/                     SectionCard, HexPageRow, StatusChip, KeyValueRow
│   ├── scan/                          ScanScreen + ScanViewModel
│   ├── taginfo/                       TagInfoScreen
│   ├── memory/                        MemoryExplorerScreen
│   ├── locks/                         LockAnalysisScreen
│   ├── write/                         WriteScreen + WriteViewModel
│   └── log/                           SessionLogScreen
├── di/                                Hilt modules
└── util/                              Hex.kt, ByteArrayExt.kt
```

### Dependency direction

`ui -> domain`, `data -> domain`, `di -> all`, `domain -> nothing`.
`domain` importing `android.*` is an architecture violation; Phase 1 includes a unit
test that fails the build if it happens (see Testing Strategy).

## Interfaces / Contracts

### Transport seam — the critical design decision

```kotlin
// domain/transport/TagTransport.kt
interface TagTransport {
    val maxTransceiveLength: Int
    fun connect()
    fun close()
    fun transceive(command: ByteArray): ByteArray
}

// domain/transport/UltralightTransport.kt
interface UltralightTransport : TagTransport {
    /** Returns exactly 16 bytes (4 pages) starting at [pageOffset], wrapping past the last page. */
    fun readPages(pageOffset: Int): ByteArray
    fun writePage(pageOffset: Int, data: ByteArray)   // data.size == 4
}
```

**Rationale.** Every decoder, the memory reader, the lock analyser, and the write guard
depend only on this interface. `AndroidUltralightTransport` is a thin adapter over
`android.nfc.tech.MifareUltralight`; `FakeUltralightTransport` (test source set) emulates
MF0ICU1 semantics — 64-byte backing array, wrap-around reads, OR-on-write for OTP,
set-only lock bits, `IOException` on writing a locked page. That fake is what makes
AC7 achievable without hardware, and it is the difference between real tests and
mock theatre.

### Chip geometry and capabilities

```kotlin
data class ChipProfile(
    val vendor: String,            // "NXP Semiconductors"
    val chipName: String,          // "MF0ICU1"
    val family: String,            // "MIFARE Ultralight"
    val totalBytes: Int,           // 64
    val pageCount: Int,            // 16
    val pageSize: Int,             // 4
    val capabilities: Set<ChipCapability>,
)

enum class ChipCapability { FAST_READ, GET_VERSION, PWD_AUTH, DYNAMIC_LOCK_BITS, COUNTERS, NDEF }
```

MF0ICU1 resolves to `capabilities = emptySet()` — deliberately, and the UI renders each
absent capability as "not supported by this chip" so the user learns the chip's limits
instead of seeing blank fields.

### MF0ICU1 memory map (authoritative for the decoder)

| Page | Bytes | Role |
|---|---|---|
| `0x00` | SN0 SN1 SN2 BCC0 | UID part 1 + check byte; `BCC0 = 0x88 ^ SN0 ^ SN1 ^ SN2` |
| `0x01` | SN3 SN4 SN5 SN6 | UID part 2 |
| `0x02` | BCC1, internal, LOCK0, LOCK1 | `BCC1 = SN3 ^ SN4 ^ SN5 ^ SN6` |
| `0x03` | OTP0..OTP3 | One-time programmable, OR-ed on write |
| `0x04`–`0x0F` | user data | 48 bytes |

### Static lock byte decode

`LOCK0` = page `0x02` byte 2:

| bit | meaning |
|---|---|
| 0 | `BL_OTP` — block-locking: freezes the OTP lock bit |
| 1 | `BL_9_4` — block-locking: freezes lock bits for pages 4–9 |
| 2 | `BL_15_10` — block-locking: freezes lock bits for pages 10–15 |
| 3 | `L_OTP` — locks page `0x03` |
| 4–7 | `L_4`..`L_7` — lock pages 4–7 |

`LOCK1` = page `0x02` byte 3: bits 0–7 = `L_8`..`L_15`.

Every lock bit is **set-only**: once 1, it can never return to 0. Block-locking bits
freeze the corresponding lock bits, making the locked/unlocked state itself permanent.

### Page access classification

```kotlin
enum class PageRole { UID, LOCK_CONTROL, OTP, USER_DATA }

enum class WriteVerdict {
    WRITABLE,
    PERMANENTLY_LOCKED,     // lock bit set
    OTP_ONE_WAY,            // page 3: bits can only be OR-ed to 1
    HARDWARE_READ_ONLY,     // pages 0-1: UID, not writable by design
    LOCK_CONTROL,           // page 2: writing here is irreversible
}
```

### Write guard (pure policy, fully unit-tested)

```kotlin
sealed interface WriteDecision {
    data object Allowed : WriteDecision
    data class RequiresExpertMode(val reason: String) : WriteDecision
    data class Blocked(val reason: String) : WriteDecision
}

class WriteGuard {
    fun evaluate(page: Int, data: ByteArray, locks: LockAnalysis, expertMode: Boolean): WriteDecision
}
```

Rules: page 0–1 → always `Blocked`. Page 2 → `RequiresExpertMode` (irreversible lock
control). Page 3 → `RequiresExpertMode` (OTP, OR-only). Pages 4–15 → `Blocked` if the
lock bit is set, else `Allowed`. `data.size != 4` → `Blocked`. Page out of range → `Blocked`.

### Reader mode

```kotlin
class NfcReaderModeController @Inject constructor() {
    fun tagEvents(activity: Activity): Flow<Tag>   // callbackFlow, awaitClose { disableReaderMode }
}
```

Flags: `FLAG_READER_NFC_A or FLAG_READER_NFC_B or FLAG_READER_NFC_F or FLAG_READER_NFC_V or
FLAG_READER_SKIP_NDEF_CHECK or FLAG_READER_NO_PLATFORM_SOUNDS`.

`SKIP_NDEF_CHECK` matters: it stops the platform performing its own NDEF probe, which
both disturbs the tag and is exactly the behaviour that makes other apps report
"not supported". `EXTRA_READER_PRESENCE_CHECK_DELAY` set to 250 ms to reduce
mid-session tag loss. `onTagDiscovered` arrives on a binder thread, so the pipeline
runs on `Dispatchers.IO` and never touches the main thread.

## Failure Modes

| Condition | Handling |
|---|---|
| `MifareUltralight.get(tag)` returns null | Report chip as non-Ultralight; fall back to `NfcA` identity-only view |
| `TagLostException` mid-dump | Partial dump preserved; remaining pages marked `NOT_ATTEMPTED`; log entry names the page reached |
| Tag NAKs a read | Page marked `NAK_REFUSED`; dump continues to the next page |
| `IOException` on write | Surfaced with the page, the bytes attempted, and the lock state at the time |
| `SecurityException` | Reported as "tag handle no longer valid — the tag was re-dispatched"; session invalidated |
| NFC disabled / absent in hardware | Distinguished states: `NfcAdapter` null → unsupported device; adapter disabled → deep-link to settings |

## Invariants

- **I1** `domain/` contains zero `android.*` imports.
- **I2** A page index reported in the UI equals the physical page index on the tag,
  regardless of `readPages` wrap-around.
- **I3** No write reaches the transport without a `WriteDecision.Allowed`.
- **I4** The session log is append-only; nothing mutates or removes a prior entry.
- **I5** All tag I/O runs off the main thread.

## Testing Strategy

JVM unit tests (`testDebugUnitTest`) with JUnit4 + kotlin.test + Turbine for Flow.
`FakeUltralightTransport` provides synthetic MF0ICU1 dumps, including a fixture built
from a plausible locked hotel-card layout.

Covered test-first: `UidDecoder` (BCC0/BCC1 valid and corrupted), `ManufacturerDecoder`,
`StaticLockDecoder` (all-unlocked, fully-locked, block-locked, mixed), page-access
classification, `MemoryRenderer` (hex/binary/ASCII incl. non-printable), `WriteGuard`
(all rule branches), and the memory reader's wrap-around correctness.

**I1 is enforced by test**, not convention: `DomainPurityTest` walks
`src/main/java/dev/shivam/nfcexplorer/domain` and fails on any `import android.`.

**Deliberate deviation from the blanket "test file per source file" rule:** Compose
screens and Hilt modules get no unit tests. Their correctness is visual and structural;
a Robolectric harness for them would cost more than it proves at this stage. They are
verified by the on-device smoke checklist in `tasks.md` Phase 6. ViewModels *are*
unit-tested, because they hold state logic. This is a scoped exception with a stated
reason, not silent omission.

Device verification is required for anything touching real hardware and is a distinct,
explicitly-labelled evidence class in `verification.md`.

## Non-Functional Requirements

- Cold start to scan-ready < 1 s on the Pixel 10.
- Full 16-page dump completes within one tag presentation (< 300 ms of transceive).
- No ANR: zero tag I/O on the main thread (I5).
- Dark and light M3 themes, dynamic color where available.

## Toolchain

Gradle 8.14.3 (cached — bootstrap the wrapper from
`~/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle`,
then use `./gradlew` exclusively). `compileSdk`/`targetSdk` 36, `minSdk 26`, JDK 17.
AGP, Kotlin, KSP, Hilt and Compose BOM versions pinned in `gradle/libs.versions.toml`
and **validated by a real `assembleDebug` in Phase 0** rather than trusted from memory —
if a pin fails to resolve, correct it against the actual Gradle error.

Manifest: `<uses-permission android:name="android.permission.NFC" />` and
`<uses-feature android:name="android.hardware.nfc" android:required="true" />`.
No storage permission — export uses SAF.

## Acceptance Criteria

Inherits AC1–AC8 from `prd.md`. Additionally:
- `./gradlew :app:testDebugUnitTest` green, including `DomainPurityTest`.
- `./gradlew :app:assembleDebug` green with zero deprecation warnings on NFC APIs.

## ADR

One ADR is warranted and is produced in Phase 1: **"Tag I/O behind a fake-able transport
interface"** — the decision that makes an inherently hardware-bound app testable without
hardware, and the constraint every later phase must respect.
