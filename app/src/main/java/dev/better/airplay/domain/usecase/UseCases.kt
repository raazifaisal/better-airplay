package dev.better.airplay.domain.usecase

import dev.better.airplay.domain.StatusStore
import dev.better.airplay.domain.model.CodecPreference
import dev.better.airplay.domain.model.AccessMode
import dev.better.airplay.domain.model.ArtworkCache
import dev.better.airplay.domain.model.NowPlaying
import dev.better.airplay.domain.model.ReceiverSnapshot
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverName
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.model.RefreshRate
import dev.better.airplay.domain.model.Resolution
import dev.better.airplay.domain.port.DeviceInfo
import dev.better.airplay.domain.port.EngineEvent
import dev.better.airplay.domain.port.EngineListener
import dev.better.airplay.domain.port.ReceiverController
import dev.better.airplay.domain.port.ReceiverEngine
import dev.better.airplay.domain.port.SettingsRepository
import dev.better.airplay.domain.port.StartResult

class GetSettings(private val settings: SettingsRepository) {
    operator fun invoke(): ReceiverSettings = settings.load()
}

/** The name advertised to senders: the user's custom name, else the TV's own name. */
class ResolveReceiverName(private val settings: SettingsRepository, private val device: DeviceInfo) {
    operator fun invoke(): String =
        settings.load().customName?.takeIf { it.isNotBlank() }
            ?: ReceiverName.sanitize(device.deviceName()).ifEmpty { "Android TV" }
}

/** Turns user settings + TV capabilities into a concrete engine configuration (resolves AUTO values). */
class BuildReceiverConfig(
    private val settings: SettingsRepository,
    private val device: DeviceInfo,
    private val resolveName: ResolveReceiverName,
) {
    operator fun invoke(): ReceiverConfig = preview(settings.load())

    /** Resolve [s] without saving it, so settings screens can show the effective result of a draft. */
    fun preview(s: ReceiverSettings): ReceiverConfig {
        val caps = device.displayCapabilities()
        val hevc = s.codec == CodecPreference.AUTO && caps.hevc4k60
        val (w, h) = when {
            s.resolution != Resolution.AUTO -> s.resolution.width to s.resolution.height
            hevc -> 3840 to 2160
            else -> minOf(caps.maxWidth, 1920) to minOf(caps.maxHeight, 1080)
        }.let { (w, h) ->
            // Without HEVC, senders cap mirroring at 1080p-class anyway; don't advertise more.
            if (!hevc && w > 1920) 1920 to 1080 else w to h
        }
        val refresh = if (s.refreshRate == RefreshRate.AUTO) caps.refreshHz.coerceIn(24, 60) else s.refreshRate.hz
        return ReceiverConfig(
            name = resolveName(),
            width = w,
            height = h,
            refreshHz = refresh,
            maxFps = minOf(s.frameRateCap.fps, refresh),
            hevc = hevc,
            // Off until the HLS player ships (Phase 6): advertising it makes YouTube hand video to a
            // player we do not have yet. With it off, iOS falls back to Screen Mirroring for video.
            videoHandover = false,
            overscanned = s.overscanCompensation,
            audioDelayMicros = ((BASE_AUDIO_DELAY_MS + s.audioDelayMs).coerceAtLeast(0)) * 1000,
            requirePin = s.access == AccessMode.ONSCREEN_PIN,
        )
    }

    companion object {
        /** UxPlay's default audio buffering; the user's A/V offset is applied on top. */
        const val BASE_AUDIO_DELAY_MS = 250
    }
}

class StartReceiver(
    private val engine: ReceiverEngine,
    private val buildConfig: BuildReceiverConfig,
    private val store: StatusStore,
) {
    operator fun invoke(): StartResult {
        val config = buildConfig()
        store.update { it.copy(name = config.name, status = ReceiverStatus.Starting, pin = null) }
        val result = engine.start(config)
        store.update {
            when (result) {
                is StartResult.Started -> it.copy(status = ReceiverStatus.Ready(result.port))
                is StartResult.Failed -> it.copy(status = ReceiverStatus.Failed(result.code))
            }
        }
        return result
    }
}

