# Spec — Tag Actions

## Current state

Phase 1 MVP complete: 284 tests, Detekt gating, `domain/` framework-free and enforced by
`DomainPurityTest`. This feature extends that structure rather than reshaping it. `ScanViewModel`
already holds `lastReport`, which the assignment UI reuses as the source of a UID.

## Architecture

```
domain/action/
  TagAction.kt            sealed: LaunchApp | OpenUri | SendIntent | MediaCommand
  TagAssignment.kt        uid + label + action
  TagActionRepository.kt  interface: observeAll / find(uid) / save / delete
data/action/
  TagActionSerializer.kt  TagAssignment <-> JSON via kotlinx.serialization DTOs
  TagActionStore.kt       DataStore<Preferences>, implements TagActionRepository
  TagActionRunner.kt      TagAction -> Intent, then starts it
TagActionActivity.kt      exported, no-UI, NFC-triggered
ui/actions/
  TagActionsScreen.kt     list, create, edit, delete, test
  TagActionsViewModel.kt
```

`domain/action` stays pure Kotlin: it describes *what* an action is, never how to run it.
`TagActionRunner` is the only place an `Intent` is constructed.

## Interfaces

```kotlin
sealed interface TagAction {
    data class LaunchApp(val packageName: String) : TagAction
    data class OpenUri(val uri: String) : TagAction
    data class SendIntent(
        val action: String,
        val uri: String? = null,
        val extras: Map<String, String> = emptyMap(),
    ) : TagAction
    data class MediaCommand(val key: MediaKey) : TagAction
}

enum class MediaKey { PLAY_PAUSE, NEXT, PREVIOUS }

data class TagAssignment(val uid: ByteBlock, val label: String, val action: TagAction)

interface TagActionRepository {
    fun observeAll(): Flow<List<TagAssignment>>
    suspend fun find(uid: ByteBlock): TagAssignment?
    suspend fun save(assignment: TagAssignment)
    suspend fun delete(uid: ByteBlock)
}
```

UIDs key the store as lowercase hex with no separators, matching the export filename convention.

## Security — the exported activity

`TagActionActivity` must be `exported="true"` or NFC dispatch cannot reach it. That means **any app on
the device can start it**, and it runs stored intents. Without a guard, another app could invoke it
repeatedly to fire whatever the user has configured.

It therefore acts only when **both** hold:

1. `intent.action` is one of `ACTION_TECH_DISCOVERED` / `ACTION_TAG_DISCOVERED` /
   `ACTION_NDEF_DISCOVERED`, **and** the intent carries a real `EXTRA_TAG`; and
2. the tag's UID matches a stored assignment.

Anything else finishes immediately without acting. AC6 and a dedicated test cover this, including the
case where a caller supplies a matching UID but not a genuine NFC action.

`SendIntent` extras are strings only. No parcelables, no component targeting — the action string and
data URI are the whole surface, which keeps what a stored action can express close to what a user
typed.

## Persistence

`DataStore<Preferences>` in private app storage, holding one JSON document.

**kotlinx.serialization is added for this**, which differs from the export path where JSON is
hand-encoded. The reason is round-tripping: the exporter only ever *writes*, so a hand-rolled encoder
with heavy escaping tests was proportionate. Persistence must also **read**, and hand-writing a JSON
parser is precisely where silent corruption lives. Data-layer DTOs carry the `@Serializable`
annotations so `domain/` stays annotation-free and `DomainPurityTest` keeps passing.

A malformed or unreadable store returns an empty list rather than throwing, so a bad write can never
brick the screen. Logged at WARN.

## Failure modes

| Condition | Behaviour |
|---|---|
| Unassigned UID | finish silently, no UI, no action (AC3) |
| Target app not installed | `ActivityNotFoundException` caught; logged; brief toast (AC5) |
| Malformed stored URI | caught at run time and reported; the editor also validates on entry |
| Store unreadable or corrupt | treated as empty, logged WARN |
| Activity launched by a non-NFC caller | finish immediately without acting (AC6) |
| Two assignments for one UID | impossible — UID is the store key |

## Testing strategy

JVM-testable, and that is most of the value:

- `TagActionRunnerTest` — every action type maps to the expected action string, data URI and extras.
  Built against an `IntentFactory` seam so assertions run without Android; the actual `startActivity`
  is the thin untested part, consistent with ADR 0001's reasoning.
- `TagActionSerializerTest` — round-trip for all four action types, plus malformed input returning
  empty rather than throwing.
- `TagActionStoreTest` — save, overwrite, delete, observe, using an in-memory DataStore.
- `TagActionsViewModelTest` — create from last scan, validation, delete, test-now.
- `TagActionDispatchTest` — the AC6 guard: non-NFC intents and missing `EXTRA_TAG` do not act.

Device-verified: tap with the app closed (AC2), tap an unassigned tag (AC3), and persistence across a
reboot (AC1).

## Non-functional

- Trigger-to-action under ~500 ms; nothing on the main thread but the `startActivity` call.
- The trigger activity has no theme and no content view, so no frame is ever drawn.
- Detekt stays green; new `ui/` files inherit the existing exclusions.

## ADR

None needed. This adds a feature within the established architecture; the one notable decision —
kotlinx.serialization for round-trip persistence versus hand-encoding for write-only export — is
recorded above.
