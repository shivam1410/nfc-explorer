package dev.shivam.nfcexplorer.domain.usecase

import dev.shivam.nfcexplorer.domain.decoder.StaticLockDecoder
import dev.shivam.nfcexplorer.domain.decoder.UidDecoder
import dev.shivam.nfcexplorer.domain.model.BccCheck
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.MemoryDump
import dev.shivam.nfcexplorer.domain.model.PageSnapshot
import dev.shivam.nfcexplorer.domain.model.ReadStatus
import dev.shivam.nfcexplorer.domain.model.TagPresentation
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagNakException
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.logging.SessionLogger
import dev.shivam.nfcexplorer.util.toHex
import java.io.IOException

/**
 * Dumps every page a chip claims to have and decodes the result.
 *
 * Blocking: performs tag I/O on the calling thread. Callers move it off the main thread —
 * `TagRepositoryImpl` runs it on `Dispatchers.IO`. Kept dispatcher-free so the domain layer
 * does not choose a threading policy.
 *
 * ### Wrap-around (invariant I2)
 *
 * `READ` returns four pages and rolls over past the end of memory, so the final stride of a
 * chip whose page count is not a multiple of four returns pages that belong at the *start*.
 * Appending them blindly would report page 0 as page 45 on an NTAG213 — a silently scrambled
 * dump. Every stride is therefore clamped to the chip's page count and the wrapped tail
 * discarded. See `docs/mf0icu1-reference.md`.
 *
 * ### Partial dumps are first class
 *
 * A tag that leaves the field mid-dump produces a report, not an exception. The stride that
 * was in flight is marked [ReadStatus.TAG_LOST] because it was genuinely attempted; pages
 * after it are [ReadStatus.NOT_ATTEMPTED] because they were never asked for. Conflating the
 * two would lose real information about where the read stopped.
 */