class StopReceiver(private val engine: ReceiverEngine, private val store: StatusStore) {
    operator fun invoke() {
        engine.stop()
        store.update { it.copy(status = ReceiverStatus.Stopped, pin = null) }
    }
}

/**
 * Translates engine events into receiver status. Owns the session's Now Playing state: metadata and
 * artwork survive pauses (iOS tears the audio stream down on pause), and artwork is cached per track.
 *
 * Ordering, as observed from real iPhones: artwork arrives BEFORE the metadata of its track, and iOS then
 * sends a second, shorter metadata for the same song. So artwork is attributed to the next metadata,
 * and partial metadata for the same title is merged rather than treated as a new track.
 */
class TrackSession(
    private val store: StatusStore,
    private val artworkCache: ArtworkCache = ArtworkCache(),
) : EngineListener {
    private var trackKey: String? = null
    /** Artwork received since the last metadata: it belongs to the metadata that comes next. */
    private var pendingArtwork = false

    private fun sameSong(a: NowPlaying?, b: NowPlaying): Boolean {
        if (a == null || a.title.isNullOrBlank() || b.title.isNullOrBlank()) return false
        if (!a.title.equals(b.title, ignoreCase = true)) return false
        return a.artist.isNullOrBlank() || b.artist.isNullOrBlank() || a.artist.equals(b.artist, ignoreCase = true)
    }

    private fun merge(old: NowPlaying, new: NowPlaying) =
        NowPlaying(new.title ?: old.title, new.artist ?: old.artist, new.album ?: old.album)

    private fun onMetadata(snap: ReceiverSnapshot, incoming: NowPlaying): ReceiverSnapshot {
        val same = sameSong(snap.nowPlaying, incoming)
        val np = if (same) merge(snap.nowPlaying!!, incoming) else incoming
        val key = ArtworkCache.keyOf(np)
        val art = when {
            pendingArtwork && snap.artwork != null -> snap.artwork.also { artworkCache.put(key, it) }
            same -> snap.artwork ?: artworkCache.get(key)
            else -> artworkCache.get(key)
        }
        pendingArtwork = false
        trackKey = key
        return snap.copy(nowPlaying = np, artwork = art)
    }

    override fun onEngineEvent(event: EngineEvent) {
        store.update { snap ->
            val st = snap.status
            val port = (st as? ReceiverStatus.Ready)?.port
            when (event) {
                is EngineEvent.SenderConnecting -> snap.copy(status = ReceiverStatus.Connecting(event.sender))
                is EngineEvent.MirroringStarted -> snap.copy(status = ReceiverStatus.Mirroring(st.currentSender, event.format), pin = null)
                EngineEvent.VideoPlaybackStarted -> snap.copy(status = ReceiverStatus.PlayingVideo(st.currentSender), pin = null)
                EngineEvent.AudioStreamStarted -> when (st) {
                    is ReceiverStatus.Mirroring, is ReceiverStatus.PlayingVideo -> snap
                    is ReceiverStatus.PlayingAudio -> snap.copy(playing = true)   // resume after pause
                    else -> snap.copy(status = ReceiverStatus.PlayingAudio(st.currentSender), pin = null, playing = true)
                }
                is EngineEvent.AudioStreamStopped ->
                    if (st is ReceiverStatus.PlayingAudio) snap.copy(playing = false, progress = snap.progress?.pausedAt(event.nowMs))
                    else snap
                is EngineEvent.PinRequested -> snap.copy(pin = event.pin)
                is EngineEvent.NowPlayingChanged -> onMetadata(snap, event.nowPlaying)
                is EngineEvent.ArtworkChanged -> {
                    pendingArtwork = event.artwork != null
                    snap.copy(artwork = event.artwork ?: snap.artwork)
                }
                is EngineEvent.VideoSizeChanged -> snap.copy(videoWidth = event.width, videoHeight = event.height)
                is EngineEvent.RemoteControlAvailable -> snap.copy(remote = event.target)
                is EngineEvent.ProgressChanged -> if (event.progress.isValid) snap.copy(progress = event.progress) else snap
                is EngineEvent.Playhead -> snap.progress?.takeIf { it.contains(event.rtp) }
                    ?.let { snap.copy(progress = it.withPlayhead(event.rtp, event.nowMs)) } ?: snap
                is EngineEvent.AudioFlushed -> snap.copy(
                    progress = snap.progress?.pausedAt(event.nowMs),
                    playing = if (st is ReceiverStatus.PlayingAudio) false else snap.playing,
                )
                is EngineEvent.AudioResumed ->
                    if (st is ReceiverStatus.PlayingAudio) snap.copy(playing = true, progress = snap.progress?.resumedAt(event.nowMs))
                    else snap
                EngineEvent.VideoPlaybackStopped, EngineEvent.SessionEnded -> {
                    trackKey = null
                    pendingArtwork = false
                    snap.copy(status = ReceiverStatus.Ready(port ?: lastPort), pin = null, playing = true, remote = null,
                        nowPlaying = null, artwork = null, videoWidth = 0, videoHeight = 0, progress = null)
                }
            }.also { if (port != null) lastPort = port }
        }
    }

    @Volatile private var lastPort = 0
}

