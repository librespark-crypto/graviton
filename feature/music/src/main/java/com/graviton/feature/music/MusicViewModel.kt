package com.graviton.feature.music

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.graviton.core.data.repository.MusicRepository
import com.graviton.core.data.repository.PreferencesRepository
import com.graviton.core.model.AudioTrack
import com.graviton.core.model.MusicPlaylist
import com.graviton.core.model.lastMusicUriForFolder
import com.graviton.core.model.recordMusicPlay
import com.graviton.core.model.startIndexForFolderPlayback
import com.graviton.core.model.toggleMusicFavorite
import com.graviton.core.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sections shown in the music library. The names also describe the actual MediaStore query used. */
enum class MusicSection(@StringRes val labelRes: Int) {
    HOME(R.string.home),
    TRACKS(R.string.tracks),
    PLAYLISTS(R.string.playlists),
    ALBUMS(R.string.albums),
    ARTISTS(R.string.artists),
    FOLDERS(R.string.folders),
}

enum class MusicSort(@StringRes val labelRes: Int) {
    TITLE(R.string.title),
    ARTIST(R.string.artist),
    ALBUM(R.string.album),
    DATE_ADDED(R.string.recently_added),
    DURATION(R.string.duration),
}

sealed interface MusicFilter {
    data object None : MusicFilter
    data class Album(val name: String) : MusicFilter
    data class Artist(val name: String) : MusicFilter
    data class Folder(val path: String) : MusicFilter
    data class Playlist(val id: Long, val name: String, val trackIds: Set<Long> = emptySet()) : MusicFilter
    data object Favorites : MusicFilter
}

/**
 * The four states the music library can genuinely be in.
 *
 * This is derived from the repository flow, never set by hand from a UI callback. That is the whole
 * point: the previous implementation flipped an `isLoading` boolean imperatively and relied on a
 * later `MutableStateFlow` emission to flip it back — an emission that `StateFlow` conflates away
 * whenever the rescan produces an equal list, leaving the tab spinning forever.
 */
sealed interface MusicLibraryState {
    data object Loading : MusicLibraryState
    data class Success(val tracks: List<AudioTrack>, val playlists: List<MusicPlaylist>) : MusicLibraryState
    data object Empty : MusicLibraryState
    data class Error(val throwable: Throwable) : MusicLibraryState
}

/**
 * A grouping of tracks (album, artist or folder) precomputed in the ViewModel.
 *
 * Grouping is done once per library change rather than inside the composable, so scrolling never
 * re-runs the grouping pass.
 */
@Immutable
data class MusicCollection(
    val name: String,
    val trackCount: Int,
    val artworkUri: String?,
    val mediaUri: String?,
)

/** Everything the user can change from the UI. Kept apart from library data on purpose. */
@Immutable
private data class MusicQuery(
    val section: MusicSection = MusicSection.HOME,
    val query: String = "",
    val sort: MusicSort = MusicSort.TITLE,
    val ascending: Boolean = true,
    val filter: MusicFilter = MusicFilter.None,
    val isFilterLoading: Boolean = false,
)

@Stable
data class MusicUiState(
    val libraryState: MusicLibraryState = MusicLibraryState.Loading,
    val allTracks: List<AudioTrack> = emptyList(),
    val tracks: List<AudioTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val section: MusicSection = MusicSection.HOME,
    val recentlyPlayed: List<AudioTrack> = emptyList(),
    val recentlyAdded: List<AudioTrack> = emptyList(),
    val mostPlayed: List<AudioTrack> = emptyList(),
    val favorites: List<AudioTrack> = emptyList(),
    /** URIs of favourited tracks, for O(1) lookup while rendering rows. */
    val favoriteUris: Set<String> = emptySet(),
    /**
     * The saved queue position, if playback was interrupted part-way through a track. This is the
     * real resume point persisted by the player service, not a re-listing of the play history.
     */
    val resumeTrack: AudioTrack? = null,
    val resumePositionMs: Long = 0L,
    val query: String = "",
    val sort: MusicSort = MusicSort.TITLE,
    val ascending: Boolean = true,
    val filter: MusicFilter = MusicFilter.None,
    val isFilterLoading: Boolean = false,
    val albums: List<MusicCollection> = emptyList(),
    val artists: List<MusicCollection> = emptyList(),
    val folders: List<MusicCollection> = emptyList(),
) {
    val isLoading: Boolean get() = libraryState is MusicLibraryState.Loading
    val isEmpty: Boolean get() = libraryState is MusicLibraryState.Empty
    val error: Throwable? get() = (libraryState as? MusicLibraryState.Error)?.throwable

    /** A human label for the active filter, or `null` when the whole library is shown. */
    @Composable
    fun activeFilterLabel(): String? = when (val current = filter) {
        MusicFilter.None -> null
        MusicFilter.Favorites -> stringResource(R.string.favorites)
        is MusicFilter.Album -> "${stringResource(R.string.album)} • ${current.name}"
        is MusicFilter.Artist -> "${stringResource(R.string.artist)} • ${current.name}"
        is MusicFilter.Folder -> "${stringResource(R.string.folder)} • ${current.path.substringAfterLast('/')}"
        is MusicFilter.Playlist -> "${stringResource(R.string.playlist)} • ${current.name}"
    }
}

