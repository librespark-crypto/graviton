package com.graviton.feature.music.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.graviton.core.ui.R
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

/**
 * Synchronised lyrics viewer.
 *
 * Performance is the design constraint. The playback position changes several times a second, and
 * naively passing it to every row would recompose the whole list on every tick. Instead:
 *
 * - the position arrives as a lambda ([positionMs]) so reading it does not subscribe the list,
 * - the active line index is a `derivedStateOf`, so only a change of *line* invalidates anything,
 * - each row reads the position only when it is the active line, and only for word highlighting,
 * - rows are keyed by [LyricLine.id], which is stable across edits and reordering.
 */
@Composable
fun LyricsViewer(
    state: LyricsUiState,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
    onRetry: (() -> Unit)? = null,
) {
    when (state) {
        LyricsUiState.Loading -> LyricsMessage(modifier) {
            CircularProgressIndicator()
        }

        LyricsUiState.Empty -> LyricsMessage(modifier) {
            Text(
                text = stringResource(R.string.no_lyrics_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is LyricsUiState.Error -> LyricsMessage(modifier) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.lyrics_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                state.message?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (onRetry != null) {
                    Text(
                        text = stringResource(R.string.try_again),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        is LyricsUiState.Success -> {
            val document = state.document
            if (document.isSynced) {
                SyncedLyrics(
                    document = document,
                    positionMs = positionMs,
                    onSeek = onSeek,
                    modifier = modifier,
                    contentPadding = contentPadding,
                )
            } else {
                UnsyncedLyrics(text = document.unsynced.orEmpty(), modifier = modifier, contentPadding = contentPadding)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    document: LyricsDocument,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val activeIndex by remember(document) {
        derivedStateOf { document.lineAt(positionMs()) }
    }

    AutoScrollToActiveLine(listState = listState, activeIndex = activeIndex)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(
            count = document.lines.size,
            key = { index -> document.lines[index].id },
        ) { index ->
            val line = document.lines[index]
            LyricLineRow(
                line = line,
                isActive = index == activeIndex,
                positionMs = positionMs,
                onClick = { onSeek(line.timeMs) },
            )
        }
        item(key = "lyrics-tail-spacer") {
            // Lets the last line scroll up to the reading position instead of sticking to the
            // bottom edge.
            Spacer(Modifier.height(240.dp))
        }
    }
}

/**
 * Keeps the active line near the top third of the viewport.
 *
 * Auto-scroll pauses while the user is dragging and resumes once they let go, so scrolling back to
 * read an earlier verse is not fought by the animation.
 */
@Composable
private fun AutoScrollToActiveLine(listState: LazyListState, activeIndex: Int) {
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { inProgress ->
            userScrolling = inProgress
        }
    }
    LaunchedEffect(activeIndex, userScrolling) {
        if (activeIndex < 0 || userScrolling) return@LaunchedEffect
        val viewportHeight = listState.layoutInfo.viewportSize.height
        // Before the first layout the viewport is 0; scrolling to offset 0 is still correct.
        val offset = -(viewportHeight / 3)
        runCatching { listState.animateScrollToItem(activeIndex, offset) }
    }
}

/**
 * One lyric line.
 *
 * Inactive lines never read the playback position, so a position tick recomposes at most the two
 * lines whose active state actually changed.
 */
@Composable
private fun LyricLineRow(
    line: LyricLine,
    isActive: Boolean,
    positionMs: () -> Long,
    onClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.onSurface
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val color by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        animationSpec = spring(),
        label = "LyricLineColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.96f,
        animationSpec = spring(),
        label = "LyricLineScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (line.isBlank) {
            // Instrumental gap: shown as a small marker so the scroll position still makes sense.
            Text(
                text = "♪",
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        } else if (isActive && line.hasWordTiming) {
            WordTimedLine(line = line, positionMs = positionMs, activeColor = activeColor, restColor = inactiveColor)
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = color,
            )
        }

        line.translation?.takeIf(String::isNotBlank)?.let { translation ->
            Text(
                text = translation,
                style = MaterialTheme.typography.bodyMedium,
                color = color.copy(alpha = if (isActive) 0.75f else 0.5f),
            )
        }
    }
}

/**
 * Word-by-word highlighting for the active line.
 *
 * Only used when the source actually provided word timings; there is no interpolation from the
 * line duration, because inventing timings looks worse than line-level sync.
 */
@Composable
private fun WordTimedLine(
    line: LyricLine,
    positionMs: () -> Long,
    activeColor: Color,
    restColor: Color,
) {
    val position = positionMs()
    val text = remember(line, position, activeColor, restColor) {
        buildAnnotatedString {
            line.words.forEach { word ->
                val progress = word.progressAt(position)
                val split = (word.text.length * progress).toInt().coerceIn(0, word.text.length)
                if (split > 0) {
                    withStyle(SpanStyle(color = activeColor, fontWeight = FontWeight.Bold)) {
                        append(word.text.substring(0, split))
                    }
                }
                if (split < word.text.length) {
                    withStyle(SpanStyle(color = restColor)) { append(word.text.substring(split)) }
                }
            }
        }
    }
    Text(text = text, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun UnsyncedLyrics(text: String, modifier: Modifier, contentPadding: PaddingValues) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LyricsMessage(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Exposed for the editor's preview pane. */
@Composable
internal fun rememberActiveLineIndex(document: LyricsDocument, positionMs: () -> Long): State<Int> =
    remember(document) { derivedStateOf { document.lineAt(positionMs()) } }
