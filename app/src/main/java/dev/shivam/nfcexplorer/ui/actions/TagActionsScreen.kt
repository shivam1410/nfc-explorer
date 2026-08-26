package dev.shivam.nfcexplorer.ui.actions

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.shivam.nfcexplorer.R
import dev.shivam.nfcexplorer.domain.action.InstalledApp
import dev.shivam.nfcexplorer.domain.action.MediaKey
import dev.shivam.nfcexplorer.domain.action.TagAction
import dev.shivam.nfcexplorer.domain.action.TagAssignment
import dev.shivam.nfcexplorer.domain.model.ByteBlock
import dev.shivam.nfcexplorer.ui.component.SectionCard
import dev.shivam.nfcexplorer.ui.theme.HexTextStyle
import androidx.core.graphics.drawable.toBitmap

/**
 * Manage which tag does what.
 *
 * States the silent-on-unmapped behaviour explicitly, because a tap that deliberately does nothing is
 * indistinguishable from a broken app until you know that is the design.
 */
@Composable
fun TagActionsScreen(
    state: TagActionsUiState,
    onEdit: (TagAssignment) -> Unit,
    onDelete: (ByteBlock) -> Unit,
    onTest: (TagAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.assignments.isEmpty()) {
            Text(
                text = stringResource(R.string.actions_none_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.assignments.forEach { assignment ->
                AssignmentCard(
                    assignment = assignment,
                    appNameOf = state::labelFor,
                    onEdit = { onEdit(assignment) },
                    onDelete = { onDelete(assignment.uid) },
                    onTest = { onTest(assignment.action) },
                )
            }
        }
    }
}

@Composable
private fun AssignmentCard(
    assignment: TagAssignment,
    appNameOf: (String) -> String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    // Collapsed by default: the label and what it does are the whole story at a glance, and a list
    // of tags is for finding one, not for reading all of them at once.
    SectionCard(
        title = assignment.label,
        subtitle = summarise(assignment.action, appNameOf),
        icon = { ActionIcon(assignment.action) },
        initiallyExpanded = false,
    ) {
        Text(text = assignment.uid.toString(), style = HexTextStyle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest) { Text(stringResource(R.string.actions_test)) }
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.actions_edit)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.actions_delete)) }
        }
    }
}


