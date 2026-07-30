package dev.shivam.nfcexplorer.domain.decoder

import dev.shivam.nfcexplorer.domain.model.BccCheck
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.Manufacturer
import dev.shivam.nfcexplorer.domain.model.TagIdentity

/**
 * UID arithmetic: cascade levels and the two check bytes.
 *
 * The check bytes are worth verifying rather than displaying blindly. The UID reported by
 * the platform comes from the anticollision exchange, while BCC0 and BCC1 are read out of
 * pages 0 and 2 — so comparing them cross-checks two independent paths. A mismatch means a
 * corrupted read or a non-compliant tag, both of which the user wants to know about.
 *
 * See `docs/mf0icu1-reference.md`.
 */
object UidDecoder {

    /** Cascade tag prefixed to the first level of a multi-level UID, per ISO/IEC 14443-3. */
    private const val CASCADE_TAG = 0x88

    private const val SINGLE_SIZE = 4
    private const val DOUBLE_SIZE = 7
    private const val TRIPLE_SIZE = 10

    /**
     * Number of anticollision cascade levels implied by [uidLength], or null when the length
     * is not one the standard defines.
     */
    fun cascadeLevels(uidLength: Int): Int? = when (uidLength) {
        SINGLE_SIZE -> 1
        DOUBLE_SIZE -> 2
        TRIPLE_SIZE -> 3
        else -> null
    }

    /**
     * `BCC0 = 0x88 XOR SN0 XOR SN1 XOR SN2`.
     *
     * Null unless the UID is double-size: a single-size UID carries its check byte in the
     * anticollision frame rather than in page 0, so there is nothing here to compare against.
     */
    fun computeBcc0(uid: ByteBlock): Byte? {
        if (uid.size != DOUBLE_SIZE) return null
        return (CASCADE_TAG xor uid.unsignedAt(0) xor uid.unsignedAt(1) xor uid.unsignedAt(2))
            .toByte()
    }

    /** `BCC1 = SN3 XOR SN4 XOR SN5 XOR SN6`. Null unless the UID is double-size. */
    fun computeBcc1(uid: ByteBlock): Byte? {
        if (uid.size != DOUBLE_SIZE) return null
        return (uid.unsignedAt(3) xor uid.unsignedAt(4) xor uid.unsignedAt(5) xor uid.unsignedAt(6))
            .toByte()
    }

    /**
     * Assembles a [TagIdentity] from whatever was actually established.
     *
     * Every nullable input stays null on the way out rather than being defaulted, so the UI
     * can distinguish "not established" from a real zero. A check is produced only when both
     * the stored and computed values exist.
     */
    fun identify(
        uid: ByteBlock,
        atqa: ByteBlock? = null,
        sak: Short? = null,
        storedBcc0: Byte? = null,
        storedBcc1: Byte? = null,
    ): TagIdentity {
        require(!uid.isEmpty) { "a tag always reports a UID; empty input is a caller bug" }

        return TagIdentity(
            uid = uid,
            atqa = atqa,
            sak = sak,
            manufacturer = Manufacturer.fromUidByte0(uid[0]),
            cascadeLevels = cascadeLevels(uid.size),
            bcc0 = check("BCC0", storedBcc0, computeBcc0(uid)),
            bcc1 = check("BCC1", storedBcc1, computeBcc1(uid)),
        )
    }

    private fun check(label: String, stored: Byte?, computed: Byte?): BccCheck? =
        if (stored != null && computed != null) BccCheck(label, stored, computed) else null
}
