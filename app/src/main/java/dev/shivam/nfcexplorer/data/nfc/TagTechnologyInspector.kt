package dev.shivam.nfcexplorer.data.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import dev.shivam.nfcexplorer.domain.decoder.ChipProfileResolver
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.domain.model.NfcTechnology
import dev.shivam.nfcexplorer.domain.model.TagPresentation
import dev.shivam.nfcexplorer.domain.model.TagTechnologies
import dev.shivam.nfcexplorer.domain.model.TechnologyInfo
import dev.shivam.nfcexplorer.domain.model.UltralightVariant
import dev.shivam.nfcexplorer.util.toHex
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns an [android.nfc.Tag] into a [TagPresentation].
 *
 * Every metadata read is individually guarded. These getters cross into the NFC service, and a
 * tag that leaves the field mid-inspection makes any one of them throw — losing the whole
 * inventory because one optional field was unavailable would be a poor trade. A field that
 * cannot be read is simply absent, which the UI renders as absent rather than as zero.
 */
class TagTechnologyInspector @Inject constructor() {

    fun inspect(tag: Tag): TagPresentation {
        val technologies = TagTechnologies(tag.techList.map { name -> describe(tag, name) })
        val nfcA = safely { NfcA.get(tag) }

        return TagPresentation(
            uid = ByteBlock.copyOf(tag.id),
            atqa = safely { nfcA?.atqa }?.let(ByteBlock::copyOf),
            sak = safely { nfcA?.sak },
            technologies = technologies,
            chip = ChipProfileResolver.resolve(
                technologies = technologies,
                variant = ultralightVariant(tag),
                atqa = safely { nfcA?.atqa }?.let(ByteBlock::copyOf),
                sak = safely { nfcA?.sak },
                uidLength = tag.id.size,
            ),
        )
    }

    private fun ultralightVariant(tag: Tag): UltralightVariant =
        when (safely { MifareUltralight.get(tag)?.type }) {
            MifareUltralight.TYPE_ULTRALIGHT -> UltralightVariant.ULTRALIGHT
            MifareUltralight.TYPE_ULTRALIGHT_C -> UltralightVariant.ULTRALIGHT_C
            else -> UltralightVariant.UNKNOWN
        }

    private fun describe(tag: Tag, name: String): TechnologyInfo = when (name) {
        NfcTechnology.NFC_A -> safely { NfcA.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                timeoutMillis = safely { tech?.timeout },
                extras = buildMap {
                    safely { tech?.atqa }?.let { put("atqa", it.toHex()) }
                    safely { tech?.sak }?.let { put("sak", it.toInt().toString(16).uppercase()) }
                },
            )
        }

        NfcTechnology.MIFARE_ULTRALIGHT -> safely { MifareUltralight.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                timeoutMillis = safely { tech?.timeout },
                extras = buildMap {
                    put("variant", ultralightVariant(tag).name)
                },
            )
        }

        NfcTechnology.NDEF -> safely { Ndef.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = null,
                extras = buildMap {
                    safely { tech?.type }?.let { put("type", it) }
                    safely { tech?.maxSize }?.let { put("maxSize", it.toString()) }
                    safely { tech?.isWritable }?.let { put("writable", it.toString()) }
                    safely { tech?.canMakeReadOnly() }?.let { put("canMakeReadOnly", it.toString()) }
                },
            )
        }

        NfcTechnology.ISO_DEP -> safely { IsoDep.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                timeoutMillis = safely { tech?.timeout },
                extras = buildMap {
                    safely { tech?.historicalBytes }?.let { put("historicalBytes", it.toHex()) }
                    safely { tech?.hiLayerResponse }?.let { put("hiLayerResponse", it.toHex()) }
                },
            )
        }

        NfcTechnology.MIFARE_CLASSIC -> safely { MifareClassic.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                timeoutMillis = safely { tech?.timeout },
                extras = buildMap {
                    safely { tech?.size }?.let { put("sizeBytes", it.toString()) }
                    safely { tech?.sectorCount }?.let { put("sectorCount", it.toString()) }
                    safely { tech?.blockCount }?.let { put("blockCount", it.toString()) }
                },
            )
        }

        NfcTechnology.NFC_B -> safely { NfcB.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                extras = buildMap {
                    safely { tech?.applicationData }?.let { put("applicationData", it.toHex()) }
                    safely { tech?.protocolInfo }?.let { put("protocolInfo", it.toHex()) }
                },
            )
        }

        NfcTechnology.NFC_F -> safely { NfcF.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                timeoutMillis = safely { tech?.timeout },
                extras = buildMap {
                    safely { tech?.manufacturer }?.let { put("manufacturer", it.toHex()) }
                    safely { tech?.systemCode }?.let { put("systemCode", it.toHex()) }
                },
            )
        }

        NfcTechnology.NFC_V -> safely { NfcV.get(tag) }.let { tech ->
            TechnologyInfo(
                name = name,
                maxTransceiveLength = safely { tech?.maxTransceiveLength },
                extras = buildMap {
                    safely { tech?.dsfId }?.let { put("dsfId", it.toInt().toString(16).uppercase()) }
                    safely { tech?.responseFlags }
                        ?.let { put("responseFlags", it.toInt().toString(16).uppercase()) }
                },
            )
        }

        // Technologies with no metadata of their own (NdefFormatable, NfcBarcode, anything new).
        else -> TechnologyInfo(name = name)
    }

    /**
     * Returns null instead of propagating, so one unavailable field cannot abort the inventory.
     *
     * [CancellationException] is rethrown explicitly. It is an `Exception` in Kotlin, so a bare
     * `catch (e: Exception)` would swallow it and a cancelled scan would quietly continue.
     * [Error] is not caught at all — a JVM error is not ours to absorb.
     */
    private inline fun <T> safely(block: () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (expected: Exception) {
            null
        }
}
