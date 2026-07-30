package dev.shivam.nfcexplorer.domain.model

/**
 * Fully-qualified `android.nfc.tech.*` names.
 *
 * Plain string identifiers rather than framework class references, so the domain layer can
 * reason about which technologies a tag advertises without importing `android.*`.
 */
object NfcTechnology {
    const val NFC_A = "android.nfc.tech.NfcA"
    const val NFC_B = "android.nfc.tech.NfcB"
    const val NFC_F = "android.nfc.tech.NfcF"
    const val NFC_V = "android.nfc.tech.NfcV"
    const val ISO_DEP = "android.nfc.tech.IsoDep"
    const val MIFARE_CLASSIC = "android.nfc.tech.MifareClassic"
    const val MIFARE_ULTRALIGHT = "android.nfc.tech.MifareUltralight"
    const val NDEF = "android.nfc.tech.Ndef"
    const val NDEF_FORMATABLE = "android.nfc.tech.NdefFormatable"
    const val NFC_BARCODE = "android.nfc.tech.NfcBarcode"

    /** Short label for display, e.g. `android.nfc.tech.NfcA` -> `NfcA`. */
    fun shortName(qualifiedName: String): String = qualifiedName.substringAfterLast('.')
}

/**
 * What the platform managed to determine about an Ultralight-family tag.
 *
 * Mirrors `MifareUltralight.getType()` without depending on it. [ULTRALIGHT] is a *family*
 * answer, not a chip answer: MF0ICU1, Ultralight EV1 and every NTAG21x report it identically.
 */
enum class UltralightVariant {
    /** Original Ultralight or an NTAG/EV1 that cannot be told apart without `GET_VERSION`. */
    ULTRALIGHT,

    /** Ultralight C, identified by the platform through an authentication probe. */
    ULTRALIGHT_C,

    /** Exposes `MifareUltralight` but the platform could not classify it further. */
    UNKNOWN,
}