/**
 * Sends a playback command to the current sender. Play/pause flips the on-screen state at once
 * (optimistic) so the TV responds instantly; the sender's audio flush/resume then confirms it.
 * Returns false when the sender hasn't announced a remote-control endpoint.
 */
class SendRemoteCommand(
    private val remote: dev.better.airplay.domain.port.RemoteControl,
    private val store: StatusStore,
) {
    operator fun invoke(command: dev.better.airplay.domain.model.RemoteCommand): Boolean {
        val target = store.snapshot.remote ?: return false
        remote.send(target, command)
        if (command == dev.better.airplay.domain.model.RemoteCommand.PLAY_PAUSE) {
            store.update { it.copy(playing = !it.playing) }
        }
        return true
    }
}

/** Connects (or disconnects, with null) the screen the mirrored picture is drawn on. */
class AttachVideoSurface(private val engine: ReceiverEngine) {
    operator fun invoke(surface: dev.better.airplay.domain.port.VideoSurface?) = engine.setVideoSurface(surface)
}

/**
 * Applies per-session media settings to the engine as the session changes:
 * "Play Audio While Mirroring" only mutes mirroring sessions (never music), and the
 * latency mode picks the decoder's low-latency path. Observe [StatusStore] with [onStatus].
 */
class SessionMediaPolicy(private val engine: ReceiverEngine, private val settings: SettingsRepository) {
    fun onStatus(snapshot: dev.better.airplay.domain.model.ReceiverSnapshot) {
        val s = settings.load()
        engine.setAudioMuted(snapshot.status is ReceiverStatus.Mirroring && !s.mirrorAudio)
        engine.setLowLatency(s.latencyMode != dev.better.airplay.domain.model.LatencyMode.SMOOTH)
    }
}

/** Rename; null/blank or the TV's own name means "follow the TV name". Returns true if the advertised name changed. */
class RenameReceiver(
    private val settings: SettingsRepository,
    private val device: DeviceInfo,
    private val resolveName: ResolveReceiverName,
    private val controller: ReceiverController,
) {
    operator fun invoke(requested: String?): Boolean {
        val before = resolveName()
        val clean = requested?.let(ReceiverName::sanitize)
            ?.takeIf { it.isNotEmpty() && it != ReceiverName.sanitize(device.deviceName()) }
        settings.save(settings.load().copy(customName = clean))
        val changed = resolveName() != before
        if (changed) controller.restart()
        return changed
    }
}

/** Applies a settings change; restarts the receiver only when the change affects what is advertised. */
class UpdateSettings(private val settings: SettingsRepository, private val controller: ReceiverController) {
    enum class Effect { NONE, APPLIED_NOW, RESTARTED }

    operator fun invoke(transform: (ReceiverSettings) -> ReceiverSettings): Effect {
        val before = settings.load()
        val after = transform(before)
        if (after == before) return Effect.NONE
        settings.save(after)
        return if (after.engineProjection() != before.engineProjection()) {
            controller.restart(); Effect.RESTARTED
        } else Effect.APPLIED_NOW
    }
}