/** Below this, a saved position is treated as "not started" rather than something to resume. */
private const val RESUME_THRESHOLD_MS = 5_000L

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    /** The shared app preferences, reused rather than duplicated into this screen's state. */
    val applicationPreferences = preferencesRepository.applicationPreferences

    private val userQuery = MutableStateFlow(MusicQuery())

    /**
     * One collection of the library, owned by the ViewModel scope.
     *
     * `SharingStarted.Eagerly` matters: the scan is tied to the ViewModel, not to a composable or
     * to whether anybody is currently collecting. Navigating away and back re-reads the cached
     * value instead of restarting a scan, so there is never a second scan job racing the first and
     * never a window where the UI subscribes to a flow that has already delivered its only value.
     */
    private val library: StateFlow<MusicLibraryState> = combine(
        musicRepository.observeTracks(),
        musicRepository.observePlaylists(),
    ) { tracks, playlists ->
        if (tracks.isEmpty() && playlists.isEmpty()) {
            MusicLibraryState.Empty
        } else {
            MusicLibraryState.Success(tracks, playlists)
        }
    }
        .catch { throwable -> emit(MusicLibraryState.Error(throwable)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MusicLibraryState.Loading)

    /**
     * Only the preference fields the library screen actually reads.
     *
     * `applicationPreferences` emits for every setting in the app, including player and theme
     * settings. Projecting to this slice and de-duplicating means changing an unrelated setting no
     * longer re-filters, re-sorts and re-groups the whole library.
     */
    private val libraryPreferences = preferencesRepository.applicationPreferences
        .map { preferences ->
            LibraryPreferences(
                musicFavorites = preferences.musicFavorites,
                musicRecentlyPlayedUris = preferences.musicRecentlyPlayedUris,
                musicPlayCounts = preferences.musicPlayCounts,
                musicQueueUris = preferences.musicQueueUris,
                musicQueueIndex = preferences.musicQueueIndex,
                musicQueuePositionMs = preferences.musicQueuePositionMs,
            )
        }
        .distinctUntilChanged()

    val uiState: StateFlow<MusicUiState> = combine(
        library,
        userQuery,
        libraryPreferences,
    ) { libraryState, query, preferences ->
        buildUiState(libraryState, query, preferences)
    }
        // Filtering, sorting and grouping a large library is real work; keep it off the main
        // thread so scrolling and navigation stay smooth while it happens.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MusicUiState())

    /**
     * Album/artist/folder groupings depend only on the track list, but the combine above re-runs
     * whenever the search query or a preference changes. Caching against the exact list instance
     * keeps typing in the search box from re-grouping the whole library on every keystroke.
     */
    private var groupingCacheKey: List<AudioTrack>? = null
    private var groupingCache: Groupings = Groupings(emptyList(), emptyList(), emptyList())

    private fun groupingsFor(tracks: List<AudioTrack>): Groupings {
        if (groupingCacheKey === tracks) return groupingCache
        val groupings = Groupings(
            albums = tracks.groupBy { it.displayAlbum }
                .map { (name, items) -> items.toCollection(name) }
                .sortedBy { it.name.lowercase() },
            artists = tracks.groupBy { it.displayArtist }
                .map { (name, items) -> items.toCollection(name) }
                .sortedBy { it.name.lowercase() },
            folders = tracks.groupBy { it.path.substringBeforeLast('/', "") }
                .map { (name, items) -> items.toCollection(name) }
                .sortedBy { it.name.lowercase() },
        )
        groupingCacheKey = tracks
        groupingCache = groupings
        return groupings
    }

    /**
     * Asks the repository to rescan.
     *
     * It deliberately does not force the UI back into Loading. A rescan while a library is already
     * on screen is a background refresh; forcing Loading here is what made the tab depend on a
     * follow-up emission that a conflating flow is free to drop.
     */
    fun refresh() {
        musicRepository.refresh()
    }

    fun selectSection(section: MusicSection) {
        userQuery.update { it.copy(section = section, filter = MusicFilter.None, isFilterLoading = false) }
    }

    fun setQuery(query: String) = userQuery.update { it.copy(query = query) }

    fun setSort(sort: MusicSort) = userQuery.update { it.copy(sort = sort) }

    fun toggleSortDirection() = userQuery.update { it.copy(ascending = !it.ascending) }

    /** Switches to the Tracks list filtered to favourites. */
    fun showFavorites() =
        userQuery.update { it.copy(section = MusicSection.TRACKS, filter = MusicFilter.Favorites) }

    fun clearFilter() = userQuery.update { it.copy(filter = MusicFilter.None) }

    fun selectAlbum(album: String) =
        userQuery.update { it.copy(section = MusicSection.TRACKS, filter = MusicFilter.Album(album)) }

    fun selectArtist(artist: String) =
        userQuery.update { it.copy(section = MusicSection.TRACKS, filter = MusicFilter.Artist(artist)) }

    fun selectFolder(path: String) =
        userQuery.update { it.copy(section = MusicSection.TRACKS, filter = MusicFilter.Folder(path)) }

    fun folderStartIndex(path: String, tracks: List<AudioTrack>): Int {
        val last = preferencesRepository.applicationPreferences.value.lastMusicUriForFolder(path)
        return startIndexForFolderPlayback(tracks.map { it.uriString }, last)
    }

    /**
     * Appends [track] to the MediaStore playlist [playlistId].
     *
     * [onResult] receives `false` when the platform refuses the write, so the UI can say so instead
     * of silently doing nothing.
     */
    fun addTrackToPlaylist(playlistId: Long, track: AudioTrack, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val added = runCatching { musicRepository.addTracksToPlaylist(playlistId, listOf(track.id)) }
                .getOrDefault(false)
            onResult(added)
        }
    }

    /** Creates a playlist and immediately puts [track] in it. */
    fun createPlaylistWithTrack(name: String, track: AudioTrack, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val id = runCatching { musicRepository.createPlaylist(name) }.getOrNull()
            val added = id != null &&
                runCatching { musicRepository.addTracksToPlaylist(id, listOf(track.id)) }.getOrDefault(false)
            onResult(added)
        }
    }

    /** Toggles the favourite flag for [track] and persists it. */
    fun toggleFavorite(track: AudioTrack) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.toggleMusicFavorite(track.uriString) }
        }
    }

    fun recordPlay(track: AudioTrack) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                // countPlay is what feeds musicPlayCounts, which the "Most played" section reads.
                it.recordMusicPlay(
                    uri = track.uriString,
                    folderPath = track.path.substringBeforeLast('/', ""),
                    countPlay = true,
                )
            }
        }
    }

    /**
     * Selects a playlist and resolves its members.
     *
     * The resolution runs in [viewModelScope]; a newer selection simply overwrites the filter, and
     * a stale result is discarded by the id check, so no cancellation bookkeeping is needed.
     */
    fun selectPlaylist(playlist: MusicPlaylist) {
        userQuery.update {
            it.copy(
                section = MusicSection.TRACKS,
                filter = MusicFilter.Playlist(playlist.id, playlist.name),
                isFilterLoading = true,
            )
        }
        viewModelScope.launch {
            val ids = runCatching { musicRepository.getPlaylistTrackIds(playlist.id).toSet() }
                .getOrDefault(emptySet())
            userQuery.update { current ->
                val active = current.filter as? MusicFilter.Playlist ?: return@update current
                if (active.id != playlist.id) return@update current
                current.copy(filter = active.copy(trackIds = ids), isFilterLoading = false)
            }
        }
    }

    private fun buildUiState(
        libraryState: MusicLibraryState,
        query: MusicQuery,
        preferences: LibraryPreferences,
    ): MusicUiState {
        val allTracks = (libraryState as? MusicLibraryState.Success)?.tracks.orEmpty()
        val playlists = (libraryState as? MusicLibraryState.Success)?.playlists.orEmpty()

        val filtered = allTracks
            .asSequence()
            .filter { track ->
                when (val filter = query.filter) {
                    MusicFilter.None -> true
                    MusicFilter.Favorites -> track.uriString in preferences.musicFavorites
                    is MusicFilter.Album -> track.displayAlbum == filter.name
                    is MusicFilter.Artist -> track.displayArtist == filter.name
                    is MusicFilter.Folder -> track.path.substringBeforeLast('/', "") == filter.path
                    is MusicFilter.Playlist -> filter.trackIds.contains(track.id)
                }
            }
            .filter { track ->
                val text = query.query.trim()
                text.isBlank() || track.displayTitle.contains(text, ignoreCase = true) ||
                    track.displayArtist.contains(text, ignoreCase = true) ||
                    track.displayAlbum.contains(text, ignoreCase = true)
            }
            .toList()

        val sorted = when (query.sort) {
            MusicSort.TITLE -> filtered.sortedWith(compareBy<AudioTrack> { it.displayTitle.lowercase() })
            MusicSort.ARTIST -> filtered.sortedWith(
                compareBy<AudioTrack> { it.displayArtist.lowercase() }
                    .thenBy { it.displayTitle.lowercase() },
            )
            MusicSort.ALBUM -> filtered.sortedWith(
                compareBy<AudioTrack> { it.displayAlbum.lowercase() }
                    .thenBy { it.displayTitle.lowercase() },
            )
            MusicSort.DATE_ADDED -> filtered.sortedBy { it.dateAdded }
            MusicSort.DURATION -> filtered.sortedBy { it.duration }
        }

        val groupings = groupingsFor(allTracks)
        val byUri = allTracks.associateBy { it.uriString }
        val recentPlayed = preferences.musicRecentlyPlayedUris.mapNotNull(byUri::get)
        val mostPlayed = allTracks
            .filter { (preferences.musicPlayCounts[it.uriString] ?: 0) > 0 }
            .sortedByDescending { preferences.musicPlayCounts[it.uriString] ?: 0 }
            .take(12)
        val musicFavorites = preferences.musicFavorites.mapNotNull(byUri::get)
        val resumeUri = preferences.musicQueueUris.getOrNull(preferences.musicQueueIndex)
        val resumeTrack = resumeUri
            ?.takeIf { preferences.musicQueuePositionMs > RESUME_THRESHOLD_MS }
            ?.let(byUri::get)

        return MusicUiState(
            libraryState = libraryState,
            allTracks = allTracks,
            tracks = if (query.ascending) sorted else sorted.asReversed(),
            playlists = playlists,
            section = query.section,
            recentlyPlayed = recentPlayed,
            recentlyAdded = allTracks.sortedByDescending { it.dateAdded }.take(12),
            mostPlayed = mostPlayed,
            favorites = musicFavorites,
            favoriteUris = preferences.musicFavorites.toSet(),
            resumeTrack = resumeTrack,
            resumePositionMs = if (resumeTrack != null) preferences.musicQueuePositionMs else 0L,
            query = query.query,
            sort = query.sort,
            ascending = query.ascending,
            filter = query.filter,
            isFilterLoading = query.isFilterLoading,
            albums = groupings.albums,
            artists = groupings.artists,
            folders = groupings.folders,
        )
    }
}

/** The subset of persisted settings the library screen depends on. */
private data class LibraryPreferences(
    val musicFavorites: List<String>,
    val musicRecentlyPlayedUris: List<String>,
    val musicPlayCounts: Map<String, Int>,
    val musicQueueUris: List<String>,
    val musicQueueIndex: Int,
    val musicQueuePositionMs: Long,
)

private data class Groupings(
    val albums: List<MusicCollection>,
    val artists: List<MusicCollection>,
    val folders: List<MusicCollection>,
)

private fun List<AudioTrack>.toCollection(name: String) = MusicCollection(
    name = name,
    trackCount = size,
    artworkUri = firstOrNull { it.artworkUriString != null }?.artworkUriString,
    mediaUri = firstOrNull()?.uriString,
)
