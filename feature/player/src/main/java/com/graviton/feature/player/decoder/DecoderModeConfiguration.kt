package com.graviton.feature.player.decoder

import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.graviton.core.model.DecoderMode

/**
 * The Media3 knobs that decide which decoder plays a video.
 *
 * @param extensionRendererMode where nextlib's FFmpeg (software) renderers sit relative to
 *   MediaCodec's.
 * @param enableDecoderFallback whether `MediaCodecRenderer` retries another *MediaCodec* decoder
 *   when the first one fails to initialise or cannot handle the format.
 * @param allowHardwareCodecs whether platform hardware-accelerated codecs may be selected.
 * @param allowSoftwareCodecs whether platform software codecs (and the bundled FFmpeg renderers)
 *   may be selected.
 */
data class DecoderModeConfiguration(
    val extensionRendererMode: Int,
    val enableDecoderFallback: Boolean,
    val allowHardwareCodecs: Boolean,
    val allowSoftwareCodecs: Boolean,
)

/**
 * Maps the user-facing [DecoderMode] onto Media3's decoder-selection knobs.
 *
 * The four modes are genuinely distinct, and each one matches its label:
 *
 * - **Auto** – MediaCodec first, FFmpeg renderers available as a fallback, decoder fallback on.
 *   Whatever plays the stream best wins.
 * - **HW** – hardware decoders only. Extension renderers are off *and* the codec selector drops
 *   every non-hardware-accelerated codec, so there is no silent software path. Decoder fallback is
 *   disabled, because "fall back to another codec" here could only mean falling back to something
 *   the user asked not to use; unplayable media surfaces as an error instead.
 * - **HW+** – hardware first, software allowed as a fallback. Both codec families are permitted,
 *   FFmpeg renderers sit behind MediaCodec, and decoder fallback is on.
 * - **SW** – software/bundled decoders only. FFmpeg renderers are preferred and hardware-
 *   accelerated platform codecs are filtered out.
 */
fun DecoderMode.toConfiguration(): DecoderModeConfiguration = when (this) {
    DecoderMode.AUTO -> DecoderModeConfiguration(
        extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        enableDecoderFallback = true,
        allowHardwareCodecs = true,
        allowSoftwareCodecs = true,
    )
    DecoderMode.HARDWARE -> DecoderModeConfiguration(
        extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
        enableDecoderFallback = false,
        allowHardwareCodecs = true,
        allowSoftwareCodecs = false,
    )
    DecoderMode.HARDWARE_PLUS -> DecoderModeConfiguration(
        extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
        enableDecoderFallback = true,
        allowHardwareCodecs = true,
        allowSoftwareCodecs = true,
    )
    DecoderMode.SOFTWARE -> DecoderModeConfiguration(
        extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
        enableDecoderFallback = true,
        allowHardwareCodecs = false,
        allowSoftwareCodecs = true,
    )
}

/**
 * A codec selector that enforces the hardware/software policy of [configuration].
 *
 * If filtering would leave no decoder at all, the unfiltered list is returned: refusing to play
 * anything is worse than honouring the preference approximately, and Media3 reports the real
 * capability failure itself.
 */
fun MediaCodecSelector.filteredBy(configuration: DecoderModeConfiguration): MediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val all = this.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        val filtered = all.filter { info ->
            if (info.hardwareAccelerated) configuration.allowHardwareCodecs else configuration.allowSoftwareCodecs
        }
        if (filtered.isEmpty()) all else filtered
    }

/** Short label used in the diagnostics log, matching the in-player chip. */
fun DecoderMode.label(): String = when (this) {
    DecoderMode.AUTO -> "Auto"
    DecoderMode.HARDWARE -> "HW"
    DecoderMode.HARDWARE_PLUS -> "HW+"
    DecoderMode.SOFTWARE -> "SW"
}

/** Kept for readability at call sites that only need the codec list. */
internal fun List<MediaCodecInfo>.hardwareOnly(): List<MediaCodecInfo> = filter { it.hardwareAccelerated }
