package dev.better.airplay.domain

import dev.better.airplay.domain.model.DisplayCapabilities
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.model.VideoPlaybackInfo
import dev.better.airplay.domain.port.VideoPlayer
import dev.better.airplay.domain.port.DeviceInfo
import dev.better.airplay.domain.port.EngineListener
import dev.better.airplay.domain.port.ReceiverController
import dev.better.airplay.domain.port.ReceiverEngine
import dev.better.airplay.domain.port.SettingsRepository
import dev.better.airplay.domain.port.StartResult
import dev.better.airplay.domain.port.VideoSurface

/** In-memory test doubles for the domain ports. */
class FakeSettings(var value: ReceiverSettings = ReceiverSettings()) : SettingsRepository {
    var saves = 0
    override fun load() = value
    override fun save(settings: ReceiverSettings) { value = settings; saves++ }
}

class FakeDevice(
    var name: String = "Family room TV",
    var caps: DisplayCapabilities = DisplayCapabilities(3840, 2160, hevc4k60 = true, refreshHz = 60),
) : DeviceInfo {
    override fun deviceName() = name
    override fun displayCapabilities() = caps
    override fun observeDeviceName(onChange: () -> Unit) = AutoCloseable { }
}

class FakeController : ReceiverController {
    var restarts = 0; var disconnects = 0; var ensures = 0
    override fun ensureRunning() { ensures++ }
    override fun restart() { restarts++ }
    override fun disconnect() { disconnects++ }
}

class FakeEngine(var result: StartResult = StartResult.Started(7000)) : ReceiverEngine {
    override var listener: EngineListener? = null
    var started: ReceiverConfig? = null
    var stopped = 0
    var surface: VideoSurface? = null
    val surfaceHistory = mutableListOf<VideoSurface?>()
    var muted: Boolean? = null
    var lowLatency: Boolean? = null
    val playbackInfo = mutableListOf<VideoPlaybackInfo>()
    override fun start(config: ReceiverConfig): StartResult { started = config; return result }
    override fun stop() { stopped++ }
    override fun disconnectSenders() {}
    /** Optional log shared with other fakes, to assert ordering across objects. */
    var log: MutableList<String>? = null
    override fun setVideoSurface(surface: VideoSurface?) { this.surface = surface; surfaceHistory += surface; log?.add("engine surface=$surface") }
    override fun setAudioMuted(muted: Boolean) { this.muted = muted }
    override fun setLowLatency(enabled: Boolean) { lowLatency = enabled }
    override fun updatePlaybackInfo(info: VideoPlaybackInfo) { playbackInfo += info }
}

/** Records commands as readable strings ("play <url> @<start>", "seek 12.0", "pause", "resume", "stop"). */
class FakeVideoPlayer : VideoPlayer {
    override var listener: VideoPlayer.Listener? = null
    val calls = mutableListOf<String>()
    /** The surface currently attached (named to avoid clashing with setSurface on the JVM). */
    var attached: VideoSurface? = null
    val surfaceHistory = mutableListOf<VideoSurface?>()
    var current = VideoPlaybackInfo.IDLE
    override fun play(url: String, startSec: Double) { calls += "play $url @$startSec" }
    override fun seek(positionSec: Double) { calls += "seek $positionSec" }
    override fun setPlaying(playing: Boolean) { calls += if (playing) "resume" else "pause" }
    override fun stop() { calls += "stop" }
    var log: MutableList<String>? = null
    override fun setSurface(surface: VideoSurface?) { attached = surface; surfaceHistory += surface; log?.add("player surface=$surface") }
    override fun info() = current
    override fun release() { calls += "release" }
}