/**
 * One tag, chosen from the workspace or typed.
 *
 * Editable rather than a fixed list: a read-only dropdown would quietly remove the ability to use a
 * tag Toggl has not seen yet, which is a capability the action otherwise has for free. Same shape as
 * the app picker, so the two selection surfaces in this editor behave alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TogglTagPicker(
    chosen: String,
    options: List<String>,
    onChoose: (String) -> Unit,
    onTyped: (String) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    val matches = remember(options, chosen) {
        options.filter { chosen.isBlank() || it.contains(chosen.trim(), ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = isOpen && matches.isNotEmpty(),
        onExpandedChange = { isOpen = it },
    ) {
        OutlinedTextField(
            value = chosen,
            onValueChange = {
                onTyped(it)
                isOpen = true
            },
            label = { Text(stringResource(R.string.actions_toggl_tags)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isOpen) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = isOpen && matches.isNotEmpty(),
            onDismissRequest = { isOpen = false },
        ) {
            matches.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onChoose(name)
                        isOpen = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun DraftEditor(
    state: TagActionsUiState,
    draft: ActionDraft,
    onDraftChange: (ActionDraft) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onTestDraft: () -> Unit,
    onAppQueryChange: (String) -> Unit,
    onPickApp: (InstalledApp) -> Unit,
    onTypeChange: (ActionType) -> Unit,
    onSchemeChange: (String) -> Unit,
    onSelectTogglTag: (String) -> Unit,
) {
    SectionCard(
        title = stringResource(
            if (draft.isExisting) R.string.actions_edit_title else R.string.actions_new_title,
        ),
        subtitle = draft.uid?.toString(),
        // A form you can collapse is a form that can hide the field you are filling in.
        collapsible = false,
    ) {
        OutlinedTextField(
            value = draft.label,
            onValueChange = { onDraftChange(draft.copy(label = it)) },
            label = { Text(stringResource(R.string.actions_label)) },
            isError = draft.touched && state.problem == DraftProblem.BLANK_LABEL,
            modifier = Modifier.fillMaxWidth(),
        )

        // Measured width rather than weight(1f). A weight divides the row it lands in, so the last
        // row -- holding one tile -- gave that tile the full width, and a square aspect ratio then
        // made it as tall as the screen is wide. A fixed third keeps every tile the same size
        // however many share a row.
        BoxWithConstraints(modifier = Modifier.padding(top = 8.dp)) {
            val tileWidth = (maxWidth - ACTION_TILE_GAP * (ACTIONS_PER_ROW - 1)) / ACTIONS_PER_ROW
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ACTION_TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(ACTION_TILE_GAP),
                maxItemsInEachRow = ACTIONS_PER_ROW,
            ) {
                ACTION_ORDER.forEach { type ->
                    ActionTypeTile(
                        type = type,
                        selected = draft.type == type,
                        onClick = { onTypeChange(type) },
                        modifier = Modifier.width(tileWidth),
                    )
                }
            }
        }

        when (draft.type) {
            ActionType.LAUNCH_APP -> AppPicker(
                state = state,
                chosen = draft.packageName,
                onQueryChange = onAppQueryChange,
                onPick = onPickApp,
            )

            ActionType.OPEN_URI -> {
                // Offered rather than typed. https is the default and http is one tap away, which is
                // the difference between a wa.me link that carries its ?text= payload and one that
                // loses it to a redirect.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LINK_SCHEMES.forEach { scheme ->
                        FilterChip(
                            selected = draft.uri.startsWith(scheme),
                            onClick = { onSchemeChange(scheme) },
                            label = { Text(scheme) },
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.uri,
                    onValueChange = { onDraftChange(draft.copy(uri = it)) },
                    label = { Text(stringResource(R.string.actions_uri)) },
                    isError = draft.touched && (
                        state.problem == DraftProblem.MISSING_TARGET ||
                            state.problem == DraftProblem.INVALID_URI
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ActionType.SEND_INTENT -> {
                OutlinedTextField(
                    value = draft.intentAction,
                    onValueChange = { onDraftChange(draft.copy(intentAction = it)) },
                    label = { Text(stringResource(R.string.actions_intent_action)) },
                    isError = draft.touched && state.problem == DraftProblem.MISSING_TARGET,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.uri,
                    onValueChange = { onDraftChange(draft.copy(uri = it)) },
                    label = { Text(stringResource(R.string.actions_uri_optional)) },
                    isError = draft.touched && state.problem == DraftProblem.INVALID_URI,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ActionType.MEDIA -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaKey.entries.forEach { key ->
                    FilterChip(
                        selected = draft.mediaKey == key,
                        onClick = { onDraftChange(draft.copy(mediaKey = key)) },
                        label = { Text(stringResource(key.labelRes())) },
                    )
                }
            }

            ActionType.WHATSAPP -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val context = LocalContext.current
                // ACTION_PICK on the phone table, not PickContact: the result URI points straight at
                // the chosen number and carries read permission for that one row, so this needs no
                // READ_CONTACTS grant at all. Asking for the whole address book to read one number
                // would be wildly disproportionate.
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    result.data?.data?.let { uri ->
                        readPhoneNumber(context, uri)?.let { number ->
                            onDraftChange(draft.copy(phoneNumber = number))
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.phoneNumber,
                    onValueChange = { onDraftChange(draft.copy(phoneNumber = it)) },
                    label = { Text(stringResource(R.string.actions_whatsapp_number)) },
                    isError = draft.touched && state.problem == DraftProblem.MISSING_TARGET,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                        )
                    },
                ) { Text(stringResource(R.string.actions_whatsapp_pick)) }

                OutlinedTextField(
                    value = draft.messageText,
                    onValueChange = { onDraftChange(draft.copy(messageText = it)) },
                    label = { Text(stringResource(R.string.actions_whatsapp_message)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.actions_whatsapp_autosend),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draft.autoSend,
                        onCheckedChange = { onDraftChange(draft.copy(autoSend = it)) },
                    )
                }
                Text(
                    text = stringResource(
                        if (draft.autoSend) {
                            R.string.actions_whatsapp_autosend_warning
                        } else {
                            R.string.actions_whatsapp_explainer
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (draft.autoSend) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            ActionType.TOGGL -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = draft.togglDescription,
                    onValueChange = { onDraftChange(draft.copy(togglDescription = it)) },
                    label = { Text(stringResource(R.string.actions_toggl_description)) },
                    placeholder = { Text(draft.label.ifBlank { "" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TogglTagPicker(
                    chosen = draft.togglTag,
                    options = state.togglTagOptions,
                    onChoose = onSelectTogglTag,
                    onTyped = { onDraftChange(draft.copy(togglTag = it)) },
                )
                Text(
                    text = stringResource(R.string.actions_toggl_explainer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // No form: the preset has nothing to configure. The permissions it needs live in
            // Settings rather than being restated on every editor that happens to select it.
            ActionType.SLEEP_CYCLE -> Text(
                text = stringResource(R.string.actions_sleep_cycle_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        state.problem?.takeIf { draft.touched }?.let { problem ->
            Text(
                text = stringResource(problem.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(onClick = onSave, enabled = state.canSave) {
                Text(stringResource(R.string.actions_save))
            }
            // Try it before committing: enabled on the same condition as save, since a testable
            // draft and a saveable one are the same thing.
            OutlinedButton(onClick = onTestDraft, enabled = state.canSave) {
                Text(stringResource(R.string.actions_test))
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.actions_cancel)) }
        }
    }
}

/**
 * Choose an installed app instead of typing its package name.
 *
 * The editor originally took the package as free text, which meant knowing that YouTube Music is
 * `com.google.android.apps.youtube.music` — it mirrored the shape of the domain type rather than
 * anything a person knows. Picking from the launchable apps also makes an unlaunchable target
 * unreachable rather than merely discouraged.
 *
 * A floating menu rather than an inline list, which the first version was. Inline, it could not scroll
 * (a lazy list has unbounded height inside the screen's own `verticalScroll`, so it had to be a capped
 * `Column`), it pushed the Save button off the screen, and it sat there open before anyone asked for it.
 * A menu solves all three at once: it scrolls, it costs no layout space because it floats, and it
 * appears on a tap.
 *
 * Rows show an icon and a name and nothing else. The package name is what gets *stored*, not what
 * anyone wants to read down a list of forty apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(
    state: TagActionsUiState,
    chosen: String,
    onQueryChange: (String) -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    var isOpen by remember { mutableStateOf(false) }
    val matches = state.visibleApps

    ExposedDropdownMenuBox(
        expanded = isOpen && matches.isNotEmpty(),
        onExpandedChange = { isOpen = it },
    ) {
        OutlinedTextField(
            value = state.appQuery,
            onValueChange = {
                onQueryChange(it)
                // Typing means looking for something, so the list should already be open.
                isOpen = true
            },
            label = { Text(stringResource(R.string.actions_app_search)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isOpen) },
            supportingText = {
                Text(
                    text = if (chosen.isBlank()) {
                        stringResource(R.string.actions_app_none_chosen)
                    } else {
                        stringResource(R.string.actions_app_chosen, state.labelFor(chosen))
                    },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable),
        )

        ExposedDropdownMenu(
            expanded = isOpen && matches.isNotEmpty(),
            onDismissRequest = { isOpen = false },
        ) {
            // Only a window of the matches is built.
            //
            // ExposedDropdownMenu is not lazy, so every item it is given is composed immediately --
            // and each one rasterises an app icon on the main thread. On a phone with hundreds of
            // launchable apps that is hundreds of PackageManager loads before the menu can draw,
            // which is exactly the stall this had. Capping it makes opening the menu constant-time
            // regardless of how many apps are installed, and the search field is how the rest are
            // reached.
            matches.take(APP_MENU_LIMIT).forEach { app ->
                DropdownMenuItem(
                    text = { Text(app.label) },
                    leadingIcon = { AppIcon(app.packageName) },
                    onClick = {
                        onPick(app)
                        isOpen = false
                    },
                )
            }

            // Says the rest exist rather than pretending the list is complete. A silently truncated
            // picker reads as "that app is not installed".
            if (matches.size > APP_MENU_LIMIT) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                R.string.actions_app_more,
                                matches.size - APP_MENU_LIMIT,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }

    if (state.apps.isEmpty()) {
        Text(
            text = stringResource(R.string.actions_app_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The app's launcher icon, or nothing.
 *
 * Loaded in composition and remembered per package: only the rows the menu actually shows pay for it,
 * and each pays once. An app whose icon cannot be loaded renders empty space rather than a placeholder
 * that would read as a real, blank-looking app.
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon: ImageBitmap? = remember(packageName) {
        runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(width = ICON_PIXELS, height = ICON_PIXELS)
                .asImageBitmap()
        }.getOrNull()
    }

    Box(modifier = Modifier.size(ICON_SIZE), contentAlignment = Alignment.Center) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        }
    }
}

/** Rendered at [ICON_SIZE]; rasterised a little larger so it stays sharp. */
private const val ICON_PIXELS = 96

