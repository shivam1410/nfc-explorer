package dev.shivam.nfcexplorer.domain.model

/**
 * Chip manufacturer identified by byte 0 of the UID, per the ISO/IEC 7816-6 registration
 * authority table.
 *
 * [Known.name] is a company name, not translatable prose, so it stays in the domain. An
 * unrecognised code is reported as [Unknown] with the raw value preserved — never guessed
 * and never silently blanked.
 */
sealed interface Manufacturer {

    val code: Int

    data class Known(override val code: Int, val name: String) : Manufacturer

    data class Unknown(override val code: Int) : Manufacturer

    companion object {
        /**
         * Subset of the ISO/IEC 7816-6 table covering the vendors actually encountered in
         * NFC tags. Deliberately partial: an absent code resolves to [Unknown] with its
         * raw value rather than being force-fitted to a nearby entry.
         */
        private val NAMES: Map<Int, String> = mapOf(
            0x01 to "Motorola",
            0x02 to "STMicroelectronics",
            0x03 to "Hitachi",
            0x04 to "NXP Semiconductors",
            0x05 to "Infineon Technologies",
            0x07 to "Texas Instruments",
            0x08 to "Fujitsu",
            0x0A to "NEC",
            0x0C to "Toshiba",
            0x0E to "Samsung Electronics",
            0x15 to "Atmel",
            0x16 to "EM Microelectronic-Marin",
            0x17 to "SMARTRAC Technology",
            0x1A to "Sony",
            0x1D to "Shanghai Fudan Microelectronics",
            0x20 to "Renesas Technology",
            0x27 to "Yubico",
            0x2B to "Maxim Integrated",
            0x2C to "Impinj",
            0x2E to "Broadcom",
            0x34 to "Mikron JSC",
            0x3D to "HID Global",
            0x40 to "Gemalto",
            0x41 to "Renesas Electronics",
        )

        fun fromUidByte0(value: Byte): Manufacturer {
            val code = value.toInt() and 0xFF
            val name = NAMES[code]
            return if (name != null) Known(code, name) else Unknown(code)
        }
    }
}
