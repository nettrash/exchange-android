/*
 * HomeScreen.kt
 * Exchange (Android)
 *
 * Top-level home screen. Shows the identity (public bundle + fingerprint
 * + Show QR button) and the recipient list. The TopAppBar overflow has
 * Settings; the trailing icons drive Compose / Decrypt / AddRecipient
 * (matching the iOS toolbar topology).
 *
 * Recipient management (v1.2): rows can be renamed (tap → dialog) and
 * reordered (long-press → drag). The new order is persisted to
 * order_index and propagates to the user's other devices through the
 * encrypted backup. Drag-reorder is implemented locally (DragDropState
 * below) so we don't take a third-party dependency.
 */

package me.nettrash.exchange.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import me.nettrash.exchange.crypto.Identity
import me.nettrash.exchange.crypto.toGroupedHex
import me.nettrash.exchange.data.Recipient
import me.nettrash.exchange.ui.ExchangeViewModel

/** Number of non-draggable items the LazyColumn renders before recipients. */
private const val HEADER_ITEM_COUNT = 3 // "My identity" label, identity card, "Recipients" label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: ExchangeViewModel,
    onCompose: () -> Unit,
    onDecrypt: () -> Unit,
    onAddRecipient: () -> Unit,
    onShowMyQr: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val identityState by viewModel.identityState.collectAsState()
    val identity =
        (identityState as? ExchangeViewModel.IdentityState.Loaded)?.identity ?: return
    val recipients by viewModel.recipientsByManualOrder.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Local, drag-mutable copy of the list. While a drag is in progress we
    // reorder this for live visual feedback and only write to the DB when
    // the finger lifts. When the DB flow emits (and we're not mid-drag) we
    // resync from it.
    var orderedRecipients by remember { mutableStateOf(recipients) }
    LaunchedEffect(recipients) { orderedRecipients = recipients }

    // The recipient currently being renamed, if any.
    var renaming by remember { mutableStateOf<Recipient?>(null) }

    val listState = rememberLazyListState()
    val dragDropState = rememberDragDropState(
        lazyListState = listState,
        recipientCount = orderedRecipients.size,
        onMove = { fromRecipientIdx, toRecipientIdx ->
            orderedRecipients = orderedRecipients.toMutableList().apply {
                add(toRecipientIdx, removeAt(fromRecipientIdx))
            }
        },
        onDrop = {
            coroutineScope.launch {
                viewModel.reorderRecipients(orderedRecipients.map { it.id })
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exchange") },
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                actions = {
                    IconButton(onClick = onDecrypt) {
                        Icon(Icons.Default.LockOpen, contentDescription = "Decrypt")
                    }
                    IconButton(onClick = onCompose) {
                        Icon(Icons.Default.Edit, contentDescription = "Compose")
                    }
                    IconButton(onClick = onAddRecipient) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add recipient")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .dragContainer(dragDropState),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("My identity") }
            item { IdentityCard(identity = identity, onShowQr = onShowMyQr) }

            item { SectionLabel("Recipients") }
            if (orderedRecipients.isEmpty()) {
                item {
                    Text(
                        "No recipients yet. Add someone's public key with the + button.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            } else {
                itemsIndexed(
                    items = orderedRecipients,
                    key = { _, r -> r.id },
                ) { index, r ->
                    val absoluteIndex = HEADER_ITEM_COUNT + index
                    val dragging = absoluteIndex == dragDropState.draggingItemIndex
                    val rowModifier = if (dragging) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer { translationY = dragDropState.draggingItemOffset }
                    } else {
                        Modifier
                    }
                    RecipientRow(
                        recipient = r,
                        dragging = dragging,
                        onTap = { renaming = r },
                        onDelete = {
                            coroutineScope.launch { viewModel.deleteRecipient(r) }
                        },
                        modifier = rowModifier,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    renaming?.let { target ->
        RenameRecipientDialog(
            recipient = target,
            onDismiss = { renaming = null },
            onSave = { newName, newNotes ->
                coroutineScope.launch {
                    viewModel.renameRecipient(target, newName, newNotes)
                }
                renaming = null
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
    )
}

@Composable
private fun IdentityCard(identity: Identity, onShowQr: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Public key",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = Identity.encode(identity.publicBundle),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Fingerprint: ${identity.fingerprint.toGroupedHex()}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowQr)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Show QR code", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun RecipientRow(
    recipient: Recipient,
    dragging: Boolean,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(12.dp),
        colors = if (dragging) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    recipient.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    recipient.fingerprintDisplay,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete recipient",
                )
            }
        }
    }
}

@Composable
private fun RenameRecipientDialog(
    recipient: Recipient,
    onDismiss: () -> Unit,
    onSave: (displayName: String, notes: String) -> Unit,
) {
    var name by remember(recipient.id) { mutableStateOf(recipient.displayName) }
    var notes by remember(recipient.id) { mutableStateOf(recipient.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit recipient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Identity: ${recipient.fingerprintDisplay}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The recipient's key can't be changed here. To point this row at a different key, delete it and add the new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, notes) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---- Drag-to-reorder ----------------------------------------------------
//
// A compact, dependency-free reorderable-list helper. It tracks which
// LazyColumn item is being dragged, the live pixel offset to render it at,
// and fires `onMove` (in recipient-index space) as the dragged row crosses
// its neighbours. `onDrop` runs when the finger lifts so the caller can
// persist the final order.

@Composable
private fun rememberDragDropState(
    lazyListState: LazyListState,
    recipientCount: Int,
    onMove: (fromRecipientIdx: Int, toRecipientIdx: Int) -> Unit,
    onDrop: () -> Unit,
): DragDropState {
    val state = remember(lazyListState) { DragDropState(lazyListState) }
    // Keep the mutable callbacks/counts fresh across recompositions.
    state.recipientCount = recipientCount
    state.onMove = onMove
    state.onDrop = onDrop
    return state
}

private class DragDropState(private val lazyListState: LazyListState) {
    var recipientCount: Int = 0
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var onDrop: () -> Unit = {}

    /** Absolute LazyColumn index of the row being dragged, or null. */
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    private var draggedDistance by mutableStateOf(0f)
    private var initialOffset by mutableStateOf(0)

    private val firstRecipientIndex get() = HEADER_ITEM_COUNT
    private val lastRecipientIndex get() = HEADER_ITEM_COUNT + recipientCount - 1

    private val draggingLayoutInfo
        get() = lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == draggingItemIndex }

    /** Pixel translation to apply to the dragged row. */
    val draggingItemOffset: Float
        get() = draggingLayoutInfo?.let { initialOffset + draggedDistance - it.offset } ?: 0f

    fun onDragStart(offset: Offset) {
        lazyListState.layoutInfo.visibleItemsInfo
            .firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) }
            ?.takeIf { it.index in firstRecipientIndex..lastRecipientIndex }
            ?.also {
                draggingItemIndex = it.index
                initialOffset = it.offset
                draggedDistance = 0f
            }
    }

    fun onDrag(offset: Offset) {
        draggedDistance += offset.y
        val dragging = draggingLayoutInfo ?: return
        val startOffset = dragging.offset + draggingItemOffset
        val middle = startOffset + dragging.size / 2f
        val target = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index != dragging.index &&
                item.index in firstRecipientIndex..lastRecipientIndex &&
                middle.toInt() in item.offset..(item.offset + item.size)
        } ?: return
        onMove(dragging.index - firstRecipientIndex, target.index - firstRecipientIndex)
        draggingItemIndex = target.index
    }

    fun onDragInterrupted() {
        if (draggingItemIndex != null) onDrop()
        draggingItemIndex = null
        draggedDistance = 0f
        initialOffset = 0
    }
}

private fun Modifier.dragContainer(state: DragDropState): Modifier =
    pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> state.onDragStart(offset) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount)
            },
            onDragEnd = { state.onDragInterrupted() },
            onDragCancel = { state.onDragInterrupted() },
        )
    }
