package com.graviton.feature.player.state

import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi
import com.graviton.feature.player.extensions.formatted
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun rememberMediaPresentationState(player: Player): MediaPresentationState {
    val mediaPresentationState = remember { MediaPresentationState(player) }
    LaunchedEffect(player) { mediaPresentationState.observe() }
    return mediaPresentationState
}

/**
 * Player state surfaced to the UI.
 *
 * Buffering information comes straight from the player: [isBuffering] mirrors
 * [Player.STATE_BUFFERING] and [bufferedPercentage] mirrors [Player.getBufferedPercentage], which
 * ExoPlayer derives from the actually buffered position and the media duration. Nothing here
 * simulates progress — when the pipeline cannot produce a meaningful value (live or unknown-length
 * streams) [bufferedPercentage] stays 0 and callers must treat it as "no data".
 */
@Stable
class MediaPresentationState(
    private val player: Player,
    @param:IntRange(from = 0) private val tickIntervalMs: Long = 500,
) {
    var position: Long by mutableLongStateOf(0L)
        private set

    var duration: Long by mutableLongStateOf(0L)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set

    var isLoading: Boolean by mutableStateOf(true)
        private set

    var isBuffering: Boolean by mutableStateOf(false)
        private set

    /**
     * The percentage of the current media that ExoPlayer reports as buffered, 0–100. This is a
     * real measurement ([Player.getBufferedPercentage]); it is 0 whenever the player cannot
     * compute it, e.g. for live streams or media with an unknown length.
     */
    var bufferedPercentage: Int by mutableStateOf(0)
        private set

    /**
     * True when the current media item is delivered over the network (http/https/rtsp/...).
     * Used to decide whether a buffering percentage is meaningful to show.
     */
    var isNetworkStream: Boolean by mutableStateOf(false)
        private set

    suspend fun observe() {
        updatePosition()
        updateDuration()
        isPlaying = player.isPlaying
        isLoading = player.isLoading
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        updateIsNetworkStream()
        updateBufferedPercentage()

        coroutineScope {
            launch {
                player.listen { events ->
                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                        )
                    ) {
                        updateDuration()
                    }

                    if (events.containsAny(
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_TIMELINE_CHANGED,
                        )
                    ) {
                        updateIsNetworkStream()
                    }

                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                        this@MediaPresentationState.isBuffering = player.playbackState == Player.STATE_BUFFERING
                        // Re-read the real buffered amount when the player enters/leaves buffering,
                        // so the indicator shows a fresh value immediately.
                        updateBufferedPercentage()
                    }

                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        this@MediaPresentationState.isPlaying = player.isPlaying
                    }

                    if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) {
                        updatePosition()
                    }

                    if (events.containsAny(Player.EVENT_IS_LOADING_CHANGED)) {
                        this@MediaPresentationState.isLoading = player.isLoading
                    }
                }
            }

            while (true) {
                delay(tickIntervalMs)
                if (player.isPlaying) {
                    updatePosition()
                }
                // While the player is waiting for data, pick up the player's real buffered
                // percentage on the existing tick — this is a read of player state, not a
                // simulated progression.
                if (player.playbackState == Player.STATE_BUFFERING) {
                    updateBufferedPercentage()
                }
            }
        }
    }

    private fun updatePosition() {
        position = player.currentPosition.coerceAtLeast(0L)
    }

    private fun updateDuration() {
        duration = player.duration.coerceAtLeast(0L)
    }

    private fun updateBufferedPercentage() {
        bufferedPercentage = player.bufferedPercentage.coerceIn(0, 100)
    }

    private fun updateIsNetworkStream() {
        val scheme = player.currentMediaItem?.localConfiguration?.uri?.scheme
        isNetworkStream = scheme != null && scheme.lowercase() in NETWORK_SCHEMES
    }

    companion object {
        private val NETWORK_SCHEMES = setOf("http", "https", "rtsp", "rtsps", "rtmp", "mms", "udp")
    }
}

val MediaPresentationState.positionFormatted: String
    get() = position.milliseconds.formatted()

val MediaPresentationState.durationFormatted: String
    get() = duration.milliseconds.formatted()

val MediaPresentationState.pendingPositionFormatted: String
    get() = (duration - position).milliseconds.formatted()

/**
 * A buffering percentage that is backed by real, measurable stream data, or null.
 *
 * A percentage is only reported when all of these hold:
 *  - the current item is a network stream (a local file trivially reports 100%),
 *  - the media duration is known (rules out live and unknown-length streams, for which
 *    ExoPlayer reports 0),
 *  - the player currently reports a buffered amount in 1..99.
 * Anything else returns null and callers must show an indeterminate indicator instead of
 * inventing a number.
 */
val MediaPresentationState.realBufferedPercentage: Int?
    get() = if (
        isNetworkStream &&
        duration > 0 &&
        bufferedPercentage in 1..99
    ) {
        bufferedPercentage
    } else {
        null
    }
