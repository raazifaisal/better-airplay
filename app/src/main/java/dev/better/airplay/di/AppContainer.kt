package dev.better.airplay.di

import android.content.Context
import android.view.Surface
import dev.better.airplay.data.device.AndroidDeviceInfo
import dev.better.airplay.data.engine.AndroidVideoSurface
import dev.better.airplay.domain.port.VideoSurface
import dev.better.airplay.domain.usecase.AttachVideoSurface
import dev.better.airplay.domain.usecase.EngineEventFanout
import dev.better.airplay.domain.usecase.VideoHandoverSession
import dev.better.airplay.domain.usecase.VideoSurfaceRouter
import dev.better.airplay.domain.port.EngineListener
import dev.better.airplay.domain.port.VideoPlayer
import dev.better.airplay.data.video.MediaPlayerVideoPlayer
import dev.better.airplay.domain.usecase.SessionMediaPolicy
import dev.better.airplay.presentation.player.PlayerViewModel
import dev.better.airplay.data.discovery.NsdServiceAdvertiser
import dev.better.airplay.data.engine.UxPlayEngine
import dev.better.airplay.data.settings.SharedPrefsSettingsRepository
import dev.better.airplay.data.settings.StableDeviceId
import dev.better.airplay.domain.StatusStore
import dev.better.airplay.domain.port.DeviceInfo
import dev.better.airplay.domain.port.ReceiverController
import dev.better.airplay.domain.port.ReceiverEngine
import dev.better.airplay.domain.port.SettingsRepository
import dev.better.airplay.domain.usecase.BuildReceiverConfig
import dev.better.airplay.domain.usecase.GetSettings
import dev.better.airplay.domain.usecase.RenameReceiver
import dev.better.airplay.domain.usecase.ResolveReceiverName
import dev.better.airplay.domain.usecase.StartReceiver
import dev.better.airplay.domain.usecase.StopReceiver
import dev.better.airplay.domain.usecase.TrackSession
import dev.better.airplay.domain.usecase.UpdateSettings
import dev.better.airplay.platform.ServiceReceiverController
import dev.better.airplay.presentation.advanced.AdvancedViewModel
import dev.better.airplay.presentation.core.ResourceStrings
import dev.better.airplay.presentation.core.Strings
import dev.better.airplay.presentation.home.HomeViewModel
import dev.better.airplay.presentation.rename.RenameViewModel

/**
 * Manual dependency injection. Exactly one instance exists per process (owned by [dev.better.airplay.App]);
 * every dependency below is a process-wide singleton created lazily on first use and never re-created.
 * ViewModels are the only per-screen objects; they are built by the factory functions at the bottom.
 */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    // ---- data (singletons) ----
    val settingsRepository: SettingsRepository by lazy { SharedPrefsSettingsRepository(app) }
    val deviceInfo: DeviceInfo by lazy { AndroidDeviceInfo(app) }
    private val deviceId by lazy { StableDeviceId(app) }
    private val advertiser by lazy { NsdServiceAdvertiser(app) }

    /** One engine per process: the native layer is itself a singleton (one AirPlay server). */
    val engine: ReceiverEngine by lazy {
        UxPlayEngine(advertiser, app.filesDir.resolve("airplay.pem")) { deviceId.get() }
    }

    // ---- domain (singletons) ----
    val statusStore: StatusStore by lazy { StatusStore() }
    val controller: ReceiverController by lazy { ServiceReceiverController(app) }

    val getSettings by lazy { GetSettings(settingsRepository) }
    val resolveReceiverName by lazy { ResolveReceiverName(settingsRepository, deviceInfo) }
    val buildReceiverConfig by lazy { BuildReceiverConfig(settingsRepository, deviceInfo, resolveReceiverName) }
    val startReceiver by lazy { StartReceiver(engine, buildReceiverConfig, statusStore) }
    val stopReceiver by lazy { StopReceiver(engine, statusStore) }
    val trackSession by lazy { TrackSession(statusStore) }
    val renameReceiver by lazy { RenameReceiver(settingsRepository, deviceInfo, resolveReceiverName, controller) }
    val updateSettings by lazy { UpdateSettings(settingsRepository, controller) }
    /** One video player per process for AirPlay video handover (YouTube, Safari, ...). */
    val videoPlayer: VideoPlayer by lazy { MediaPlayerVideoPlayer(app) }
    val videoSurfaceRouter by lazy { VideoSurfaceRouter(engine, videoPlayer) }
    val videoHandoverSession by lazy { VideoHandoverSession(engine, videoPlayer, videoSurfaceRouter, statusStore) }
    /** The engine's single listener: session status first, then the video handover. */
    val engineEvents: EngineListener by lazy { EngineEventFanout(listOf(trackSession, videoHandoverSession)) }
    val attachVideoSurface by lazy { AttachVideoSurface(videoSurfaceRouter) }
    val remoteControl: dev.better.airplay.domain.port.RemoteControl by lazy { dev.better.airplay.data.remote.DacpRemoteControl(app) }
    val sendRemoteCommand by lazy { dev.better.airplay.domain.usecase.SendRemoteCommand(remoteControl, statusStore) }
    val sessionMediaPolicy by lazy { SessionMediaPolicy(engine, settingsRepository) }

    /** Wraps a framework Surface in the domain's opaque handle (keeps Views free of data-layer types). */
    fun wrapSurface(surface: Surface): VideoSurface = AndroidVideoSurface(surface)

    // ---- presentation ----
    private val strings: Strings by lazy { ResourceStrings(app) }

    fun homeViewModel() = HomeViewModel(statusStore, buildReceiverConfig, controller, strings)
    fun advancedViewModel() = AdvancedViewModel(getSettings, updateSettings, buildReceiverConfig, strings)
    fun renameViewModel() = RenameViewModel(renameReceiver, resolveReceiverName, deviceInfo)
    fun playerViewModel() = PlayerViewModel(statusStore, attachVideoSurface, sendRemoteCommand, controller, strings)
}
