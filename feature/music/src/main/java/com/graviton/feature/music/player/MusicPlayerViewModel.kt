package com.graviton.feature.music.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graviton.core.data.repository.MusicRepository
import com.graviton.core.data.repository.PreferencesRepository
import com.graviton.core.model.toggleMusicFavorite
import com.graviton.feature.music.lyrics.LyricsParser
import com.graviton.feature.music.lyrics.LyricsRepository
import com.graviton.feature.music.lyrics.LyricsRequest
import com.graviton.feature.music.lyrics.LyricsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    private val lyricsRepository: LyricsRepository,
    private val musicRepository: MusicRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val lyricsInternal = MutableStateFlow<LyricsUiState>(LyricsUiState.Empty)
    val lyrics = lyricsInternal.asStateFlow()

    val preferences = preferencesRepository.applicationPreferences

    /**
     * The lyrics load in flight.
     *
     * Skipping tracks quickly must not let an older load overwrite a newer one, so the previous
     * job is cancelled rather than left to race.
     */
    private var lyricsJob: Job? = null

    /** The request the current lyrics belong to, so edits are saved against the right track. */
    private var currentRequest: LyricsRequest? = null

    /** Toggles the favourite flag for the item currently playing. */
    fun toggleFavorite(uriString: String?) {
        val uri = uriString?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.toggleMusicFavorite(uri) }
        }
    }

    fun loadLyrics(uriString: String?, title: String?) {
        lyricsJob?.cancel()
        if (uriString.isNullOrBlank()) {
            currentRequest = null
            lyricsInternal.value = LyricsUiState.Empty
            return
        }
        if (!preferencesRepository.applicationPreferences.value.musicShowLyrics) {
            lyricsInternal.value = LyricsUiState.Empty
            return
        }
        lyricsInternal.value = LyricsUiState.Loading
        lyricsJob = viewModelScope.launch {
            val track = runCatching { musicRepository.getTrack(uriString) }.getOrNull()
            val request = LyricsRequest(
                mediaUri = uriString,
                filePath = track?.path,
                title = title.orEmpty().ifBlank { track?.displayTitle.orEmpty() },
                artist = track?.displayArtist.orEmpty(),
                album = track?.displayAlbum,
                durationMs = track?.duration,
            )
            currentRequest = request
            val result = runCatching { lyricsRepository.load(request) }
            lyricsInternal.value = result.fold(
                onSuccess = { document ->
                    if (document.isEmpty) LyricsUiState.Empty else LyricsUiState.Success(document)
                },
                onFailure = { LyricsUiState.Error(it.message) },
            )
        }
    }

    fun retryLyrics() {
        val request = currentRequest ?: return
        loadLyrics(request.mediaUri, request.title)
    }

    /** Persists edited lyrics for the current track and refreshes the viewer. */
    fun saveLyrics(raw: String, onResult: (Boolean) -> Unit = {}) {
        val request = currentRequest ?: return onResult(false)
        viewModelScope.launch {
            val result = runCatching { lyricsRepository.replace(request, raw) }
            result.getOrNull()?.let { document ->
                lyricsInternal.value =
                    if (document.isEmpty) LyricsUiState.Empty else LyricsUiState.Success(document)
            }
            onResult(result.isSuccess)
        }
    }

    /** Loads a user-picked `.lrc`/`.ttml` file into the editor without saving it yet. */
    fun importLyrics(uri: Uri, onLoaded: (com.graviton.feature.music.lyrics.LyricsDocument) -> Unit) {
        viewModelScope.launch {
            val raw = runCatching { lyricsRepository.import(uri) }.getOrNull() ?: return@launch
            onLoaded(LyricsParser.parse(raw))
        }
    }
}
