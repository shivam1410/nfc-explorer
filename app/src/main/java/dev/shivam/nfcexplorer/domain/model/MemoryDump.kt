package dev.shivam.nfcexplorer.domain.model

/**
 * Outcome of attempting to read one page.
 *
 * [NOT_ATTEMPTED] is a first-class state, not an error: when a tag leaves the field
 * mid-dump the pages after that point were never asked for, which is different from a
 * page that refused.
 */
enum class ReadStatus {
    OK,
    NAK_REFUSED,
    IO_ERROR,
    TAG_LOST,
    NOT_ATTEMPTED,
}

/**
 * One page of tag memory.
 *
 * [bytes] is null for every status other than [ReadStatus.OK]. That is deliberate: a page
 * that could not be read must be impossible to render as `00 00 00 00`, because that is
 * indistinguishable from a page of genuine zeros. [detail] carries a short technical note
 * (an exception class name, for instance) and is never user-facing prose.
 */
data class PageSnapshot(
    val index: Int,
    val bytes: ByteBlock?,
    val status: ReadStatus,
    val detail: String? = null,
) {
    val isReadable: Boolean get() = status == ReadStatus.OK && bytes != null

    init {
        require(index >= 0) { "page index must be non-negative, was $index" }
        require((status == ReadStatus.OK) == (bytes != null)) {
            "status $status and bytes presence disagree for page $index"
        }
    }

    companion object {
        fun ok(index: Int, bytes: ByteBlock) = PageSnapshot(index, bytes, ReadStatus.OK)

        fun failed(index: Int, status: ReadStatus, detail: String? = null): PageSnapshot {
            require(status != ReadStatus.OK) { "use ok() for successful reads" }
            return PageSnapshot(index, bytes = null, status = status, detail = detail)
        }
    }
}

/**
 * A full read attempt over a tag's memory. Always covers every page the chip claims to
 * have, so a partial dump is visible as such rather than as a short list.
 */
data class MemoryDump(
    val pages: List<PageSnapshot>,
    val pageSize: Int,
) {
    init {
        // readableBytes() concatenates in list order, so ordering is an invariant rather than
        // an assumption. Enforced here so it cannot silently produce a scrambled image.
        require(pages.zipWithNext().all { (first, second) -> first.index < second.index }) {
            "pages must be ordered by ascending index with no duplicates, got " +
                pages.map { it.index }
        }
        require(pages.all { page -> page.bytes == null || page.bytes.size == pageSize }) {
            "every readable page must hold exactly pageSize ($pageSize) bytes"
        }
    }

    val readableCount: Int get() = pages.count { it.isReadable }

    val isComplete: Boolean get() = pages.isNotEmpty() && pages.all { it.isReadable }

    fun page(index: Int): PageSnapshot? = pages.firstOrNull { it.index == index }

    /** Concatenated bytes of readable pages only, for whole-image views. */
    fun readableBytes(): ByteBlock {
        val output = ByteArray(readableCount * pageSize)
        var cursor = 0
        pages.forEach { page ->
            page.bytes?.let { block ->
                block.toByteArray().copyInto(output, cursor)
                cursor += block.size
            }
        }
        return ByteBlock.copyOf(output)
    }

    companion object {
        val EMPTY = MemoryDump(pages = emptyList(), pageSize = 0)
    }
}
