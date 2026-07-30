package dev.shivam.nfcexplorer.fake

import dev.shivam.nfcexplorer.domain.transport.UltralightTransport.Companion.BYTES_PER_PAGE

/**
 * Synthetic MF0ICU1 memory images.
 *
 * Check bytes are computed rather than hardcoded, so a fixture can never encode a BCC that
 * contradicts its own UID — which would make BCC verification tests pass for the wrong reason.
 */
object Mf0icu1Fixtures {

    /** Seven-byte NXP UID (byte 0 = 0x04). */
    val SAMPLE_UID = byteArrayOf(0x04, 0xA2.toByte(), 0x55, 0x71, 0x18, 0x39, 0xFF.toByte())

    /** Value NXP ships in the page-2 internal byte. */
    const val INTERNAL_BYTE: Byte = 0x48

    const val LOCK_PAGE = 2
    const val OTP_PAGE = 3
    const val FIRST_USER_PAGE = 4

    /**
     * Byte array from int literals.
     *
     * Kotlin's `Byte` is signed, so `byteArrayOf(0x90)` will not compile and every value
     * above 0x7F needs `.toByte()`. In a file that is almost entirely hex literals that
     * noise hides real mistakes.
     */
    fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { index -> values[index].toByte() }

    fun bcc0(uid: ByteArray): Byte =
        (0x88 xor uid[0].toInt() xor uid[1].toInt() xor uid[2].toInt()).toByte()

    fun bcc1(uid: ByteArray): Byte =
        (uid[3].toInt() xor uid[4].toInt() xor uid[5].toInt() xor uid[6].toInt()).toByte()

    /**
     * Builds a 64-byte image with a well-formed header.
     *
     * @param userData bytes written from page 4 onward, zero-padded and truncated to fit.
     */
    fun image(
        uid: ByteArray = SAMPLE_UID,
        lock0: Int = 0x00,
        lock1: Int = 0x00,
        otp: ByteArray = ByteArray(BYTES_PER_PAGE),
        userData: ByteArray = ByteArray(0),
    ): ByteArray {
        require(uid.size == 7) { "MF0ICU1 uses a 7-byte UID, got ${uid.size}" }
        require(otp.size == BYTES_PER_PAGE) { "OTP page is $BYTES_PER_PAGE bytes" }

        val memory = ByteArray(FakeUltralightTransport.TOTAL_BYTES)

        // Page 0: SN0 SN1 SN2 BCC0
        uid.copyInto(memory, destinationOffset = 0, startIndex = 0, endIndex = 3)
        memory[3] = bcc0(uid)

        // Page 1: SN3..SN6
        uid.copyInto(memory, destinationOffset = 4, startIndex = 3, endIndex = 7)

        // Page 2: BCC1, internal, LOCK0, LOCK1
        memory[8] = bcc1(uid)
        memory[9] = INTERNAL_BYTE
        memory[10] = lock0.toByte()
        memory[11] = lock1.toByte()

        // Page 3: OTP
        otp.copyInto(memory, destinationOffset = OTP_PAGE * BYTES_PER_PAGE)

        // Pages 4-15: user data
        val userOffset = FIRST_USER_PAGE * BYTES_PER_PAGE
        val copyLength = minOf(userData.size, memory.size - userOffset)
        userData.copyInto(memory, destinationOffset = userOffset, startIndex = 0, endIndex = copyLength)

        return memory
    }

    /** Factory-fresh tag: valid header, no locks, all user pages writable and zeroed. */
    fun blank(): ByteArray = image()

    /**
     * Every lock bit set: OTP and pages 4-15 permanently read-only.
     * `LOCK0 = 0xFF` also sets all three block-locking bits, freezing the lock bits themselves.
     */
    fun fullyLocked(): ByteArray = image(lock0 = 0xFF, lock1 = 0xFF)

    /**
     * Block-locking bits set with no page actually locked.
     *
     * The interesting case: pages 4-15 stay writable *forever*, because their lock bits can
     * no longer be changed. Locked and frozen are independent facts.
     */
    fun blockLocked(): ByteArray = image(lock0 = 0x07)

    /**
     * Shape a hotel key card typically takes: opaque payload, OTP and every user page locked
     * after personalisation, and no NDEF TLV anywhere — which is why NDEF-oriented apps
     * report the tag as unsupported.
     */
    fun hotelCardLike(): ByteArray = image(
        lock0 = 0xF8, // L_OTP | L_4 | L_5 | L_6 | L_7
        lock1 = 0xFF, // L_8..L_15
        otp = bytes(0x00, 0x00, 0x00, 0x00),
        userData = bytes(
            0x5A, 0x11, 0x03, 0x7C,
            0x00, 0x1E, 0x44, 0x90,
            0x21, 0x08, 0x14, 0x06,
            0x00, 0x00, 0x00, 0x00,
            0xA3, 0x5F, 0x00, 0x01,
        ),
    )

    /**
     * The real hotel card this app was built to investigate, byte for byte.
     *
     * Captured from hardware on 2026-07-30; see
     * `.aw_docs/features/nfc-explorer-mvp/evidence/hotel-card-dump.md`. Worth having as a fixture
     * because it is the combination that actually turns up in the wild and that synthetic cases
     * miss: **completely unlocked** (`LOCK0 = LOCK1 = 0x00`) yet carrying a personalised payload,
     * with a dirty OTP page that is not an NDEF capability container.
     *
     * The UID here is genuinely on the tag; it is a serial number, not a secret — it is broadcast
     * unencrypted to any reader in range before authentication of any kind.
     */
    fun unlockedHotelCard(): ByteArray = image(
        uid = bytes(0x04, 0x0E, 0x66, 0xA2, 0xF0, 0x7B, 0x81),
        lock0 = 0x00,
        lock1 = 0x00,
        otp = bytes(0x46, 0x0D, 0xAE, 0x11),
        userData = bytes(
            0xE2, 0x42, 0x1B, 0x5E,
            0x36, 0x56, 0x3A, 0x96,
            0xCA, 0xC7, 0xC4, 0x88,
            0xC2, 0xBD, 0xD7, 0x19,
            0x67, 0x03, 0x03, 0xFC,
            0x4D, 0xD4, 0xBF, 0x32,
        ),
    )

    /** Printable ASCII in the user area, for renderer tests. */
    fun withAsciiPayload(text: String): ByteArray =
        image(userData = text.toByteArray(Charsets.US_ASCII))
}
