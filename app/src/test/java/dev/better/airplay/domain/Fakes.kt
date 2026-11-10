package dev.better.airplay.domain

import dev.better.airplay.domain.model.DisplayCapabilities
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverSettings
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
    var muted: Boolean? = null
    var lowLatency: Boolean? = null
    override fun start(config: ReceiverConfig): StartResult { started = config; return result }
    override fun stop() { stopped++ }
    override fun disconnectSenders() {}
    override fun setVideoSurface(surface: VideoSurface?) { this.surface = surface }
    override fun setAudioMuted(muted: Boolean) { this.muted = muted }
    override fun setLowLatency(enabled: Boolean) { lowLatency = enabled }
}
