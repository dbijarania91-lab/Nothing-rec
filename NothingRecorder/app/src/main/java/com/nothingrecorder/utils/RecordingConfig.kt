package com.nothingrecorder.utils

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RecordingConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val recordInternalAudio: Boolean,
    val recordMic: Boolean,
    val thermalGuard: Boolean,
    val audioSampleRate: Int = 44100
) : Parcelable {

    val resolutionLabel: String get() = when {
        width >= 2560 -> "2K"
        width >= 1920 -> "1080p"
        else -> "720p"
    }

    companion object {
        // Nothing Phone (1) optimised presets — Snapdragon 778G+

        fun preset1080p60(): RecordingConfig = RecordingConfig(
            width = 1920, height = 1080, fps = 60,
            bitrateBps = 30_000_000, // 30 Mbps
            recordInternalAudio = true, recordMic = false,
            thermalGuard = true
        )

        fun preset1080p120(): RecordingConfig = RecordingConfig(
            width = 1920, height = 1080, fps = 120,
            bitrateBps = 50_000_000, // 50 Mbps — needed for 120fps clarity
            recordInternalAudio = true, recordMic = false,
            thermalGuard = true
        )

        fun preset720p60(): RecordingConfig = RecordingConfig(
            width = 1280, height = 720, fps = 60,
            bitrateBps = 15_000_000, // 15 Mbps
            recordInternalAudio = true, recordMic = false,
            thermalGuard = true
        )

        fun preset2K30(): RecordingConfig = RecordingConfig(
            width = 2560, height = 1440, fps = 30,
            bitrateBps = 60_000_000, // 60 Mbps for 2K
            recordInternalAudio = true, recordMic = false,
            thermalGuard = true
        )

        fun fromPrefs(
            resolution: String, fps: Int, bitrateMbps: Int,
            internalAudio: Boolean, mic: Boolean, thermalGuard: Boolean
        ): RecordingConfig {
            val (w, h) = when (resolution) {
                "2K"    -> 2560 to 1440
                "1080p" -> 1920 to 1080
                else    -> 1280 to 720
            }
            return RecordingConfig(
                width = w, height = h, fps = fps,
                bitrateBps = bitrateMbps * 1_000_000,
                recordInternalAudio = internalAudio,
                recordMic = mic,
                thermalGuard = thermalGuard
            )
        }
    }
}