private val ICON_SIZE = 28.dp

private val ACTION_ICON_SIZE = 18.dp

/**
 * How many apps the picker builds at once.
 *
 * Chosen for composition cost, not for taste: each row loads and rasterises an icon synchronously,
 * so this is the number of icon decodes the menu pays for on open.
 */
private const val APP_MENU_LIMIT = 30

/**
 * One line describing what a tag will do.
 *
 * [appNameOf] resolves a package to its app name, because the package is what gets *stored* and not
 * what belongs on a card the user reads. It stays a lookup rather than a stored field so an app that
 * gets renamed or uninstalled is described by what is true now.
 */
@Composable
private fun summarise(action: TagAction, appNameOf: (String) -> String): String = when (action) {
    is TagAction.LaunchApp ->
        stringResource(R.string.actions_summary_launch, appNameOf(action.packageName))
    is TagAction.OpenUri -> stringResource(R.string.actions_summary_uri, action.uri)
    is TagAction.SendIntent -> stringResource(R.string.actions_summary_intent, action.action)
    is TagAction.MediaCommand ->
        stringResource(R.string.actions_summary_media, stringResource(action.key.labelRes()))
    is TagAction.WhatsAppMessage ->
        stringResource(R.string.actions_summary_whatsapp, action.phoneNumber)
    is TagAction.TogglToggle -> stringResource(R.string.actions_summary_toggl, action.description)
    is TagAction.DragGesture -> stringResource(R.string.actions_summary_gesture)
    is TagAction.TapNode -> stringResource(R.string.actions_summary_tap)
    is TagAction.Steps -> stringResource(R.string.actions_summary_steps, action.steps.size)
    // Named by the app it watches rather than by the mechanism: "Sleep Cycle - start or end" says
    // what the tag does, where "toggle on a notification channel" says how it is implemented.
    is TagAction.WhileNotificationShowing ->
        stringResource(R.string.actions_summary_toggle, appNameOf(action.packageName))
}


