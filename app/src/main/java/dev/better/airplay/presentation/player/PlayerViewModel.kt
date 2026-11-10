package dev.better.airplay.presentation.player

import android.os.SystemClock
import dev.better.airplay.R
import dev.better.airplay.domain.StatusStore
import dev.better.airplay.domain.model.ReceiverSnapshot
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.port.ReceiverController
import dev.better.airplay.domain.usecase.AttachVideoSurface
import dev.better.airplay.presentation.core.Strings
import dev.better.airplay.presentation.core.ViewModel

class PlayerViewModel(
    store: StatusStore,
    private val attachVideoSurface: AttachVideoSurface,
    private val sendRemoteCommand: dev.better.airplay.domain.usecase.SendRemoteCommand,
    private val controller: ReceiverController,
    private val strings: Strings,
) : ViewModel<PlayerUiState, PlayerAction, PlayerEffect>(PlayerUiState()) {

    private var lastBack = 0L
    private var finished = false
    private val subscription: AutoCloseable = store.observe(::onSnapshot)

    override fun onAction(action: PlayerAction) {
        when (action) {
            is PlayerAction.SurfaceAvailable -> attachVideoSurface(action.surface)
            PlayerAction.SurfaceGone -> attachVideoSurface(null)
            is PlayerAction.Remote ->
                if (!sendRemoteCommand(action.command)) emit(PlayerEffect.Hint(strings.get(R.string.player_no_remote)))
            PlayerAction.BackPressed -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBack < DOUBLE_BACK_MS) {
                    controller.disconnect()
                    finish()
                } else {
                    lastBack = now
                    emit(PlayerEffect.Hint(strings.get(R.string.player_back_hint)))
                }
            }
        }
    }

    override fun onCleared() {
        subscription.close()
        attachVideoSurface(null)
    }

    private fun onSnapshot(snap: ReceiverSnapshot) {
        val st = snap.status
        if (!st.isConnected) { finish(); return }
        val sender = st.currentSender?.name ?: strings.get(R.string.a_device)
        val mode = when (st) {
            is ReceiverStatus.Mirroring -> PlayerMode.MIRRORING
            is ReceiverStatus.PlayingAudio -> PlayerMode.AUDIO
            is ReceiverStatus.PlayingVideo -> PlayerMode.VIDEO
            else -> PlayerMode.CONNECTING
        }
        val np = snap.nowPlaying
        setState {
            PlayerUiState(
                mode = mode,
                senderName = sender,
                caption = when (mode) {
                    PlayerMode.CONNECTING -> strings.get(R.string.status_connecting, sender)
                    PlayerMode.AUDIO -> strings.get(R.string.player_audio_from, sender)
                    else -> strings.get(R.string.status_mirroring, sender)
                },
                title = np?.title?.takeIf { it.isNotBlank() }
                    ?: if (mode == PlayerMode.AUDIO) strings.get(R.string.player_unknown_track) else null,
                artist = np?.artist?.takeIf { it.isNotBlank() },
                album = np?.album?.takeIf { it.isNotBlank() },
                artwork = snap.artwork,
                playing = snap.playing,
                progress = snap.progress,
                pin = snap.pin,
                videoWidth = snap.videoWidth,
                videoHeight = snap.videoHeight,
                gameMode = mode == PlayerMode.MIRRORING,
                controlsEnabled = snap.remote != null,
            )
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        emit(PlayerEffect.Finish)
    }

    private companion object { const val DOUBLE_BACK_MS = 2000L }
}
