package com.graviton.feature.player.decoder

import android.media.MediaCodecInfo
import android.annotation.SuppressLint
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.common.Format
import com.graviton.core.model.decoder.BitDepth
import com.graviton.core.model.decoder.VideoCodec
import com.graviton.core.model.decoder.VideoStreamSpec

/**
 * Builds the [VideoStreamSpec] a capability check needs out of a Media3 [Format].
 *
 * [Format.profile] and [Format.level] are taken at face value and compared against
 * `MediaCodecInfo.CodecProfileLevel`, which is the numbering [DeviceDecoderCapabilities] reads back
 * out of `CodecCapabilities.profileLevels`. Both sides therefore have to agree on that numbering for
 * a profile comparison to mean anything; if a profile ever looks wrong in the diagnostics log, this
 * mapping is the first place to look.
 */
@SuppressLint("UnsafeOptInUsageError")
fun Format.toVideoStreamSpec(): VideoStreamSpec? {
    val codec = VideoCodec.fromMimeType(sampleMimeType) ?: return null
    val profileLevel = MediaCodecUtil.getCodecProfileAndLevel(this)
    val fmtProfile = profileLevel?.first ?: Format.NO_VALUE
    val fmtLevel = profileLevel?.second ?: Format.NO_VALUE
    val fmtWidth = this.width
    val fmtHeight = this.height
    val fmtFrameRate = this.frameRate

    return VideoStreamSpec(
        codec = codec,
        mimeType = sampleMimeType ?: codec.mimeType,
        profile = if (fmtProfile == Format.NO_VALUE) null else fmtProfile,
        level = if (fmtLevel == Format.NO_VALUE) null else fmtLevel,
        width = if (fmtWidth == Format.NO_VALUE) null else fmtWidth,
        height = if (fmtHeight == Format.NO_VALUE) null else fmtHeight,
        frameRate = if (fmtFrameRate == Format.NO_VALUE.toFloat() || fmtFrameRate <= 0f) null else fmtFrameRate,
        bitDepth = if (fmtProfile == Format.NO_VALUE) BitDepth.UNKNOWN else bitDepthFor(codec, fmtProfile),
    )
}

/**
 * Derives the sample bit depth from a codec profile.
 *
 * Only profiles whose bit depth is unambiguous are mapped. Everything else, including AV1 Main,
 * returns [BitDepth.UNKNOWN]: AV1 Main covers both 8-bit and 10-bit and the real answer lives in the
 * sequence header, which neither Media3's [Format] nor nextlib's mediainfo exposes.
 *
 * Extractors frequently leave [Format.profile] unset as well, so [BitDepth.UNKNOWN] is the common
 * case and callers must not read it as "8-bit".
 */
private fun bitDepthFor(codec: VideoCodec, profile: Int): BitDepth = when (codec) {
    VideoCodec.H264 -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> BitDepth.TEN

        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
        -> BitDepth.EIGHT

        else -> BitDepth.UNKNOWN
    }

    VideoCodec.HEVC -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
        -> BitDepth.TEN

        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> BitDepth.EIGHT

        else -> BitDepth.UNKNOWN
    }

    VideoCodec.AV1 -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus,
        -> BitDepth.TEN

        // AV1 Main is 8- or 10-bit depending on the sequence header, so it proves nothing.
        else -> BitDepth.UNKNOWN
    }

    VideoCodec.VP8, VideoCodec.VP9 -> BitDepth.UNKNOWN
}

fun getProfileName(codec: VideoCodec, profile: Int): String? = when (codec) {
    VideoCodec.H264 -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline -> "Constrained Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh -> "Constrained High"
        else -> "Profile $profile"
    }

    VideoCodec.HEVC -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
        else -> "Profile $profile"
    }

    VideoCodec.AV1 -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "Main 8"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
        else -> "Profile $profile"
    }

    VideoCodec.VP9 -> when (profile) {
        MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "Profile 0 (8-bit)"
        MediaCodecInfo.CodecProfileLevel.VP9Profile1 -> "Profile 1"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "Profile 2 (10-bit)"
        MediaCodecInfo.CodecProfileLevel.VP9Profile3 -> "Profile 3"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR -> "Profile 2 HDR"
        MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR -> "Profile 3 HDR"
        else -> "Profile $profile"
    }

    VideoCodec.VP8 -> "Profile $profile"
}

fun getLevelName(codec: VideoCodec, level: Int): String? = when (codec) {
    VideoCodec.H264 -> when (level) {
        MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "1.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> "1b"
        MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "1.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "1.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "1.3"
        MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "2.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "2.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "3.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "3.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "4.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "4.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "5.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "5.2"
        MediaCodecInfo.CodecProfileLevel.AVCLevel6 -> "6.0"
        MediaCodecInfo.CodecProfileLevel.AVCLevel61 -> "6.1"
        MediaCodecInfo.CodecProfileLevel.AVCLevel62 -> "6.2"
        else -> "Level $level"
    }

    VideoCodec.HEVC -> when (level) {
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel1, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel1 -> "1.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel2, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel2 -> "2.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel21, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3 -> "3.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4 -> "4.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel5 -> "5.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel52, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel52 -> "5.2"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel6, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel6 -> "6.0"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel61, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel61 -> "6.1"
        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel62, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel62 -> "6.2"
        else -> "Level $level"
    }

    VideoCodec.AV1 -> when (level) {
        MediaCodecInfo.CodecProfileLevel.AV1Level2 -> "2.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level3 -> "3.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level4 -> "4.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level5 -> "5.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level52 -> "5.2"
        MediaCodecInfo.CodecProfileLevel.AV1Level6 -> "6.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level61 -> "6.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level62 -> "6.2"
        MediaCodecInfo.CodecProfileLevel.AV1Level7 -> "7.0"
        MediaCodecInfo.CodecProfileLevel.AV1Level71 -> "7.1"
        MediaCodecInfo.CodecProfileLevel.AV1Level72 -> "7.2"
        MediaCodecInfo.CodecProfileLevel.AV1Level73 -> "7.3"
        else -> "Level $level"
    }

    VideoCodec.VP9 -> when (level) {
        MediaCodecInfo.CodecProfileLevel.VP9Level1 -> "1.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level11 -> "1.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level2 -> "2.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level21 -> "2.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level3 -> "3.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level31 -> "3.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level4 -> "4.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level41 -> "4.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level5 -> "5.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level51 -> "5.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level52 -> "5.2"
        MediaCodecInfo.CodecProfileLevel.VP9Level6 -> "6.0"
        MediaCodecInfo.CodecProfileLevel.VP9Level61 -> "6.1"
        MediaCodecInfo.CodecProfileLevel.VP9Level62 -> "6.2"
        else -> "Level $level"
    }

    VideoCodec.VP8 -> "Level $level"
}
