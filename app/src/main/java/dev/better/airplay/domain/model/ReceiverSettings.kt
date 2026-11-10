package dev.better.airplay.domain.model

/** Maximum mirroring resolution offered to senders. AUTO picks the best the TV can decode. */
enum class Resolution(val width: Int, val height: Int) {
    AUTO(0, 0),
    UHD_2160(3840, 2160),
    QHD_1440(2560, 1440),
    FHD_1080(1920, 1080),
    HD_720(1280, 720),
}

/** AUTO advertises HEVC + H.264 and lets the sender choose; H264_ONLY hides HEVC (max 1080p-class). */
enum class CodecPreference { AUTO, H264_ONLY }

/** Display refresh rate reported to senders. AUTO uses the TV's current mode. */
enum class RefreshRate(val hz: Int) { AUTO(0), HZ_60(60), HZ_50(50), HZ_30(30), HZ_24(24) }

/** Upper bound on mirrored frames per second requested from the sender. */
enum class FrameRateCap(val fps: Int) { FPS_60(60), FPS_30(30) }

/** Mirroring jitter buffer: lower latency vs. smoother playback on busy Wi-Fi. */
enum class LatencyMode(val bufferMs: Int) { LOW(40), BALANCED(120), SMOOTH(300) }

/** Video handover: switch the TV's refresh rate to match the content. */
enum class FrameRateMatching { OFF, SEAMLESS_ONLY, ALWAYS }

/** Who may connect. ONSCREEN_PIN shows a code on the TV the first time a device connects. */
enum class AccessMode { EVERYONE, ONSCREEN_PIN }

data class ReceiverSettings(
    val customName: String? = null,
    // Video (mirroring)
    val resolution: Resolution = Resolution.AUTO,
    val codec: CodecPreference = CodecPreference.AUTO,
    val refreshRate: RefreshRate = RefreshRate.AUTO,
    val frameRateCap: FrameRateCap = FrameRateCap.FPS_60,
    val overscanCompensation: Boolean = false,
    val latencyMode: LatencyMode = LatencyMode.BALANCED,
    // Audio
    val mirrorAudio: Boolean = true,
    val audioDelayMs: Int = 0,
    val surroundPassthrough: Boolean = true,
    // Video playback (handover)
    val hdrPassthrough: Boolean = true,
    val frameRateMatching: FrameRateMatching = FrameRateMatching.SEAMLESS_ONLY,
    // Access & diagnostics
    val access: AccessMode = AccessMode.EVERYONE,
    val statsOverlay: Boolean = false,
) {
    /** The subset of settings baked into the running receiver; changing these needs a restart. */
    fun engineProjection() = listOf(customName, resolution, codec, refreshRate, frameRateCap,
        overscanCompensation, audioDelayMs, access)

    companion object {
        const val AUDIO_DELAY_MIN_MS = -500
        const val AUDIO_DELAY_MAX_MS = 500
        const val AUDIO_DELAY_STEP_MS = 10
    }
}
