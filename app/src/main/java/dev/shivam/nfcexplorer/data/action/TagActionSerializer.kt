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
        action = actionDto(assignment.action),
        updatedAtMillis = assignment.updatedAtMillis,
    )

    /**
     * Recursive, but only ever one level deep: [TagAction.Steps] rejects nesting and
     * [TagAction.WhileNotificationShowing] takes leaves for both branches.
     */
    private fun actionDto(action: TagAction): ActionDto = when (action) {
        is TagAction.LaunchApp -> ActionDto(type = TYPE_LAUNCH_APP, packageName = action.packageName)
        is TagAction.OpenUri -> ActionDto(type = TYPE_OPEN_URI, uri = action.uri)
        is TagAction.SendIntent -> ActionDto(
            type = TYPE_SEND_INTENT,
            intentAction = action.action,
            uri = action.uri,
            extras = action.extras,
        )
        is TagAction.MediaCommand -> ActionDto(type = TYPE_MEDIA, mediaKey = action.key.name)
        is TagAction.DragGesture -> ActionDto(
            type = TYPE_DRAG,
            drag = DragDto(
                startXRatio = action.startXRatio,
                startYRatio = action.startYRatio,
                endXRatio = action.endXRatio,
                endYRatio = action.endYRatio,
                holdMillis = action.holdMillis,
                travelMillis = action.travelMillis,
                steps = action.steps,
                requireForegroundPackage = action.requireForegroundPackage,
            ),
        )
        is TagAction.WhatsAppMessage -> ActionDto(
            type = TYPE_WHATSAPP,
            packageName = action.phoneNumber,
            intentAction = action.message,
            autoSend = action.autoSend,
        )
        is TagAction.TapNode -> ActionDto(
            type = TYPE_TAP_NODE,
            packageName = action.viewId,
            intentAction = action.contentDescription,
            uri = null,
            channelId = action.requireForegroundPackage,
        )
        is TagAction.TogglToggle -> ActionDto(
            type = TYPE_TOGGL,
            workspaceId = action.workspaceId,
            // Reused rather than a new column: the description is the human label of the entry.
            intentAction = action.description,
            projectId = action.projectId,
        )
        is TagAction.Steps -> ActionDto(
            type = TYPE_STEPS,
            steps = action.steps.map(::actionDto),
            gapMillis = action.gapMillis,
        )
        is TagAction.WhileNotificationShowing -> ActionDto(
            type = TYPE_WHILE_NOTIFICATION,
            packageName = action.packageName,
            channelId = action.channelId,
            showing = actionDto(action.showing),
            absent = actionDto(action.absent),
        )
    }

    /**
     * Null for anything unusable: an unparseable UID, an unknown action type, a missing field, or a
     * value the domain type's own `init` rejects. Construction is wrapped because those `require`
     * checks are the last line of defence against hand-edited or corrupted storage.
     */
    private fun toDomainOrNull(dto: AssignmentDto): TagAssignment? = runCatching {
        val bytes = dto.uidHex.parseHexBytes() ?: return null
        val action = toActionOrNull(dto.action) ?: return null
        TagAssignment(
            uid = ByteBlock.copyOf(bytes),
            label = dto.label,
            action = action,
            updatedAtMillis = dto.updatedAtMillis,
        )
    }.getOrNull()

    private fun toActionOrNull(dto: ActionDto): TagAction? = runCatching {
        when (dto.type) {
            TYPE_WHILE_NOTIFICATION -> {
                val pkg = dto.packageName ?: return@runCatching null
                val channel = dto.channelId ?: return@runCatching null
                val showing = dto.showing?.let(::toLeafOrNull) ?: return@runCatching null
                val absent = dto.absent?.let(::toLeafOrNull) ?: return@runCatching null
                TagAction.WhileNotificationShowing(pkg, channel, showing, absent)
            }
            else -> toLeafOrNull(dto)
        }
    }.getOrNull()

    /**
     * A leaf action, or null.
     *
     * Separate from [toActionOrNull] so the branches of a composite are constrained to leaves by the
     * return type. A document hand-edited to nest a composite inside a composite decodes to null and
     * loses one assignment, rather than producing a shape the domain forbids.
     */
    private fun toLeafOrNull(dto: ActionDto): TagAction.Leaf? = runCatching {
        when (dto.type) {
            TYPE_LAUNCH_APP -> dto.packageName?.let(TagAction::LaunchApp)
            TYPE_OPEN_URI -> dto.uri?.let(TagAction::OpenUri)
            TYPE_SEND_INTENT -> dto.intentAction?.let { action ->
                TagAction.SendIntent(action = action, uri = dto.uri, extras = dto.extras)
            }
            TYPE_MEDIA -> dto.mediaKey
                ?.let { name -> MediaKey.entries.firstOrNull { it.name == name } }
                ?.let(TagAction::MediaCommand)
            TYPE_WHATSAPP -> dto.packageName?.let { number ->
                TagAction.WhatsAppMessage(
                    phoneNumber = number,
                    message = dto.intentAction.orEmpty(),
                    autoSend = dto.autoSend,
                )
            }
            TYPE_TAP_NODE -> TagAction.TapNode(
                viewId = dto.packageName,
                contentDescription = dto.intentAction,
                requireForegroundPackage = dto.channelId,
            )
            TYPE_TOGGL -> dto.workspaceId?.let { workspace ->
                TagAction.TogglToggle(
                    workspaceId = workspace,
                    description = dto.intentAction.orEmpty(),
                    projectId = dto.projectId,
                )
            }
            TYPE_DRAG -> dto.drag?.let { drag ->
                TagAction.DragGesture(
                    startXRatio = drag.startXRatio,
                    startYRatio = drag.startYRatio,
                    endXRatio = drag.endXRatio,
                    endYRatio = drag.endYRatio,
                    holdMillis = drag.holdMillis,
                    travelMillis = drag.travelMillis,
                    steps = drag.steps,
                    requireForegroundPackage = drag.requireForegroundPackage,
                )
            }
            TYPE_STEPS -> dto.steps
                ?.map { child -> toLeafOrNull(child) ?: return@runCatching null }
                ?.let { children ->
                    TagAction.Steps(steps = children, gapMillis = dto.gapMillis ?: 0L)
                }
            // Written by a newer build. Skip this entry, keep the rest.
            else -> null
        }
    }.getOrNull()

    private const val TYPE_LAUNCH_APP = "launchApp"
    private const val TYPE_OPEN_URI = "openUri"
    private const val TYPE_SEND_INTENT = "sendIntent"
    private const val TYPE_MEDIA = "media"
    private const val TYPE_DRAG = "dragGesture"
    private const val TYPE_STEPS = "steps"
    private const val TYPE_TOGGL = "togglToggle"
    private const val TYPE_WHATSAPP = "whatsAppMessage"
    private const val TYPE_TAP_NODE = "tapNode"
    private const val TYPE_WHILE_NOTIFICATION = "whileNotificationShowing"

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
        @SerialName("updatedAtMillis") val updatedAtMillis: Long = 0,
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
        @SerialName("channelId") val channelId: String? = null,
        @SerialName("showing") val showing: ActionDto? = null,
        @SerialName("absent") val absent: ActionDto? = null,
        @SerialName("steps") val steps: List<ActionDto>? = null,
        @SerialName("gapMillis") val gapMillis: Long? = null,
        @SerialName("drag") val drag: DragDto? = null,
        @SerialName("workspaceId") val workspaceId: Long? = null,
        @SerialName("projectId") val projectId: Long? = null,
        @SerialName("autoSend") val autoSend: Boolean = false,
    )

    /** Gesture geometry, kept in its own object so [ActionDto] does not sprout eight more columns. */
    @Serializable
    private data class DragDto(
        @SerialName("startXRatio") val startXRatio: Float,
        @SerialName("startYRatio") val startYRatio: Float,
        @SerialName("endXRatio") val endXRatio: Float,
        @SerialName("endYRatio") val endYRatio: Float,
        @SerialName("holdMillis") val holdMillis: Long,
        @SerialName("travelMillis") val travelMillis: Long,
        @SerialName("steps") val steps: Int,
        @SerialName("requireForegroundPackage") val requireForegroundPackage: String? = null,
    )
}