/**
 * The number behind a contact-picker result, or null.
 *
 * Read through the returned URI rather than by querying the contacts provider directly: that URI
 * carries a one-row read grant, which is why this works without READ_CONTACTS. Failures return null
 * — a picker that yielded nothing usable should leave the field alone, not crash the editor.
 */
private fun readPhoneNumber(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()


/**
 * One action choice: a square tile with the mark above its name.
 *
 * Square because three fit a phone's width only if they stop competing for it horizontally -- an
 * icon-then-label row at a third of the screen truncates "Launch app". Stacking gives the label the
 * full tile width and makes the marks, which are the fastest thing to recognise, the dominant part.
 */
@Composable
private fun ActionTypeTile(
    type: ActionType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(ACTION_TILE_CORNER))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
            )
            .border(1.dp, border, RoundedCornerShape(ACTION_TILE_CORNER))
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(type.iconRes()),
            contentDescription = null,
            modifier = Modifier.size(ACTION_TILE_ICON),
            tint = type.brandTint() ?: MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(type.labelRes()),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The order the tiles appear in, which is not the order the enum happens to declare.
 *
 * Grouped by what they are: the three integrations first, since those are the ones reached by name,
 * then the three generic escape hatches, then media.
 */
private val ACTION_ORDER = listOf(
    ActionType.TOGGL,
    ActionType.WHATSAPP,
    ActionType.SLEEP_CYCLE,
    ActionType.LAUNCH_APP,
    ActionType.OPEN_URI,
    ActionType.SEND_INTENT,
    ActionType.MEDIA,
)

