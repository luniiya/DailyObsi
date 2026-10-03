package dev.ayaya.dailyobsi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.zIndex
import dev.ayaya.dailyobsi.todo.inOrder
import dev.ayaya.dailyobsi.todo.todoBlock
import dev.ayaya.dailyobsi.todo.todoNeighbor
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.ayaya.dailyobsi.todo.SeparatorKind
import dev.ayaya.dailyobsi.todo.TodoItem
import dev.ayaya.dailyobsi.todo.TodoSource
import dev.ayaya.dailyobsi.todo.canAddSubtask
import dev.ayaya.dailyobsi.todo.canDelete
import dev.ayaya.dailyobsi.todo.canRename
import dev.ayaya.dailyobsi.todo.reorderedIds
import dev.ayaya.dailyobsi.todo.separatorKind
import dev.ayaya.dailyobsi.todo.todoCheckable
import dev.ayaya.dailyobsi.todo.todoDepths
import dev.ayaya.dailyobsi.todo.todoSourceLabel
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/** A line being typed in place: a new item below/under [targetId], or a rename of it. */
private data class Draft(val kind: Kind, val targetId: Long, val fallbackId: Long = targetId) {
    enum class Kind { AFTER, SUBTASK, RENAME }
}

/** The Nextcloud Daily Todo tab: the server's list for the open note's date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTab(
    state: TodoUiState,
    model: TodoViewModel,
    onAtTopChanged: (Boolean) -> Unit,
) {
    val day = state.day
    var draft by remember(state.requestedDate) { mutableStateOf<Draft?>(null) }
    val listState = rememberLazyListState()
    val latestOnAtTopChanged by rememberUpdatedState(onAtTopChanged)
    val latestItems by rememberUpdatedState(day?.items.orEmpty())
    val haptics = LocalHapticFeedback.current
    val fallbackRowPx = with(LocalDensity.current) { 56.dp.toPx() }
    val edgePx = with(LocalDensity.current) { 72.dp.toPx() }

    // Long-press drag: [preview] is the order on screen while dragging (nothing is saved
    // until the finger lifts), [dragOffset] how far the dragged block sits from its slot.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var preview by remember { mutableStateOf<List<TodoItem>?>(null) }

    /** Swap the dragged block past a sibling once it's dragged over half of it. */
    fun settle() {
        val id = dragId ?: return
        var current = preview ?: return
        val sizes = listState.layoutInfo.visibleItemsInfo.associate { it.key to it.size }
        while (dragOffset != 0f) {
            val dir = if (dragOffset > 0) 1 else -1
            val neighbor = todoNeighbor(current, id, dir) ?: break
            val height = todoBlock(current, neighbor).sumOf { (sizes[it]?.toFloat() ?: fallbackRowPx).toDouble() }.toFloat()
            if (abs(dragOffset) < height / 2) break
            current = inOrder(current, reorderedIds(current, id, dir) ?: break)
            dragOffset -= dir * height
        }
        preview = current
    }

    fun endDrag() {
        val order = preview?.map { it.id }
        dragId = null
        dragOffset = 0f
        model.setDragging(false)
        // The optimistic reorder lands before the preview goes, so nothing jumps back.
        order?.let(model::reorder)
        preview = null
    }

    // Near the top/bottom edge, scroll the list under the dragged row.
    LaunchedEffect(dragId) {
        val id = dragId ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val top = row.offset + dragOffset
            val step = when {
                top < info.viewportStartOffset + edgePx -> -12f
                top + row.size > info.viewportEndOffset - edgePx -> 12f
                else -> 0f
            }
            if (step != 0f) {
                val moved = listState.scrollBy(step)
                dragOffset += moved
                settle()
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { latestOnAtTopChanged(it) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        state.message?.let { message ->
            Banner(message, MaterialTheme.colorScheme.error) {
                TextButton(onClick = model::clearMessage) { Text("Dismiss") }
            }
        }
        if (state.offline) {
            val since = state.cachedAt?.let { " · list from ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}" }
            Banner(
                text = if (day != null) "Offline$since. Read-only until Nextcloud is back." else "Can't reach Nextcloud.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = { Icon(Icons.Filled.CloudOff, contentDescription = null) },
            ) { TextButton(onClick = model::refresh) { Text("Retry") } }
        } else if (day != null && !day.editable) {
            Banner(
                if (day.future) "This day hasn't started yet." else "This day is read-only.",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = model::pullToRefresh,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            when {
                day == null && state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                day == null -> Unit
                else -> {
                    val items = preview ?: day.items
                    val depths = remember(items) { todoDepths(items) }
                    val dragBlock = dragId?.let { todoBlock(items, it) }.orEmpty()
                    val checkable = todoCheckable(items)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 4.dp,
                            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
                        ),
                    ) {
                        item(key = "header") {
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                                val done = checkable.count { it.completed }
                                Text(
                                    "$done of ${checkable.size} done",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (checkable.isEmpty()) 0f else done / checkable.size.toFloat() },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (state.canEdit) {
                                    Spacer(Modifier.height(10.dp))
                                    AddBox(onAdd = model::add)
                                }
                            }
                        }
                        if (items.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    when {
                                        day.editable -> "Nothing planned yet."
                                        day.future -> "A day is created when it starts."
                                        else -> "Nothing was recorded on this day."
                                    },
                                    Modifier.fillMaxWidth().padding(24.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(items, key = { it.id }) { item ->
                            val depth = depths[item.id] ?: 0
                            // A just-added item isn't in the list until the reload lands; keep
                            // the line under the previous one meanwhile.
                            val rowDraft = draft?.takeIf {
                                it.targetId == item.id ||
                                    (it.fallbackId == item.id && items.none { other -> other.id == it.targetId })
                            }
                            val lifted = item.id in dragBlock
                            Column(
                                (if (lifted) {
                                    Modifier.zIndex(1f).graphicsLayer {
                                        translationY = dragOffset
                                        shadowElevation = 8.dp.toPx()
                                        shape = RoundedCornerShape(12.dp)
                                        clip = true
                                    }.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                } else {
                                    Modifier.animateItem()
                                }).pointerInput(item.id, state.canEdit) {
                                    if (!state.canEdit) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            draft = null
                                            preview = latestItems
                                            dragOffset = 0f
                                            dragId = item.id
                                            model.setDragging(true)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            settle()
                                        },
                                        onDragEnd = ::endDrag,
                                        onDragCancel = ::endDrag,
                                    )
                                },
                            ) {
                                TodoRow(
                                    item = item,
                                    depth = depth,
                                    subtitle = subtitle(item, items),
                                    canEdit = state.canEdit,
                                    renaming = rowDraft?.kind == Draft.Kind.RENAME,
                                    canMoveUp = reorderedIds(items, item.id, -1) != null,
                                    canMoveDown = reorderedIds(items, item.id, 1) != null,
                                    onToggle = { model.toggle(item) },
                                    onStartRename = { draft = Draft(Draft.Kind.RENAME, item.id) },
                                    onRename = { title, continueBelow ->
                                        model.rename(item, title)
                                        draft = if (continueBelow) Draft(Draft.Kind.AFTER, item.id) else null
                                    },
                                    onCancel = { draft = null },
                                    onAddSubtask = { draft = Draft(Draft.Kind.SUBTASK, item.id) },
                                    onMove = { delta -> model.move(item, delta) },
                                    onDelete = { model.delete(item) },
                                )
                                if (rowDraft != null && rowDraft.kind != Draft.Kind.RENAME) {
                                    val sub = rowDraft.kind == Draft.Kind.SUBTASK
                                    DraftLine(
                                        depth = if (sub) depth + 1 else depth,
                                        onSubmit = { title ->
                                            // Keep going: the next line opens below the one just added.
                                            val next = { id: Long -> draft = Draft(Draft.Kind.AFTER, id, fallbackId = item.id) }
                                            when {
                                                title.isBlank() -> draft = null
                                                sub -> model.addSubtask(item, title.trim(), next)
                                                else -> model.addAfter(item, title.trim(), next)
                                            }
                                        },
                                        onCancel = { draft = null },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: Color,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.invoke()
        Text(text, Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodyMedium)
        action?.invoke()
    }
}

/** "Add a task": a soft rounded pill (no outline) that lights up in the accent
 *  colour when focused. Enter adds and the box stays focused for the next one. */
@Composable
private fun AddBox(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    fun submit() {
        val title = text.trim()
        if (title.isNotEmpty()) onAdd(title)
        text = ""
    }
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = text,
        onValueChange = { text = it.replace("\n", "") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .clip(shape)
                    .background(if (focused) colors.primaryContainer.copy(alpha = 0.35f) else colors.surfaceContainerHigh)
                    .border(if (focused) 1.5.dp else 0.dp, if (focused) colors.primary else Color.Transparent, shape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = if (focused) colors.primary else colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text("Add a task", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    }
                    field()
                }
                if (text.isNotBlank()) {
                    FilledTonalIconButton(onClick = ::submit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Add")
                    }
                }
            }
        },
    )
}

@Composable
private fun TodoRow(
    item: TodoItem,
    depth: Int,
    subtitle: String,
    canEdit: Boolean,
    renaming: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (title: String, continueBelow: Boolean) -> Unit,
    onCancel: () -> Unit,
    onAddSubtask: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val separator = separatorKind(item.title)
    if (separator != null) {
        // Dividers and gaps span the whole row; a quick (yours) one gets its menu on top
        // at the end, everything else needs no slot for it.
        Box(
            Modifier.fillMaxWidth().padding(start = INDENT * depth).height(if (canEdit && canDelete(item)) 48.dp else 24.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (separator == SeparatorKind.RULE) {
                HorizontalDivider(Modifier.fillMaxWidth().padding(horizontal = 12.dp).align(Alignment.Center))
            }
            if (canEdit && canDelete(item)) {
                Box(Modifier.background(MaterialTheme.colorScheme.surface, CircleShape)) {
                    RowMenu(
                        item = item,
                        canMoveUp = canMoveUp,
                        canMoveDown = canMoveDown,
                        onRename = onStartRename,
                        onAddSubtask = onAddSubtask,
                        onMove = onMove,
                        onDelete = onDelete,
                    )
                }
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = INDENT * depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.completed, onCheckedChange = { onToggle() }, enabled = canEdit)
        if (renaming) {
            InlineField(
                initial = item.title,
                modifier = Modifier.weight(1f),
                onSubmit = { onRename(it, true) },
                onCancel = onCancel,
            )
        } else {
            Column(
                Modifier.weight(1f)
                    .clickable(enabled = canEdit && canRename(item), onClick = onStartRename)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                    color = if (item.completed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (canEdit && !renaming) {
            RowMenu(
                item = item,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onRename = onStartRename,
                onAddSubtask = onAddSubtask,
                onMove = onMove,
                onDelete = onDelete,
            )
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun RowMenu(
    item: TodoItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onAddSubtask: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${item.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canRename(item)) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { open = false; onRename() },
                )
            }
            if (canAddSubtask(item)) {
                DropdownMenuItem(
                    text = { Text("Add subtask") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                    onClick = { open = false; onAddSubtask() },
                )
            }
            DropdownMenuItem(
                text = { Text("Move up") },
                leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                enabled = canMoveUp,
                onClick = { open = false; onMove(-1) },
            )
            DropdownMenuItem(
                text = { Text("Move down") },
                leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                enabled = canMoveDown,
                onClick = { open = false; onMove(1) },
            )
            if (canDelete(item)) {
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { open = false; onDelete() },
                )
            }
        }
    }
}

/** The line that opens below an item: Enter adds and keeps a fresh line open, Enter on empty closes it. */
@Composable
private fun DraftLine(depth: Int, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = INDENT * depth + 48.dp, end = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InlineField(
            initial = "",
            placeholder = "New item (Enter to add, empty to stop)",
            modifier = Modifier.weight(1f),
            clearOnSubmit = true,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun InlineField(
    initial: String,
    modifier: Modifier,
    placeholder: String? = null,
    clearOnSubmit: Boolean = false,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember(initial) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = value,
        onValueChange = { value = it.copy(text = it.text.replace("\n", "")) },
        modifier = modifier.focusRequester(focus),
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onSubmit(value.text)
            if (clearOnSubmit) value = TextFieldValue("")
        }),
        trailingIcon = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

private val INDENT = 28.dp

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

/** "Task · 14:00 · Due … · 1/3 subtasks", like the web UI's subname. */
private fun subtitle(item: TodoItem, items: List<TodoItem>): String {
    if (separatorKind(item.title) != null) return ""
    val zone = ZoneId.systemDefault()
    val children = if (item.parentId == null) items.filter { it.parentId == item.id } else emptyList()
    return listOfNotNull(
        todoSourceLabel(item.source).takeIf { item.source != TodoSource.ROUTINE },
        when {
            item.allDay -> "All day"
            item.startsAt != null -> timeFormat.format(Instant.ofEpochSecond(item.startsAt).atZone(zone))
            else -> null
        },
        item.dueAt?.let { "Due " + dateTimeFormat.format(Instant.ofEpochSecond(it).atZone(zone)) },
        children.takeIf { it.isNotEmpty() }?.let { "${it.count { c -> c.completed }}/${it.size} subtasks" },
    ).joinToString(" · ")
}
