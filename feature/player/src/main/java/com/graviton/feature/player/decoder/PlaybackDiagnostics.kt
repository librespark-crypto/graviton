package com.graviton.feature.player.decoder

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.graviton.core.model.DecoderMode
import com.graviton.core.model.decoder.BitDepth
import com.graviton.core.model.decoder.DecoderCapability
import com.graviton.core.model.decoder.DecoderHierarchy
import com.graviton.core.model.decoder.SoftwareSupport
import com.graviton.core.model.decoder.VideoStreamSpec

/**
 * Logs what Graviton actually decoded a video with, for the decoder test matrix.
 *
 * One line per event under the [TAG] tag covers the fields the benchmark matrix asks for: the
 * selected decoder name, whether it was hardware or software, MIME type, profile, level, bit depth,
 * resolution, frame rate, decoder initialisation time, dropped frames, fallback attempts, renderer
 * errors and playback state.
 *
 * This is deliberately log-only. It observes playback and never changes it.
 */
@OptIn(UnstableApi::class)
class PlaybackDiagnostics(
    private val capabilities: DeviceDecoderCapabilities,
    private val decoderMode: DecoderMode,
    /**
     * Whether an app-bundled software decoder exists for a MIME type.
     *
     * Passed in rather than hardcoded because the honest answer is per-codec: nextlib's FFmpeg build
     * covers H.264, HEVC, MPEG-1/2, VP8 and VP9 but not AV1, so AV1 has no software fallback at all.
     */
    private val softwareDecoderAvailableFor: (String) -> Boolean,
) : AnalyticsListener {

    private var currentSpec: VideoStreamSpec? = null
    private var decoderInitialisations = 0
    private var droppedFrames = 0
    private var videoDecoderCounters: DecoderCounters? = null

    fun currentRenderedFrames(): Int =
        videoDecoderCounters?.renderedOutputBufferCount ?: snapshot.renderedFrames

    fun currentDroppedFrames(): Int =
        videoDecoderCounters?.droppedBufferCount ?: droppedFrames

    /**
     * The latest decoder facts, readable by the UI.
     *
     * These are the same values the log lines below report; exposing them lets the Video
     * information sheet show what actually decoded the stream instead of guessing. Nothing here
     * influences playback.
     */
    @Volatile
    var snapshot: PlaybackDiagnosticsSnapshot = PlaybackDiagnosticsSnapshot()
        private set

    override fun onVideoEnabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        videoDecoderCounters = decoderCounters
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        snapshot = snapshot.copy(
            renderedFrames = decoderCounters.renderedOutputBufferCount,
            droppedFrames = decoderCounters.droppedBufferCount,
        )
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        decoderInitialisations = 0
        droppedFrames = 0

        val spec = format.toVideoStreamSpec()
        currentSpec = spec

        val bitDepthStr = when {
            format.colorInfo != null && format.colorInfo!!.lumaBitdepth > 0 -> "${format.colorInfo!!.lumaBitdepth}-bit"
            spec?.bitDepth == BitDepth.TEN -> "10-bit"
            spec?.bitDepth == BitDepth.EIGHT -> "8-bit"
            else -> null
        }
        val profileStr = if (spec?.profile != null) {
            getProfileName(spec.codec, spec.profile!!)
        } else null
        val levelStr = if (spec?.level != null) {
            getLevelName(spec.codec, spec.level!!)
        } else null

        val bitrateVal = if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            format.bitrate.toLong()
        } else if (format.averageBitrate != Format.NO_VALUE && format.averageBitrate > 0) {
            format.averageBitrate.toLong()
        } else if (format.peakBitrate != Format.NO_VALUE && format.peakBitrate > 0) {
            format.peakBitrate.toLong()
        } else {
            PlaybackDiagnosticsSnapshot.UNKNOWN_LONG
        }

        snapshot = snapshot.copy(
            droppedFrames = 0,
            renderedFrames = videoDecoderCounters?.renderedOutputBufferCount ?: 0,
            decoderInitialisations = 0,
            mimeType = format.sampleMimeType ?: spec?.mimeType,
            width = if (format.width != Format.NO_VALUE) format.width else PlaybackDiagnosticsSnapshot.UNKNOWN_INT,
            height = if (format.height != Format.NO_VALUE) format.height else PlaybackDiagnosticsSnapshot.UNKNOWN_INT,
            frameRate = if (format.frameRate != Format.NO_VALUE.toFloat() && format.frameRate > 0f) format.frameRate else PlaybackDiagnosticsSnapshot.UNKNOWN_FLOAT,
            bitrate = bitrateVal,
            bitDepth = bitDepthStr,
            profile = profileStr,
            level = levelStr,
        )

        if (spec == null) {
            log("format mime=${format.sampleMimeType} codecs=${format.codecs} (no capability model)")
            return
        }

        val capability = capabilityFor(spec)
        val path = DecoderHierarchy.resolve(capability)
        val atRisk = DecoderHierarchy.isSoftwarePerformanceRisk(capability)

        log(
            "format ${capability.describe()} | mode=${decoderMode.label()} " +
                "| mime=${spec.mimeType} codecs=${format.codecs} " +
                "| bitDepth=${bitDepthStr ?: "unknown"} profile=${profileStr ?: "unknown"} level=${levelStr ?: "unknown"} " +
                "| path=$path${if (atRisk) " (software decode likely to drop frames)" else ""}",
        )
        log("device decoders for ${spec.mimeType}: ${capabilities.decoderNames(spec.mimeType)}")
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderInitialisations++
        val hardware = capabilities.isHardwareDecoderName(decoderName)
        val kind = when (hardware) {
            true -> "HW"
            false -> "SW(platform)"
            // Not a MediaCodec at all, which is how nextlib's FFmpeg renderer appears.
            null -> "SW(extension)"
        }
        val fallback = if (decoderInitialisations > 1) " [fallback attempt #$decoderInitialisations]" else ""

        snapshot = snapshot.copy(
            videoDecoderName = decoderName,
            isVideoDecoderHardware = hardware,
            videoDecoderInitMs = initializationDurationMs,
            decoderInitialisations = decoderInitialisations,
            renderedFrames = videoDecoderCounters?.renderedOutputBufferCount ?: snapshot.renderedFrames,
            droppedFrames = videoDecoderCounters?.droppedBufferCount ?: droppedFrames,
        )

        log("decoder=$decoderName kind=$kind initMs=$initializationDurationMs$fallback")
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        snapshot = snapshot.copy(audioDecoderName = decoderName)
    }

    override fun onVideoDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) {
        val totalDropped = currentDroppedFrames()
        val totalRendered = currentRenderedFrames()
        log("decoder released=$decoderName after ${decoderInitialisations} init(s), $totalDropped dropped frame(s), $totalRendered rendered frame(s)")
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrameCount: Int,
        elapsedMs: Long,
    ) {
        droppedFrames += droppedFrameCount
        val totalDropped = currentDroppedFrames()
        val totalRendered = currentRenderedFrames()
        snapshot = snapshot.copy(
            droppedFrames = totalDropped,
            renderedFrames = totalRendered,
        )
        log("droppedFrames=+$droppedFrameCount in ${elapsedMs}ms (total=$totalDropped, rendered=$totalRendered)")
    }

    override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) {
        log("videoCodecError: ${videoCodecError.javaClass.simpleName}: ${videoCodecError.message}", error = true)
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        log(
            "playerError code=${error.errorCode} (${error.errorCodeName}) " +
                "spec=${currentSpec?.describe() ?: "unknown"}: ${error.message}",
            error = true,
        )
    }

    private fun capabilityFor(spec: VideoStreamSpec) = DecoderCapability(
        spec = spec,
        hardware = capabilities.hardwareSupportFor(spec),
        alternativeHardware = capabilities.alternativeHardwareSupportFor(spec),
        software = if (softwareDecoderAvailableFor(spec.mimeType)) {
            SoftwareSupport.AVAILABLE
        } else {
            SoftwareSupport.UNAVAILABLE
        },
    )

    private fun log(message: String, error: Boolean = false) {
        if (error) Log.w(TAG, message) else Log.i(TAG, message)
    }

    companion object {
        const val TAG = "GravitonDecoder"
    }
}

/**
 * An immutable read of what the renderers are currently doing.
 *
 * Null / [UNKNOWN_INT] members mean "not reported yet", which the UI surfaces as "Unknown" rather
 * than inventing a value.
 */
data class PlaybackDiagnosticsSnapshot(
    val videoDecoderName: String? = null,
    /** True for a platform hardware decoder, false for a platform software one, null for an app decoder. */
    val isVideoDecoderHardware: Boolean? = null,
    val videoDecoderInitMs: Long = UNKNOWN_LONG,
    val audioDecoderName: String? = null,
    val droppedFrames: Int = 0,
    val renderedFrames: Int = 0,
    val decoderInitialisations: Int = 0,
    val mimeType: String? = null,
    val width: Int = UNKNOWN_INT,
    val height: Int = UNKNOWN_INT,
    val frameRate: Float = UNKNOWN_FLOAT,
    val bitrate: Long = UNKNOWN_LONG,
    val bitDepth: String? = null,
    val profile: String? = null,
    val level: String? = null,
) {
    companion object {
        const val UNKNOWN_LONG = -1L
        const val UNKNOWN_INT = -1
        const val UNKNOWN_FLOAT = -1f
    }
}