private const val ACTIONS_PER_ROW = 3
private val ACTION_TILE_GAP = 8.dp
private val ACTION_TILE_CORNER = 14.dp
private val ACTION_TILE_ICON = 26.dp

/**
 * The icon for a configured action, so a list of tags is scannable by shape as well as by reading.
 *
 * A launch action shows the *app's own* icon rather than a generic glyph: three tags that all launch
 * something are told apart by which app, and that is exactly what the icon can say. Everything else
 * falls back to the vector for its kind.
 *
 * The pieces a preset is built from -- a drag, a tap, a sequence -- share one icon: they are never
 * configured on their own, so distinguishing them would be detail nobody chose.
 */
@Composable
private fun ActionIcon(action: TagAction) {
    if (action is TagAction.LaunchApp) {
        AppIcon(action.packageName)
        return
    }
    val iconRes = when (action) {
        is TagAction.OpenUri -> R.drawable.ic_action_link
        is TagAction.SendIntent -> R.drawable.ic_action_intent
        is TagAction.MediaCommand -> R.drawable.ic_action_media
        is TagAction.WhatsAppMessage -> R.drawable.ic_action_whatsapp
        is TagAction.TogglToggle -> R.drawable.ic_action_toggl
        is TagAction.WhileNotificationShowing -> R.drawable.ic_action_sleep
        is TagAction.LaunchApp,
        is TagAction.DragGesture,
        is TagAction.TapNode,
        is TagAction.Steps,
        -> R.drawable.ic_nav_actions
    }
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(ICON_SIZE),
        tint = action.brandTint() ?: MaterialTheme.colorScheme.primary,
    )
}

/**
 * The colour a service's own mark should be drawn in, or null to follow the theme.
 *
 * Only for marks that belong to someone else. Tinting them with the app's primary made the Toggl
 * ring teal, which is a different logo.
 */
@Composable
private fun TagAction.brandTint(): Color? = when (this) {
    is TagAction.TogglToggle -> colorResource(R.color.brand_toggl)
    is TagAction.WhatsAppMessage -> colorResource(R.color.brand_whatsapp)
    else -> null
}

@Composable
private fun ActionType.brandTint(): Color? = when (this) {
    ActionType.TOGGL -> colorResource(R.color.brand_toggl)
    ActionType.WHATSAPP -> colorResource(R.color.brand_whatsapp)
    else -> null
}

/** Screen-reader users get the label; the icon is decoration, so it carries no description. */
internal fun ActionType.iconRes(): Int = when (this) {
    ActionType.LAUNCH_APP -> R.drawable.ic_action_app
    ActionType.OPEN_URI -> R.drawable.ic_action_link
    ActionType.SEND_INTENT -> R.drawable.ic_action_intent
    ActionType.MEDIA -> R.drawable.ic_action_media
    ActionType.SLEEP_CYCLE -> R.drawable.ic_action_sleep
    ActionType.TOGGL -> R.drawable.ic_action_toggl
    ActionType.WHATSAPP -> R.drawable.ic_action_whatsapp
}

internal fun ActionType.labelRes(): Int = when (this) {
    ActionType.LAUNCH_APP -> R.string.actions_type_launch
    ActionType.OPEN_URI -> R.string.actions_type_uri
    ActionType.SEND_INTENT -> R.string.actions_type_intent
    ActionType.MEDIA -> R.string.actions_type_media
    ActionType.SLEEP_CYCLE -> R.string.actions_type_sleep_cycle
    ActionType.TOGGL -> R.string.actions_type_toggl
    ActionType.WHATSAPP -> R.string.actions_type_whatsapp
}

private fun MediaKey.labelRes(): Int = when (this) {
    MediaKey.PLAY_PAUSE -> R.string.actions_media_play_pause
    MediaKey.NEXT -> R.string.actions_media_next
    MediaKey.PREVIOUS -> R.string.actions_media_previous
}

private fun DraftProblem.labelRes(): Int = when (this) {
    DraftProblem.NO_TAG -> R.string.actions_problem_no_tag
    DraftProblem.BLANK_LABEL -> R.string.actions_problem_blank_label
    DraftProblem.MISSING_TARGET -> R.string.actions_problem_missing_target
    DraftProblem.INVALID_URI -> R.string.actions_problem_invalid_uri
}
