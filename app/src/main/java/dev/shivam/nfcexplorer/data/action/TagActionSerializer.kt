package dev.shivam.nfcexplorer.data.action

import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.util.parseHexBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialises assignments to and from a single JSON document.
 *
 * Uses kotlinx.serialization rather than the hand-written encoder in `domain/export`, and the
 * difference is deliberate: the exporter only ever *writes*, so a hand-rolled encoder with heavy
 * escaping tests was proportionate. This must also **read**, and hand-writing a parser is exactly
 * where silent corruption lives.
 *
 * The DTOs live here, in `data/`, so `@Serializable` never reaches `domain/` and `DomainPurityTest`
 * keeps passing.
 *
 * **Nothing here throws.** A malformed document, an unknown action type, or an entry that violates a
 * domain invariant all degrade to "fewer assignments" rather than an exception. This code runs on the
 * dispatch path, triggered by a tap with no user watching, and a crash there is invisible and useless.
 */
object TagActionSerializer {

    private const val SCHEMA_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(assignments: List<TagAssignment>): String =
        // Explicit serializer rather than the reified overload: the DTOs are private, which the
        // inline reified form cannot reach.
        json.encodeToString(
            StoreDto.serializer(),
            StoreDto(version = SCHEMA_VERSION, assignments = assignments.map(::toDto)),
        )

    fun decode(document: String?): List<TagAssignment> {
        if (document.isNullOrBlank()) return emptyList()

        val store = runCatching { json.decodeFromString(StoreDto.serializer(), document) }
            .getOrNull()
            ?: return emptyList()

        // mapNotNull, so one bad entry costs one assignment rather than all of them.
        return store.assignments.mapNotNull(::toDomainOrNull)
    }

    private fun toDto(assignment: TagAssignment) = AssignmentDto(
        uidHex = assignment.uidKey,
        label = assignment.label,
        action = when (val action = assignment.action) {
            is TagAction.LaunchApp -> ActionDto(type = TYPE_LAUNCH_APP, packageName = action.packageName)
            is TagAction.OpenUri -> ActionDto(type = TYPE_OPEN_URI, uri = action.uri)
            is TagAction.SendIntent -> ActionDto(
                type = TYPE_SEND_INTENT,
                intentAction = action.action,
                uri = action.uri,
                extras = action.extras,
            )
            is TagAction.MediaCommand -> ActionDto(type = TYPE_MEDIA, mediaKey = action.key.name)
        },
    )

    /**
     * Null for anything unusable: an unparseable UID, an unknown action type, a missing field, or a
     * value the domain type's own `init` rejects. Construction is wrapped because those `require`
     * checks are the last line of defence against hand-edited or corrupted storage.
     */
    private fun toDomainOrNull(dto: AssignmentDto): TagAssignment? = runCatching {
        val bytes = dto.uidHex.parseHexBytes() ?: return null
        val action = toActionOrNull(dto.action) ?: return null
        TagAssignment(uid = ByteBlock.copyOf(bytes), label = dto.label, action = action)
    }.getOrNull()

    private fun toActionOrNull(dto: ActionDto): TagAction? = runCatching {
        when (dto.type) {
            TYPE_LAUNCH_APP -> dto.packageName?.let(TagAction::LaunchApp)
            TYPE_OPEN_URI -> dto.uri?.let(TagAction::OpenUri)
            TYPE_SEND_INTENT -> dto.intentAction?.let { action ->
                TagAction.SendIntent(action = action, uri = dto.uri, extras = dto.extras)
            }
            TYPE_MEDIA -> dto.mediaKey
                ?.let { name -> MediaKey.entries.firstOrNull { it.name == name } }
                ?.let(TagAction::MediaCommand)
            // Written by a newer build. Skip this entry, keep the rest.
            else -> null
        }
    }.getOrNull()

    private const val TYPE_LAUNCH_APP = "launchApp"
    private const val TYPE_OPEN_URI = "openUri"
    private const val TYPE_SEND_INTENT = "sendIntent"
    private const val TYPE_MEDIA = "media"

    @Serializable
    private data class StoreDto(
        val version: Int,
        val assignments: List<AssignmentDto> = emptyList(),
    )

    @Serializable
    private data class AssignmentDto(
        @SerialName("uidHex") val uidHex: String,
        @SerialName("label") val label: String,
        @SerialName("action") val action: ActionDto,
    )

    /**
     * One flat shape for every action type.
     *
     * A polymorphic hierarchy would be tidier, but an unknown `type` from a newer build must be
     * *skippable* rather than a decode failure for the whole document — and a flat DTO with a string
     * discriminator gives that for free.
     */
    @Serializable
    private data class ActionDto(
        @SerialName("type") val type: String,
        @SerialName("packageName") val packageName: String? = null,
        @SerialName("uri") val uri: String? = null,
        @SerialName("intentAction") val intentAction: String? = null,
        @SerialName("mediaKey") val mediaKey: String? = null,
        @SerialName("extras") val extras: Map<String, String> = emptyMap(),
    )
}
