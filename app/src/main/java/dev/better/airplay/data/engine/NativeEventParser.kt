package dev.better.airplay.data.engine

import dev.better.airplay.domain.model.Artwork
import dev.better.airplay.domain.model.PlaybackProgress
import dev.better.airplay.domain.model.RemoteTarget
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.model.VideoFormat
import dev.better.airplay.domain.port.EngineEvent

/** Maps the native bridge's events to domain [EngineEvent]s. Unknown/diagnostic events return null. */
internal object NativeEventParser {
    /** [nowMs] is the local monotonic time the event arrived (stamps progress for extrapolation). */
    fun parse(event: String, arg: String, nowMs: Long = 0): EngineEvent? = when (event) {
        "client_request" -> {
            // "name | model | deviceid"
            val parts = arg.split(" | ")
            EngineEvent.SenderConnecting(
                Sender(name = parts.getOrNull(0)?.takeIf { it.isNotBlank() && it != "?" } ?: "Unknown device",
                    model = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "?" })
            )
        }
        "video_codec" -> EngineEvent.MirroringStarted(parseFormat(arg))
        "video_size" -> Regex("(\\d+)x(\\d+)").find(arg)?.let {
            EngineEvent.VideoSizeChanged(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        "video_play" -> EngineEvent.VideoPlaybackStarted
        "video_stop" -> EngineEvent.VideoPlaybackStopped
        "audio_format" -> if (arg.contains("screen=0")) EngineEvent.AudioStreamStarted else null
        "audio_codec" -> if (arg == "2") EngineEvent.AudioStreamStarted else null   // ALAC = music session
        "display_pin" -> EngineEvent.PinRequested(arg)
        "audio_stream_stopped" -> EngineEvent.AudioStreamStopped(nowMs)
        "progress" -> arg.split("/").mapNotNull { it.trim().toLongOrNull() }.takeIf { it.size == 3 }?.let { (s, c, e) ->
            EngineEvent.ProgressChanged(PlaybackProgress(s, c, e, nowMs))
        }
        "audio_rtp" -> arg.trim().toLongOrNull()?.let { EngineEvent.Playhead(it, nowMs) }
        "audio_flush", "audio_idle" -> EngineEvent.AudioFlushed(nowMs)
        "audio_resumed" -> EngineEvent.AudioResumed(nowMs)
        "dacp" -> arg.trim().split(Regex("\\s+")).takeIf { it.size == 2 && it.all(String::isNotBlank) }
            ?.let { (id, token) -> EngineEvent.RemoteControlAvailable(RemoteTarget(id, token)) }
        "conn_destroy" -> EngineEvent.SessionEnded
        else -> null
    }

    fun parseBinary(event: String, data: ByteArray): EngineEvent? = when (event) {
        "metadata" -> DmapParser.parse(data)?.let { EngineEvent.NowPlayingChanged(it) }
        "coverart" -> EngineEvent.ArtworkChanged(Artwork(data))
        else -> null
    }

    /** "codec=2 source=3840x2160 offset=0,0 size=3840x2160" */
    fun parseFormat(arg: String): VideoFormat? {
        val codec = when (Regex("codec=(\\d)").find(arg)?.groupValues?.get(1)) {
            "1" -> VideoFormat.Codec.H264
            "2" -> VideoFormat.Codec.HEVC
            else -> VideoFormat.Codec.UNKNOWN
        }
        val m = Regex("source=(\\d+)x(\\d+)").find(arg) ?: return VideoFormat(codec, 0, 0)
        return VideoFormat(codec, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
}
