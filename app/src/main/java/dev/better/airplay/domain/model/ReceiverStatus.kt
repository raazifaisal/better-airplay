package dev.better.airplay.domain.model

data class Sender(val name: String, val model: String? = null)

data class VideoFormat(val codec: Codec, val width: Int, val height: Int, val fps: Int = 0) {
    enum class Codec { H264, HEVC, UNKNOWN }

    /** e.g. "4K · HEVC" */
    val label: String
        get() {
            val longest = maxOf(width, height)
            val res = when {
                longest >= 3800 -> "4K"
                longest >= 2500 -> "1440p"
                longest >= 1900 -> "1080p"
                longest > 0 -> "${minOf(width, height)}p"
                else -> null
            }
            val c = when (codec) { Codec.H264 -> "H.264"; Codec.HEVC -> "HEVC"; Codec.UNKNOWN -> null }
            return listOfNotNull(res, c).joinToString(" · ")
        }
}

sealed interface ReceiverStatus {
    data object Stopped : ReceiverStatus
    data object Starting : ReceiverStatus
    data class Ready(val port: Int) : ReceiverStatus
    data class Connecting(val sender: Sender?) : ReceiverStatus
    data class Mirroring(val sender: Sender?, val format: VideoFormat?) : ReceiverStatus
    data class PlayingVideo(val sender: Sender?) : ReceiverStatus
    data class PlayingAudio(val sender: Sender?) : ReceiverStatus
    data class Failed(val code: Int) : ReceiverStatus

    val isConnected get() = this is Connecting || this is Mirroring || this is PlayingVideo || this is PlayingAudio
    /** The connected device, if any (named differently from subtypes' `sender` to avoid hiding). */
    val currentSender: Sender?
        get() = when (this) {
            is Connecting -> sender
            is Mirroring -> sender
            is PlayingVideo -> sender
            is PlayingAudio -> sender
            else -> null
        }
}

/** Track info sent by the sender during audio AirPlay (Music, podcasts, Control Center audio). */
data class NowPlaying(val title: String?, val artist: String?, val album: String?)

/** Encoded image (JPEG/PNG) as sent by the sender. Identity-compared on purpose: images are large. */
class Artwork(val bytes: ByteArray)

/** Everything the UI needs to know about the receiver right now. */
data class ReceiverSnapshot(
    val name: String = "",
    val status: ReceiverStatus = ReceiverStatus.Stopped,
    /** On-screen PIN while a new device is pairing. */
    val pin: String? = null,
    val nowPlaying: NowPlaying? = null,
    val artwork: Artwork? = null,
    /** Decoded mirror picture size, for aspect-correct display. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val progress: PlaybackProgress? = null,
    /** False while an audio session is paused (iOS tears the audio stream down but keeps the connection). */
    val playing: Boolean = true,
    /** Where to send play/pause/next/previous for this session, once the sender has announced it. */
    val remote: RemoteTarget? = null,
)

/** What the TV can actually decode, used to resolve AUTO settings. */
data class DisplayCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val hevc4k60: Boolean,
    val refreshHz: Int,
)

/** Fully resolved configuration handed to the receiver engine. */
data class ReceiverConfig(
    val name: String,
    val width: Int,
    val height: Int,
    val refreshHz: Int,
    val maxFps: Int,
    val hevc: Boolean,
    val videoHandover: Boolean,
    val overscanned: Boolean,
    val audioDelayMicros: Int,
    val requirePin: Boolean,
)
