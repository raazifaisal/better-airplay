package dev.better.airplay.domain.usecase

import dev.better.airplay.domain.StatusStore
import dev.better.airplay.domain.model.VideoError
import dev.better.airplay.domain.model.VideoPlaybackInfo
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.port.EngineListener
import dev.better.airplay.domain.port.ReceiverEngine
import dev.better.airplay.domain.port.VideoPlayer
import dev.better.airplay.domain.port.VideoSurface

/** Delivers each engine event to several listeners, in order (the engine has a single listener slot). */
class EngineEventFanout(private val listeners: List<EngineListener>) : EngineListener {
    override fun onEngineEvent(event: EngineEvent) = listeners.forEach { it.onEngineEvent(event) }
}

/**
 * The player screen has one Surface; it belongs to the mirroring decoder normally and to the
 * handover video player while a handed-over video plays. A Surface takes one producer at a time,
 * so the old owner always lets go before the new owner gets it.
 */
class VideoSurfaceRouter(private val engine: ReceiverEngine, private val player: VideoPlayer) {
    private var surface: VideoSurface? = null
    private var handover = false

    @Synchronized
    fun attach(surface: VideoSurface?) {
        this.surface = surface
        if (handover) player.setSurface(surface) else engine.setVideoSurface(surface)
    }

    @Synchronized
    fun setHandoverActive(active: Boolean) {
        if (active == handover) return
        handover = active
        if (active) {
            engine.setVideoSurface(null)
            player.setSurface(surface)
        } else {
            player.setSurface(null)
            engine.setVideoSurface(surface)
        }
    }
}

/**
 * Runs an AirPlay video handover: the phone's play / scrub / pause / stop drive the [VideoPlayer],
 * and the player's progress goes back to the engine so the phone's scrubber follows the TV.
 * Status (PlayingVideo / Ready) is owned by [TrackSession]; this only adds the video size.
 */
class VideoHandoverSession(
    private val engine: ReceiverEngine,
    private val player: VideoPlayer,
    private val router: VideoSurfaceRouter,
    private val store: StatusStore,
) : EngineListener, VideoPlayer.Listener {

    @Volatile private var active = false

    init {
        player.listener = this
    }

    override fun onEngineEvent(event: EngineEvent) {
        when (event) {
            is EngineEvent.VideoHandoverStarted -> start(event.url, event.startSec)
            is EngineEvent.VideoHandoverSeek -> if (active) player.seek(event.positionSec)
            is EngineEvent.VideoHandoverRate -> if (active) player.setPlaying(event.rate > 0f)
            EngineEvent.VideoHandoverStopped, EngineEvent.SessionEnded -> end(EngineReport.IDLE)
            else -> Unit
        }
    }

    @Synchronized
    private fun start(url: String, startSec: Double) {
        active = true
        engine.updatePlaybackInfo(VideoPlaybackInfo.IDLE)   // never report the previous video's progress
        router.setHandoverActive(true)
        player.play(url, startSec)
    }

    private enum class EngineReport { IDLE, ENDED }

    @Synchronized
    private fun end(report: EngineReport) {
        if (!active) return
        active = false
        player.stop()
        router.setHandoverActive(false)
        engine.updatePlaybackInfo(if (report == EngineReport.ENDED) VideoPlaybackInfo.ENDED else VideoPlaybackInfo.IDLE)
    }

    // ---- VideoPlayer.Listener ----

    override fun onInfo(info: VideoPlaybackInfo) {
        if (active) engine.updatePlaybackInfo(info)
    }

    override fun onVideoSize(width: Int, height: Int) {
        if (active && width > 0 && height > 0) store.update { it.copy(videoWidth = width, videoHeight = height) }
    }

    /** The stream can't be played here: report it as ended so the phone is released at once. */
    override fun onError(error: VideoError) = end(EngineReport.ENDED)
}
