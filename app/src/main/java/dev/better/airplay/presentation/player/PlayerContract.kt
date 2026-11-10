package dev.better.airplay.presentation.player

import dev.better.airplay.domain.model.Artwork
import dev.better.airplay.domain.model.PlaybackProgress
import dev.better.airplay.domain.port.VideoSurface

enum class PlayerMode { CONNECTING, MIRRORING, AUDIO, VIDEO }

/** Model: the full-screen player (mirroring, music Now Playing, connecting). */
data class PlayerUiState(
    val mode: PlayerMode = PlayerMode.CONNECTING,
    val senderName: String = "",
    /** Connecting caption, or the source line under the track info ("AirPlay from Raazi's iPhone"). */
    val caption: String = "",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: Artwork? = null,
    /** False while the sender has paused (artwork shrinks, motion and progress stop). */
    val playing: Boolean = true,
    /** Raw progress; the View samples it against the clock so the bar moves smoothly. */
    val progress: PlaybackProgress? = null,
    val pin: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** Ask the TV for its low-latency "game" picture mode (ALLM) while mirroring. */
    val gameMode: Boolean = false,
    /** The sender accepts play/pause/next/previous from the TV. */
    val controlsEnabled: Boolean = false,
)

sealed interface PlayerAction {
    data class SurfaceAvailable(val surface: VideoSurface) : PlayerAction
    data object SurfaceGone : PlayerAction
    data object BackPressed : PlayerAction
    data class Remote(val command: dev.better.airplay.domain.model.RemoteCommand) : PlayerAction
}

sealed interface PlayerEffect {
    data class Hint(val text: String) : PlayerEffect
    data object Finish : PlayerEffect
}
