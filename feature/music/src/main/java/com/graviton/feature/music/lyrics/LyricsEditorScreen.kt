package com.graviton.feature.music.lyrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.graviton.core.ui.R

/**
 * Structured LRC editor.
 *
 * Each lyric line is a row with its own timestamp field and controls; there is deliberately no
 * "edit the whole file as text" mode, because that loses word timings and makes stamping a line
 * from the current playback position impossible.
 *
 * The editor always works against the track that is currently playing, which is what makes
 * "set timestamp from current position" and "preview from this line" meaningful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsEditorScreen(
    state: LyricsEditorState,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onSave: (String) -> Unit,
    onImport: (android.net.Uri) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var offsetInput by remember { mutableStateOf(state.offsetMs.toString()) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onImport) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lyrics_editor)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = stringResource(R.string.lyrics_import))
                    }
                    TextButton(onClick = { state.sortByTime(); onSave(state.toLrc()) }) {
                        Text(stringResource(R.string.lyrics_save))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { state.addLineAfter(state.lines.lastIndex, positionMs()) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.lyrics_add_line))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OffsetControls(
                value = offsetInput,
                onValueChange = { text ->
                    offsetInput = text
                    text.toLongOrNull()?.let(state::setOffset)
                },
                onShift = { delta ->
                    state.shiftAll(delta)
                },
            )

            if (state.lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.lyrics_no_lines),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.lines, key = { it.id }) { line ->
                        val index = state.lines.indexOf(line)
                        EditorLineCard(
                            line = line,
                            expanded = state.expandedIndex == index,
                            onExpandToggle = {
                                state.expandedIndex = if (state.expandedIndex == index) -1 else index
                            },
                            onTextChange = { state.updateText(index, it) },
                            onTranslationChange = { state.updateTranslation(index, it) },
                            onTimestampChange = { state.updateTimestamp(index, it) },
                            onStampFromPosition = { state.setTimestampFromPosition(index, positionMs()) },
                            onPreview = { onSeek(line.timeMs) },
                            onAddBelow = { state.addLineAfter(index, positionMs()) },
                            onDelete = { state.deleteLine(index) },
                            onMoveUp = { state.moveLine(index, index - 1) },
                            onMoveDown = { state.moveLine(index, index + 1) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OffsetControls(
    value: String,
    onValueChange: (String) -> Unit,
    onShift: (Long) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.lyrics_offset)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Shifting rewrites the timestamps; the offset tag above leaves them alone and asks
            // the player to compensate. Both are genuinely useful, so both are offered.
            listOf(-500L, -100L, 100L, 500L).forEach { delta ->
                FilledTonalButton(onClick = { onShift(delta) }) {
                    Text(if (delta > 0) "+$delta" else "$delta")
                }
            }
        }
    }
}

@Composable
private fun EditorLineCard(
    line: EditableLine,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onTextChange: (String) -> Unit,
    onTranslationChange: (String) -> Unit,
    onTimestampChange: (String) -> Boolean,
    onStampFromPosition: () -> Unit,
    onPreview: () -> Unit,
    onAddBelow: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var timestampInput by remember(line.id, line.timeMs) { mutableStateOf(line.timestamp) }
    var timestampValid by remember(line.id) { mutableStateOf(true) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = line.timestamp,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                IconButton(onClick = onPreview) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.lyrics_preview_from_line))
                }
                IconButton(onClick = onExpandToggle) {
                    Icon(
                        if (expanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.lyrics_edit),
                    )
                }
            }

            if (expanded) {
                OutlinedTextField(
                    value = timestampInput,
                    onValueChange = {
                        timestampInput = it
                        timestampValid = onTimestampChange(it)
                    },
                    label = { Text(stringResource(R.string.lyrics_timestamp)) },
                    isError = !timestampValid,
                    supportingText = if (!timestampValid) {
                        { Text(stringResource(R.string.lyrics_invalid_timestamp)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = line.text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.lyrics_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = line.translation.orEmpty(),
                    onValueChange = onTranslationChange,
                    label = { Text(stringResource(R.string.lyrics_translation)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onStampFromPosition) {
                        Icon(Icons.Default.Timer, contentDescription = null)
                        Text(
                            text = stringResource(R.string.lyrics_set_timestamp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onMoveUp) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(R.string.lyrics_move_up))
                    }
                    IconButton(onClick = onMoveDown) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(R.string.lyrics_move_down))
                    }
                    IconButton(onClick = onAddBelow) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.lyrics_add_line))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.lyrics_delete_line))
                    }
                }
            }
        }
    }
}
