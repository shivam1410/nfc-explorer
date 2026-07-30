package dev.shivam.nfcexplorer.domain.export

import dev.shivam.nfcexplorer.domain.decoder.MemoryRenderer
import dev.shivam.nfcexplorer.domain.model.BccCheck
import dev.shivam.nfcexplorer.domain.model.ChipCapability
import dev.shivam.nfcexplorer.domain.model.DynamicLockSupport
import dev.shivam.nfcexplorer.domain.model.Manufacturer
import dev.shivam.nfcexplorer.domain.model.TagReport
import dev.shivam.nfcexplorer.logging.LogEntry
import dev.shivam.nfcexplorer.util.toHex

/** Export file formats. */
enum class ExportFormat(val extension: String, val mimeType: String) {
    JSON("json", "application/json"),
    TEXT("txt", "text/plain"),
}

/**
 * Serialises a session to a file body.
 *
 * Both formats hold the same rule as the UI: **a page that could not be read is never rendered as
 * zeros.** JSON emits `"hex": null` alongside the status; text emits `??`. An export that quietly
 * turned unread pages into `00 00 00 00` would be worse than no export, because the file outlives
 * the session that knew better.
 *
 * [report] may be null so a log-only session is still exportable — the log is often the interesting
 * part when nothing would read.
 */
interface SessionExporter {
    val format: ExportFormat

    fun export(report: TagReport?, entries: List<LogEntry>, exportedAtMillis: Long): String
}

private const val SCHEMA_VERSION = 1
private const val APP_NAME = "NFC Explorer"

object JsonSessionExporter : SessionExporter {
    override val format = ExportFormat.JSON

    override fun export(
        report: TagReport?,
        entries: List<LogEntry>,
        exportedAtMillis: Long,
    ): String = Json.encode(
        linkedMapOf(
            "app" to APP_NAME,
            // Bumped if the shape changes, so a consumer can tell old files from new.
            "schemaVersion" to SCHEMA_VERSION,
            "exportedAtMillis" to exportedAtMillis,
            "tag" to report?.let(::identityOf),
            "chip" to report?.let(::chipOf),
            "technologies" to report?.let(::technologiesOf),
            "memory" to report?.let(::memoryOf),
            "locks" to report?.let(::locksOf),
            "log" to entries.map(::logEntryOf),
        ),
    )

    private fun identityOf(report: TagReport) = linkedMapOf(
        "uid" to report.identity.uid.toString(),
        "uidLength" to report.identity.uidLength,
        "cascadeLevels" to report.identity.cascadeLevels,
        "manufacturer" to when (val maker = report.identity.manufacturer) {
            is Manufacturer.Known -> linkedMapOf("code" to maker.code, "name" to maker.name)
            is Manufacturer.Unknown -> linkedMapOf("code" to maker.code, "name" to null)
        },
        "atqa" to report.identity.atqa?.toString(),
        "sak" to report.identity.sak?.let { "%02X".format(it.toInt() and 0xFF) },
        "bcc0" to bccOf(report.identity.bcc0),
        "bcc1" to bccOf(report.identity.bcc1),
    )

    private fun bccOf(check: BccCheck?) = check?.let {
        linkedMapOf(
            "stored" to it.stored.toHex(),
            "computed" to it.computed.toHex(),
            "valid" to it.isValid,
        )
    }

    private fun chipOf(report: TagReport) = linkedMapOf(
        "vendor" to report.chip.vendor,
        "chipName" to report.chip.chipName,
        "family" to report.chip.family,
        "totalBytes" to report.chip.totalBytes,
        "pageCount" to report.chip.pageCount,
        "pageSize" to report.chip.pageSize,
        "geometryConfirmed" to report.chip.geometryConfirmed,
        // Absent capabilities are listed too, so a consumer sees what the chip cannot do.
        "capabilities" to ChipCapability.entries.associate { capability ->
            capability.name to report.chip.supports(capability)
        }.toSortedMap().let { LinkedHashMap(it) },
    )

    private fun technologiesOf(report: TagReport) = report.technologies.available.map { tech ->
        linkedMapOf(
            "name" to tech.name,
            "maxTransceiveLength" to tech.maxTransceiveLength,
            "timeoutMillis" to tech.timeoutMillis,
            "extras" to LinkedHashMap(tech.extras),
        )
    }

    private fun memoryOf(report: TagReport) = linkedMapOf(
        "pageSize" to report.memory.pageSize,
        "pagesRead" to report.memory.readableCount,
        "pagesTotal" to report.memory.pages.size,
        "complete" to report.memory.isComplete,
        "pages" to report.memory.pages.map { page ->
            linkedMapOf(
                "index" to page.index,
                "status" to page.status.name,
                // Null, never zeros. See the class doc.
                "hex" to MemoryRenderer.hex(page),
                "ascii" to MemoryRenderer.ascii(page),
                "detail" to page.detail,
            )
        },
    )

