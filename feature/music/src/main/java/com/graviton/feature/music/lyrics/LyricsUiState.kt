package com.graviton.feature.music.lyrics

import androidx.compose.runtime.Immutable

/** The four states the lyrics pane can be in. The UI renders exactly one of them. */
@Immutable
sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data object Empty : LyricsUiState
    data class Error(val message: String?) : LyricsUiState
    data class Success(val document: LyricsDocument) : LyricsUiState
}
