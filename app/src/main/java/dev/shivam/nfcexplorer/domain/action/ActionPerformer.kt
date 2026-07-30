package dev.shivam.nfcexplorer.domain.action

/**
 * Performs an action now.
 *
 * A seam so callers above it — the trigger activity and the "test now" button — do not depend on
 * Android. `TagActionRunner` is the real implementation; tests supply a recording fake.
 *
 * Returns [Result] rather than throwing: every caller runs somewhere a crash would be unhelpful, from
 * a no-UI activity fired by a tap to a button press whose failure should become a message.
 */
interface ActionPerformer {
    fun perform(action: TagAction): Result<Unit>
}
