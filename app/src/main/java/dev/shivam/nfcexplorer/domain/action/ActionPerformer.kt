package dev.shivam.nfcexplorer.domain.action

/**
 * Performs an action now.
 *
 * A seam so callers above it — the trigger activity and the "test now" button — do not depend on
 * Android. `TagActionRunner` is the real implementation; tests supply a recording fake.
 *
 * Returns [Result] rather than throwing: every caller runs somewhere a crash would be unhelpful, from
 * a no-UI activity fired by a tap to a button press whose failure should become a message.
 *
 * `suspend` because an action is no longer always instantaneous. A gesture takes about a second of
 * wall clock and a multi-step action waits between its steps; doing that on the caller's thread would
 * block the main thread of an activity started by a tap. Suspending keeps the timing honest — the
 * result still describes what actually happened — without anyone having to remember to hop threads.
 */
interface ActionPerformer {
    suspend fun perform(action: TagAction): Result<Unit>
}
