package com.graviton.feature.player.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen

/**
 * What the renderer is currently able to draw.
 *
 * [UNKNOWN] is a real state, not a placeholder: between a media item transition and the first
 * `onTracksChanged` for the new item the player still reports the *previous* item's tracks and
 * metadata. Treating that window as "audio" is what used to paint the previous song's album art
 * inside the video area; treating it as "video" would flash a black frame over audio-only media.
 * While unknown, neither artwork nor decoded frames are presented — the shutter covers the surface.
 */
enum class MediaContentType {
    UNKNOWN,
    AUDIO_ONLY,
    VIDEO,
}

@Composable
fun rememberMediaContentTypeState(player: Player): MediaContentTypeState {
    val state = remember(player) { MediaContentTypeState(player) }
    LaunchedEffect(player) { state.observe() }
    return state
}

/**
 * Single source of truth for "is this item audio-only or video".
 *
 * Derived exclusively from the player's *track* information. Artwork presence is deliberately not
 * part of the decision: every item in Graviton gets an artwork URI (music album art, video
 * thumbnail, or the default placeholder), so artwork can never imply "this is not a video".
 */
@Stable
class MediaContentTypeState(private val player: Player) {
    var contentType: MediaContentType by mutableStateOf(MediaContentType.UNKNOWN)
        private set

    val isAudioOnly: Boolean get() = contentType == MediaContentType.AUDIO_ONLY

    suspend fun observe() {
        update()
        player.listen { events ->
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                // Invalidate immediately: the tracks reported right now still belong to the
                // item that just finished.
                contentType = MediaContentType.UNKNOWN
            }
            if (events.containsAny(
                    Player.EVENT_TRACKS_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                update()
            }
        }
    }

    private fun update() {
        val groups = player.currentTracks.groups
        contentType = when {
            groups.isEmpty() -> MediaContentType.UNKNOWN
            groups.any { it.type == C.TRACK_TYPE_VIDEO } -> MediaContentType.VIDEO
            groups.any { it.type == C.TRACK_TYPE_AUDIO } -> MediaContentType.AUDIO_ONLY
            else -> MediaContentType.UNKNOWN
        }
    }
}