    private fun locksOf(report: TagReport) = linkedMapOf(
        "lockBytes" to report.locks.staticLockBytes?.toString(),
        "lockedPages" to report.locks.lockedPages,
        "writablePages" to report.locks.writablePages,
        "lockBits" to report.locks.lockBits.map { bit ->
            linkedMapOf(
                "name" to bit.name,
                "set" to bit.isSet,
                "page" to bit.protectedPage,
                "frozen" to bit.isFrozen,
            )
        },
        "blockLockBits" to report.locks.blockLockBits.map { bit ->
            linkedMapOf(
                "name" to bit.name,
                "set" to bit.isSet,
                "freezesFrom" to bit.freezesPages.first,
                "freezesTo" to bit.freezesPages.last,
            )
        },
        "pageAccess" to report.locks.pageAccess.map { access ->
            linkedMapOf(
                "page" to access.page,
                "role" to access.role.name,
                "verdict" to access.verdict.name,
                "lockedBy" to access.lockedBy,
            )
        },
        "dynamicLockBits" to when (val support = report.locks.dynamicLockSupport) {
            is DynamicLockSupport.NotSupportedByChip -> linkedMapOf(
                "supported" to false,
                "introducedIn" to support.introducedIn,
            )
            is DynamicLockSupport.Present -> linkedMapOf(
                "supported" to true,
                "bytes" to support.bytes.toString(),
            )
        },
    )

    private fun logEntryOf(entry: LogEntry) = linkedMapOf(
        "sequence" to entry.sequence,
        "timestampMillis" to entry.timestampMillis,
        "level" to entry.level.name,
        "category" to entry.category,
        "message" to entry.message,
        "payload" to LinkedHashMap(entry.payload),
    )
}

object TextSessionExporter : SessionExporter {
    override val format = ExportFormat.TEXT

    override fun export(
        report: TagReport?,
        entries: List<LogEntry>,
        exportedAtMillis: Long,
    ): String = buildString {
        appendLine("$APP_NAME — session export")
        appendLine("exportedAtMillis  $exportedAtMillis")
        appendLine()

        if (report == null) {
            appendLine("No tag was captured in this session.")
        } else {
            appendIdentity(report)
            appendChip(report)
            appendMemory(report)
            appendLocks(report)
        }

        appendLine("SESSION LOG")
        appendLine("-".repeat(72))
        if (entries.isEmpty()) {
            appendLine("(empty)")
        } else {
            entries.forEach { entry ->
                appendLine("[${entry.sequence}] ${entry.timestampMillis} ${entry.level} ${entry.category}: ${entry.message}")
                entry.payload.forEach { (key, value) -> appendLine("        $key = $value") }
            }
        }
    }

    private fun StringBuilder.appendIdentity(report: TagReport) {
        val identity = report.identity
        appendLine("IDENTITY")
        appendLine("-".repeat(72))
        appendLine("UID           ${identity.uid}")
        appendLine("UID length    ${identity.uidLength} bytes")
        appendLine("Cascade       ${identity.cascadeLevels ?: "unknown"}")
        appendLine("Manufacturer  ${identity.manufacturer}")
        appendLine("ATQA          ${identity.atqa ?: "not established"}")
        appendLine(
            "SAK           " +
                (identity.sak?.let { "%02X".format(it.toInt() and 0xFF) } ?: "not established"),
        )
        appendLine("BCC0          ${bccLine(identity.bcc0)}")
        appendLine("BCC1          ${bccLine(identity.bcc1)}")
        appendLine()
    }

    private fun bccLine(check: BccCheck?): String = when {
        check == null -> "not established"
        check.isValid -> "valid (${check.stored.toHex()})"
        else -> "MISMATCH stored=${check.stored.toHex()} computed=${check.computed.toHex()}"
    }

    private fun StringBuilder.appendChip(report: TagReport) {
        val chip = report.chip
        appendLine("CHIP")
        appendLine("-".repeat(72))
        appendLine("Vendor        ${chip.vendor.ifEmpty { "—" }}")
        appendLine("Chip          ${chip.chipName.ifEmpty { "not confirmed" }}")
        appendLine("Family        ${chip.family.ifEmpty { "—" }}")
        appendLine(
            "Geometry      ${chip.pageCount} pages x ${chip.pageSize} B" +
                if (chip.geometryConfirmed) " (confirmed)" else " (floor, unconfirmed)",
        )
        appendLine()
    }

    private fun StringBuilder.appendMemory(report: TagReport) {
        appendLine("MEMORY  ${report.memory.readableCount}/${report.memory.pages.size} pages read")
        appendLine("-".repeat(72))
        report.memory.pages.forEach { page ->
            // Unreadable pages show ?? plus the reason, never zeros.
            val hex = MemoryRenderer.hex(page) ?: "?? ?? ?? ??"
            val ascii = MemoryRenderer.ascii(page) ?: ""
            val note = if (page.isReadable) "" else "  (${page.status})"
            appendLine("%02X  %-14s %-6s%s".format(page.index, hex, ascii, note))
        }
        appendLine()
    }

    private fun StringBuilder.appendLocks(report: TagReport) {
        val locks = report.locks
        appendLine("LOCK ANALYSIS")
        appendLine("-".repeat(72))
        appendLine("Lock bytes    ${locks.staticLockBytes ?: "unreadable"}")
        appendLine("Locked pages  ${locks.lockedPages.ifEmpty { "none" }}")
        appendLine("Writable      ${locks.writablePages.ifEmpty { "none" }}")
        locks.lockBits.forEach { bit ->
            appendLine(
                "  ${bit.name.padEnd(8)} page %02X  %s%s".format(
                    bit.protectedPage,
                    if (bit.isSet) "set" else "clear",
                    if (bit.isFrozen) ", frozen" else "",
                ),
            )
        }
        locks.blockLockBits.forEach { bit ->
            appendLine(
                "  ${bit.name.padEnd(8)} freezes %02X-%02X  %s".format(
                    bit.freezesPages.first,
                    bit.freezesPages.last,
                    if (bit.isSet) "set" else "clear",
                ),
            )
        }
        appendLine("Dynamic lock  ${locks.dynamicLockSupport}")
        appendLine()
    }
}