class ReadTagUseCase(
    private val logger: SessionLogger,
) {

    operator fun invoke(
        transport: UltralightTransport,
        presentation: TagPresentation,
    ): TagReport {
        val pageCount = presentation.chip.pageCount
        val pageSize = presentation.chip.pageSize

        val memory = if (pageCount <= 0 || pageSize <= 0) {
            // An unidentified chip has no known geometry. Reading addresses blind would
            // invent structure, so nothing is attempted.
            logger.warn(
                category = CATEGORY,
                message = "chip geometry unknown; no pages read",
                payload = mapOf("chip" to presentation.chip.chipName.ifEmpty { "unidentified" }),
            )
            MemoryDump.EMPTY
        } else {
            dump(transport, pageCount, pageSize)
        }

        val lockBytes = staticLockBytes(memory)
        val report = TagReport(
            presentation = presentation,
            identity = UidDecoder.identify(
                uid = presentation.uid,
                atqa = presentation.atqa,
                sak = presentation.sak,
                storedBcc0 = memory.page(UID_PAGE_0)?.bytes?.get(BCC0_OFFSET),
                storedBcc1 = memory.page(LOCK_PAGE)?.bytes?.get(BCC1_OFFSET),
            ),
            memory = memory,
            locks = StaticLockDecoder.decode(lockBytes),
        )

        logger.info(
            category = CATEGORY,
            message = "dump finished",
            payload = mapOf(
                "pagesRead" to memory.readableCount.toString(),
                "pagesTotal" to memory.pages.size.toString(),
                "complete" to memory.isComplete.toString(),
            ),
        )

        // The image and lock state are logged as text on purpose. A dump that only exists on
        // screen cannot be captured, diffed, or attached to a bug report, which is most of what
        // makes this a debugging tool rather than a viewer.
        if (memory.pages.isNotEmpty()) {
            logger.info(
                category = CATEGORY,
                message = "memory image",
                payload = mapOf("image" to renderImage(memory)),
            )
            logger.info(
                category = CATEGORY,
                message = "lock state",
                payload = mapOf(
                    "lockBytes" to (report.locks.staticLockBytes?.toString() ?: "unreadable"),
                    "lockedPages" to report.locks.lockedPages.joinToString(),
                    "writablePages" to report.locks.writablePages.joinToString(),
                    "bcc0" to bccSummary(report.identity.bcc0),
                    "bcc1" to bccSummary(report.identity.bcc1),
                ),
            )
        }
        return report
    }

    /**
     * `00:04 A2 55 71 | 01:18 39 FF 22 | ...`, with `??` for pages that did not read.
     *
     * Unreadable pages are marked rather than zero-filled so the text cannot be mistaken for a
     * complete image — the same rule the UI follows.
     */
    private fun renderImage(memory: MemoryDump): String =
        memory.pages.joinToString(" | ") { page ->
            val index = page.index.toString(16).uppercase().padStart(2, '0')
            val bytes = page.bytes?.toString() ?: "?? ?? ?? ??"
            "$index:$bytes"
        }

    private fun bccSummary(check: BccCheck?): String = when {
        check == null -> "not established"
        check.isValid -> "valid"
        else -> "MISMATCH stored=${check.stored.toHex()} computed=${check.computed.toHex()}"
    }

    private fun dump(
        transport: UltralightTransport,
        pageCount: Int,
        pageSize: Int,
    ): MemoryDump {
        val pages = MutableList(pageCount) { index ->
            PageSnapshot.failed(index, ReadStatus.NOT_ATTEMPTED)
        }

        var offset = 0
        while (offset < pageCount) {
            // Clamp: the chip returns four pages regardless, but only this many are real.
            val pagesInStride = minOf(UltralightTransport.PAGES_PER_READ, pageCount - offset)

            try {
                val frame = transport.readPages(offset)
                for (position in 0 until pagesInStride) {
                    val start = position * pageSize
                    pages[offset + position] = PageSnapshot.ok(
                        index = offset + position,
                        bytes = ByteBlock.copyOf(frame.copyOfRange(start, start + pageSize)),
                    )
                }
            } catch (lost: TagFieldLostException) {
                markStride(pages, offset, pagesInStride, ReadStatus.TAG_LOST, lost)
                logger.error(
                    category = CATEGORY,
                    message = "tag left the field mid-dump",
                    payload = mapOf(
                        "pageOffset" to offset.toString(),
                        "exception" to (lost::class.simpleName ?: "TagFieldLostException"),
                    ),
                )
                // Everything past this stride stays NOT_ATTEMPTED: the tag is gone, and
                // retrying would only produce more failures to report.
                return MemoryDump(pages, pageSize)
            } catch (refused: TagNakException) {
                markStride(pages, offset, pagesInStride, ReadStatus.NAK_REFUSED, refused)
                logger.warn(
                    category = CATEGORY,
                    message = "tag refused a read",
                    payload = mapOf(
                        "pageOffset" to offset.toString(),
                        "exception" to (refused::class.simpleName ?: "TagNakException"),
                    ),
                )
            } catch (failure: IOException) {
                markStride(pages, offset, pagesInStride, ReadStatus.IO_ERROR, failure)
                logger.error(
                    category = CATEGORY,
                    message = "read failed",
                    payload = mapOf(
                        "pageOffset" to offset.toString(),
                        "exception" to (failure::class.simpleName ?: "IOException"),
                    ),
                )
            }

            offset += UltralightTransport.PAGES_PER_READ
        }

        return MemoryDump(pages, pageSize)
    }

    private fun markStride(
        pages: MutableList<PageSnapshot>,
        offset: Int,
        pagesInStride: Int,
        status: ReadStatus,
        cause: Throwable,
    ) {
        val detail = cause::class.simpleName
        for (position in 0 until pagesInStride) {
            pages[offset + position] = PageSnapshot.failed(offset + position, status, detail)
        }
    }

    /** The two static lock bytes, or null when page `0x02` was not readable. */
    private fun staticLockBytes(memory: MemoryDump): ByteBlock? {
        val lockPage = memory.page(LOCK_PAGE)?.bytes ?: return null
        if (lockPage.size <= LOCK1_OFFSET) return null
        return ByteBlock.ofInts(
            lockPage.unsignedAt(LOCK0_OFFSET),
            lockPage.unsignedAt(LOCK1_OFFSET),
        )
    }

    private companion object {
        const val CATEGORY = "read"

        const val UID_PAGE_0 = 0
        const val LOCK_PAGE = 2

        /** Page 0 byte 3 holds BCC0. */
        const val BCC0_OFFSET = 3

        /** Page 2 byte 0 holds BCC1; bytes 2 and 3 hold the lock bytes. */
        const val BCC1_OFFSET = 0
        const val LOCK0_OFFSET = 2
        const val LOCK1_OFFSET = 3
    }
}
