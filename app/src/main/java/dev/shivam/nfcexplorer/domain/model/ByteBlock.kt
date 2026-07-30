package dev.shivam.nfcexplorer.domain.model

/**
 * An immutable block of bytes.
 *
 * Domain models describe tag memory, so they are byte-valued almost everywhere. Holding a
 * raw [ByteArray] in a `data class` would break two things at once: `equals`/`hashCode`
 * compare by identity rather than content, and the array stays mutable after construction
 * so a caller can rewrite a value object in place. Both matter for a tool whose whole job
 * is reporting bytes accurately.
 *
 * Every entry point copies, so a [ByteBlock] can never alias a caller's array.
 */
class ByteBlock private constructor(private val bytes: ByteArray) {

    val size: Int get() = bytes.size

    val isEmpty: Boolean get() = bytes.isEmpty()

    operator fun get(index: Int): Byte = bytes[index]

    /** The byte at [index] widened to its unsigned `0..255` value. */
    fun unsignedAt(index: Int): Int = bytes[index].toInt() and 0xFF

    /** Defensive copy — callers may mutate the result freely. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    fun asList(): List<Byte> = bytes.toList()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ByteBlock && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Uppercase space-separated hex, e.g. `04 A2 55 71`. Intended for logs and debugging. */
    override fun toString(): String =
        bytes.joinToString(" ") { byte ->
            val value = byte.toInt() and 0xFF
            "${HEX_DIGITS[value ushr 4]}${HEX_DIGITS[value and 0x0F]}"
        }

    companion object {
        private const val HEX_DIGITS = "0123456789ABCDEF"

        val EMPTY: ByteBlock = ByteBlock(ByteArray(0))

        fun copyOf(source: ByteArray): ByteBlock =
            if (source.isEmpty()) EMPTY else ByteBlock(source.copyOf())

        fun of(vararg bytes: Byte): ByteBlock = copyOf(bytes)

        /** Convenience for literals: `ByteBlock.ofInts(0x04, 0xA2, 0x55, 0x71)`. */
        fun ofInts(vararg values: Int): ByteBlock =
            copyOf(ByteArray(values.size) { index -> values[index].toByte() })
    }
}
