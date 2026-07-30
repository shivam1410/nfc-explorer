package dev.shivam.nfcexplorer.domain.repository

import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.model.WriteBatchResult

/**
 * An opaque reference to the tag currently in the field.
 *
 * The domain layer must name the tag in repository signatures but cannot import
 * `android.nfc.Tag`, so implementations wrap it. Valid only while the tag is present.
 */
interface TagHandle

/**
 * Reads and writes tags, returning domain models.
 *
 * Every method returns [Result] rather than throwing: a tag leaving the field mid-operation is
 * an ordinary outcome for this app, not an exceptional one, and the failure carries information
 * the UI needs to explain what happened.
 *
 * Note that a *failed read* and a *partial read* are different things. A partial dump comes back
 * as [Result.success] holding a [TagReport] whose pages carry per-page status — throwing it away
 * as a failure would discard exactly the evidence the user wants.
 */
interface TagRepository {

    suspend fun read(handle: TagHandle): Result<TagReport>

    /**
     * Writes a consecutive run of pages starting at [startPage].
     *
     * Implementations re-read the tag's lock state as part of this call rather than accepting it
     * from the caller. Lock state is what decides whether a write is permitted, and a value carried
     * over from an earlier scan may be stale — the tag could have been locked in between. It must
     * come from the tag being written, in the same session.
     *
     * @param expertMode when true, permits the irreversible writes that are otherwise gated. It can
     *   never permit a write the guard blocks outright.
     */
    suspend fun writePages(
        handle: TagHandle,
        startPage: Int,
        pages: List<ByteArray>,
        expertMode: Boolean,
    ): Result<WriteBatchResult>
}
