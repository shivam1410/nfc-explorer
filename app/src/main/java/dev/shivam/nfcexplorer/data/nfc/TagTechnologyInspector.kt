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

    /**
     * Dispatches to a per-technology reader.
     *
     * One function per technology rather than one long `when`: each reads a different set of
     * getters, and keeping them separate means adding a technology in Phase 2 touches one small
     * function instead of extending a branch that was already 90 lines and complexity 35.
     */
    private fun describe(tag: Tag, name: String): TechnologyInfo = when (name) {
        NfcTechnology.NFC_A -> describeNfcA(tag, name)
        NfcTechnology.MIFARE_ULTRALIGHT -> describeUltralight(tag, name)
        NfcTechnology.NDEF -> describeNdef(tag, name)
        NfcTechnology.ISO_DEP -> describeIsoDep(tag, name)
        NfcTechnology.MIFARE_CLASSIC -> describeMifareClassic(tag, name)
        NfcTechnology.NFC_B -> describeNfcB(tag, name)
        NfcTechnology.NFC_F -> describeNfcF(tag, name)
        NfcTechnology.NFC_V -> describeNfcV(tag, name)
        // Technologies with no metadata of their own (NdefFormatable, NfcBarcode, anything new).
        else -> TechnologyInfo(name = name)
    }

    private fun describeNfcA(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { NfcA.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            timeoutMillis = safely { tech?.timeout },
            extras = buildMap {
                safely { tech?.atqa }?.let { put("atqa", it.toHex()) }
                safely { tech?.sak }?.let { put("sak", hex(it.toInt())) }
            },
        )
    }

    private fun describeUltralight(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { MifareUltralight.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            timeoutMillis = safely { tech?.timeout },
            extras = mapOf("variant" to ultralightVariant(tag).name),
        )
    }

    private fun describeNdef(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { Ndef.get(tag) }
        return TechnologyInfo(
            name = name,
            extras = buildMap {
                safely { tech?.type }?.let { put("type", it) }
                safely { tech?.maxSize }?.let { put("maxSize", it.toString()) }
                safely { tech?.isWritable }?.let { put("writable", it.toString()) }
                safely { tech?.canMakeReadOnly() }?.let { put("canMakeReadOnly", it.toString()) }
            },
        )
    }

    private fun describeIsoDep(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { IsoDep.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            timeoutMillis = safely { tech?.timeout },
            extras = buildMap {
                safely { tech?.historicalBytes }?.let { put("historicalBytes", it.toHex()) }
                safely { tech?.hiLayerResponse }?.let { put("hiLayerResponse", it.toHex()) }
            },
        )
    }

    private fun describeMifareClassic(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { MifareClassic.get(tag) }
        return TechnologyInfo(
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

    private fun describeNfcB(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { NfcB.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            extras = buildMap {
                safely { tech?.applicationData }?.let { put("applicationData", it.toHex()) }
                safely { tech?.protocolInfo }?.let { put("protocolInfo", it.toHex()) }
            },
        )
    }

    private fun describeNfcF(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { NfcF.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            timeoutMillis = safely { tech?.timeout },
            extras = buildMap {
                safely { tech?.manufacturer }?.let { put("manufacturer", it.toHex()) }
                safely { tech?.systemCode }?.let { put("systemCode", it.toHex()) }
            },
        )
    }

    private fun describeNfcV(tag: Tag, name: String): TechnologyInfo {
        val tech = safely { NfcV.get(tag) }
        return TechnologyInfo(
            name = name,
            maxTransceiveLength = safely { tech?.maxTransceiveLength },
            extras = buildMap {
                safely { tech?.dsfId }?.let { put("dsfId", hex(it.toInt())) }
                safely { tech?.responseFlags }?.let { put("responseFlags", hex(it.toInt())) }
            },
        )
    }

    private fun hex(value: Int): String = "%02X".format(value and 0xFF)

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
