package dev.better.airplay.domain.port

import dev.better.airplay.domain.model.Artwork
import dev.better.airplay.domain.model.DisplayCapabilities
import dev.better.airplay.domain.model.NowPlaying
import dev.better.airplay.domain.model.PlaybackProgress
import dev.better.airplay.domain.model.RemoteCommand
import dev.better.airplay.domain.model.RemoteTarget
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.model.Sender
import dev.better.airplay.domain.model.VideoError
import dev.better.airplay.domain.model.VideoFormat
import dev.better.airplay.domain.model.VideoPlaybackInfo

/** Opaque handle to somewhere video can be drawn (the data layer wraps an Android Surface). */
interface VideoSurface

/** The AirPlay protocol engine (implemented over UxPlay in the data layer). */
interface ReceiverEngine {
    var listener: EngineListener?
    fun start(config: ReceiverConfig): StartResult
    fun stop()
    fun disconnectSenders()
    /** Where mirrored video renders; null detaches (frames are cached until a surface returns). */
    fun setVideoSurface(surface: VideoSurface?)
    fun setAudioMuted(muted: Boolean)
    fun setLowLatency(enabled: Boolean)
    /**
     * Latest state of the handover video player. The engine answers the phone's playback-info
     * polls from this (it never calls back into the app to ask), so push it regularly.
     */
    fun updatePlaybackInfo(info: VideoPlaybackInfo)
}

/**
 * Plays a handed-over video URL (HLS from the receiver's local server, or a direct http(s) URL).
 * Commands may be called from any thread; the implementation moves them to its own thread.
 */
interface VideoPlayer {
    interface Listener {
        /** Regular progress (about twice a second while active), on the player's thread. */
        fun onInfo(info: VideoPlaybackInfo)
        fun onVideoSize(width: Int, height: Int)
        fun onError(error: VideoError)
    }

    var listener: Listener?
    /** Load [url] and start playing at [startSec]. Replaces whatever was playing. */
    fun play(url: String, startSec: Double)
    fun seek(positionSec: Double)
    fun setPlaying(playing: Boolean)
    /** Stop and unload; the player can [play] again afterwards. */
    fun stop()
    /** Where the picture is drawn; null detaches (audio keeps playing). */
    fun setSurface(surface: VideoSurface?)
    /** Current state snapshot. */
    fun info(): VideoPlaybackInfo
    /** Free all resources; the player can't be used afterwards. */
    fun release()
}

sealed interface StartResult {
    data class Started(val port: Int) : StartResult
    data class Failed(val code: Int) : StartResult
}

fun interface EngineListener {
    /** May be called on any thread. */
    fun onEngineEvent(event: EngineEvent)
}

sealed interface EngineEvent {
    data class SenderConnecting(val sender: Sender) : EngineEvent
    data class MirroringStarted(val format: VideoFormat?) : EngineEvent
    /** The phone handed a video to the TV (YouTube "play on TV", Safari's AirPlay button, ...). */
    data class VideoHandoverStarted(val url: String, val startSec: Double) : EngineEvent
    /** The phone's scrubber moved. */
    data class VideoHandoverSeek(val positionSec: Double) : EngineEvent
    /** Play (rate 1) / pause (rate 0) from the phone. */
    data class VideoHandoverRate(val rate: Float) : EngineEvent
    /** The phone stopped the video, or the receiver ended the handover. */
    data object VideoHandoverStopped : EngineEvent
    data object AudioStreamStarted : EngineEvent
    /** The sender tore down the audio stream (iOS does this on pause); the connection stays open. */
    data class AudioStreamStopped(val nowMs: Long) : EngineEvent
    data class PinRequested(val pin: String) : EngineEvent
    data class NowPlayingChanged(val nowPlaying: NowPlaying) : EngineEvent
    data class ArtworkChanged(val artwork: Artwork?) : EngineEvent
    data class VideoSizeChanged(val width: Int, val height: Int) : EngineEvent
    data class ProgressChanged(val progress: PlaybackProgress) : EngineEvent
    /** RTP timestamp of the audio frame just handed to the output: the authoritative playhead. */
    data class Playhead(val rtp: Long, val nowMs: Long) : EngineEvent
    /** The sender flushed its audio (pause / seek); progress freezes until the next update. */
    data class AudioFlushed(val nowMs: Long) : EngineEvent
    /** Audio packets are flowing again after a flush / idle gap (the sender pressed play). */
    data class AudioResumed(val nowMs: Long) : EngineEvent
    data class RemoteControlAvailable(val target: RemoteTarget) : EngineEvent
    data object SessionEnded : EngineEvent
}

/** Sends playback commands back to the sender (DACP). Fire-and-forget; never blocks the caller. */
interface RemoteControl {
    fun send(target: RemoteTarget, command: RemoteCommand)
}

interface SettingsRepository {
    fun load(): ReceiverSettings
    fun save(settings: ReceiverSettings)
}

interface DeviceInfo {
    /** The TV's own name from system settings. */
    fun deviceName(): String
    fun displayCapabilities(): DisplayCapabilities
    /** Calls [onChange] when the system device name changes. Close to stop observing. */
    fun observeDeviceName(onChange: () -> Unit): AutoCloseable
}

/** Commands to the long-running receiver (implemented by the platform service). */
interface ReceiverController {
    fun ensureRunning()
    fun restart()
    fun disconnect()
}
