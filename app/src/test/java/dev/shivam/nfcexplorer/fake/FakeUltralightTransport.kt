package dev.shivam.nfcexplorer.fake

import dev.shivam.nfcexplorer.domain.model.ChipProfile
import dev.shivam.nfcexplorer.domain.transport.TagFieldLostException
import dev.shivam.nfcexplorer.domain.transport.TagNakException
import dev.shivam.nfcexplorer.domain.transport.TagNotConnectedException
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport.Companion.BYTES_PER_PAGE
import dev.shivam.nfcexplorer.domain.transport.UltralightTransport.Companion.BYTES_PER_READ

/**
 * An in-memory MIFARE Ultralight MF0ICU1.
 *
 * This is the reason the decode layer is testable at all — NFC hardware does not exist in
 * the emulator, so without a fake that behaves like the real chip there would be no way to
 * prove any of this logic without a phone in hand.
 *
 * It is deliberately not a permissive stub. It enforces the chip's actual semantics, so a
 * test passing against it means something:
 *
 *  - `READ` returns four pages and **wraps** past the end of memory.
 *  - Pages 0-1 hold the UID and reject writes.
 *  - Page 2: `BCC1` and the internal byte ignore writes; the two lock bytes are **OR-ed**.
 *  - Page 3 (OTP) is **OR-ed** — bits set, never cleared.
 *  - Pages 4-15 reject writes once their lock bit is set.
 *
 * See `docs/mf0icu1-reference.md`.
 *
 * @param initialMemory 64 bytes of starting content.
 * @param failFromPage when set, any read at or past this page raises [TagFieldLostException],
 *   simulating the tag being pulled away mid-dump.
 * @param nakPages pages that refuse to be read. A real chip NAKs the whole four-page window,
 *   so any read whose window covers one of these fails.
 * @param failOnConnect when true, [connect] raises [TagFieldLostException]. Models a tag that is gone
 *   before the connection opens — and, for the trigger's guard, a `Tag` parcel with no live session
 *   behind it, which is the case a hostile caller can construct.
 */
class FakeUltralightTransport(
    initialMemory: ByteArray = ByteArray(TOTAL_BYTES),
    private val failFromPage: Int? = null,
    private val nakPages: Set<Int> = emptySet(),
    private val failOnConnect: Boolean = false,
) : UltralightTransport {

    init {
        require(initialMemory.size == TOTAL_BYTES) {
            "MF0ICU1 has $TOTAL_BYTES bytes, got ${initialMemory.size}"
        }
    }

    private val memory: ByteArray = initialMemory.copyOf()

    private val recordedWrites = mutableListOf<WriteAttempt>()

    var isConnected: Boolean = false
        private set

    var isClosed: Boolean = false
        private set

    var readCount: Int = 0
        private set

    /**
     * Every write that reached the transport, accepted or rejected.
     *
     * Lets a test assert the stronger property that a blocked write never arrives here at
     * all, rather than merely that memory is unchanged.
     */
    val writes: List<WriteAttempt> get() = recordedWrites.toList()

    override val maxTransceiveLength: Int = 253

    override fun connect() {
        check(!isClosed) { "cannot reconnect a closed transport" }
        if (failOnConnect) throw TagFieldLostException()
        isConnected = true
    }

    override fun close() {
        isConnected = false
        isClosed = true
    }

    override fun transceive(command: ByteArray): ByteArray {
        requireConnected()
        throw TagNakException("raw transceive is not modelled by the fake")
    }

    override fun readPages(pageOffset: Int): ByteArray {
        requireConnected()
        if (pageOffset < 0 || pageOffset >= PAGE_COUNT) {
            throw TagNakException("page $pageOffset outside 0..${PAGE_COUNT - 1}")
        }
        failFromPage?.let { boundary ->
            if (pageOffset >= boundary) throw TagFieldLostException()
        }
        // The chip NAKs the entire command if any page in the window is unreadable.
        val window = (0 until PAGES_PER_READ_LOCAL).map { (pageOffset + it) % PAGE_COUNT }
        window.firstOrNull { it in nakPages }?.let { refused ->
            throw TagNakException("page $refused refused the read")
        }

        readCount++
        return ByteArray(BYTES_PER_READ) { offset ->
            memory[(pageOffset * BYTES_PER_PAGE + offset) % TOTAL_BYTES]
        }
    }

    override fun writePage(pageOffset: Int, data: ByteArray) {
        requireConnected()
        recordedWrites += WriteAttempt(pageOffset, data.copyOf())

        if (data.size != BYTES_PER_PAGE) {
            throw TagNakException("write payload must be $BYTES_PER_PAGE bytes, got ${data.size}")
        }
        if (pageOffset < 0 || pageOffset >= PAGE_COUNT) {
            throw TagNakException("page $pageOffset outside 0..${PAGE_COUNT - 1}")
        }
        if (pageOffset in UID_PAGES) {
            throw TagNakException("page $pageOffset holds the UID and is fixed in hardware")
        }

        when (pageOffset) {
            // BCC1 and the internal byte ignore writes; the lock bytes accumulate bits.
            LOCK_PAGE -> {
                orInto(LOCK_PAGE, byteIndex = 2, value = data[2])
                orInto(LOCK_PAGE, byteIndex = 3, value = data[3])
            }
            // OTP: bits can be set but never cleared.
            OTP_PAGE -> {
                if (isLocked(OTP_PAGE)) throw TagNakException("page $OTP_PAGE is locked")
                for (index in 0 until BYTES_PER_PAGE) orInto(OTP_PAGE, index, data[index])
            }
            else -> {
                if (isLocked(pageOffset)) throw TagNakException("page $pageOffset is locked")
                data.copyInto(memory, pageOffset * BYTES_PER_PAGE)
            }
        }
    }

    /** Current memory contents. Copy, so a test cannot mutate the fake through it. */
    fun snapshot(): ByteArray = memory.copyOf()

    fun page(index: Int): ByteArray =
        memory.copyOfRange(index * BYTES_PER_PAGE, (index + 1) * BYTES_PER_PAGE)

    private fun orInto(page: Int, byteIndex: Int, value: Byte) {
        val position = page * BYTES_PER_PAGE + byteIndex
        memory[position] = (memory[position].toInt() or value.toInt()).toByte()
    }

    /** Reads the lock bit governing [page] straight out of memory, as the chip would. */
    private fun isLocked(page: Int): Boolean {
        val lock0 = memory[LOCK_PAGE * BYTES_PER_PAGE + 2].toInt() and 0xFF
        val lock1 = memory[LOCK_PAGE * BYTES_PER_PAGE + 3].toInt() and 0xFF
        return when (page) {
            OTP_PAGE -> lock0 and 0x08 != 0
            in 4..7 -> lock0 and (1 shl page) != 0
            in 8..15 -> lock1 and (1 shl (page - 8)) != 0
            else -> false
        }
    }

    private fun requireConnected() {
        if (!isConnected || isClosed) throw TagNotConnectedException()
    }

    data class WriteAttempt(val page: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is WriteAttempt && page == other.page && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * page + data.contentHashCode()
    }

    companion object {
        val PAGE_COUNT = ChipProfile.MF0ICU1.pageCount
        val TOTAL_BYTES = ChipProfile.MF0ICU1.totalBytes

        private const val PAGES_PER_READ_LOCAL = 4
        private val UID_PAGES = 0..1
        private const val LOCK_PAGE = 2
        private const val OTP_PAGE = 3
    }
}
