package dev.better.airplay.presentation.home

import dev.better.airplay.R
import dev.better.airplay.domain.StatusStore
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverSnapshot
import dev.better.airplay.domain.model.ReceiverStatus
import dev.better.airplay.domain.port.ReceiverController
import dev.better.airplay.domain.usecase.BuildReceiverConfig
import dev.better.airplay.presentation.core.RowAction
import dev.better.airplay.presentation.core.RowModel
import dev.better.airplay.presentation.core.Strings
import dev.better.airplay.presentation.core.ViewModel
import dev.better.airplay.presentation.core.description

class HomeViewModel(
    private val store: StatusStore,
    private val buildConfig: BuildReceiverConfig,
    private val controller: ReceiverController,
    private val strings: Strings,
) : ViewModel<HomeUiState, HomeAction, HomeEffect>(HomeUiState()) {

    private var focusedRow: String? = null
    private var snapshot = ReceiverSnapshot()
    private val subscription: AutoCloseable

    init {
        controller.ensureRunning()
        subscription = store.observe { snap -> snapshot = snap; publish() }
    }

    override fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.Row -> when (val a = action.action) {
                is RowAction.Focused -> { focusedRow = a.id; publish() }
                is RowAction.Clicked -> click(a.id)
                is RowAction.Adjusted -> Unit
            }
        }
    }

    /** Re-read settings (e.g. after returning from Advanced) so the summary stays current. */
    fun refresh() = publish()

    override fun onCleared() = subscription.close()

    private fun click(id: String) = when (id) {
        HomeRows.NAME -> emit(HomeEffect.OpenRename)
        HomeRows.ADVANCED -> emit(HomeEffect.OpenAdvanced)
        HomeRows.DISCONNECT -> controller.disconnect()
        HomeRows.RESTART -> {
            controller.restart()
            emit(HomeEffect.Message(strings.get(R.string.msg_restarting)))
        }
        else -> Unit
    }

    private fun publish() {
        val snap = snapshot
        val status = snap.status
        val sender = status.currentSender?.name ?: strings.get(R.string.a_device)
        val config = buildConfig()
        val rows = buildList {
            add(RowModel.Navigation(HomeRows.NAME, strings.get(R.string.row_name), snap.name.ifEmpty { config.name },
                strings.get(R.string.row_name_desc)))
            if (status.isConnected) {
                add(RowModel.Action(HomeRows.DISCONNECT, strings.get(R.string.row_disconnect), sender,
                    strings.get(R.string.row_disconnect_desc, sender)))
            }
            add(RowModel.Navigation(HomeRows.ADVANCED, strings.get(R.string.row_advanced), summary(config),
                strings.get(R.string.row_advanced_desc)))
            add(RowModel.Action(HomeRows.RESTART, strings.get(R.string.row_restart), null,
                strings.get(R.string.row_restart_desc)))
        }
        val focused = rows.firstOrNull { it.id == focusedRow }
        setState {
            HomeUiState(
                receiverName = snap.name.ifEmpty { config.name },
                statusText = statusText(status, sender),
                statusTone = tone(status),
                description = focused?.description ?: connectHint(status, snap.name.ifEmpty { config.name }),
                pin = snap.pin,
                rows = rows,
            )
        }
    }

    private fun statusText(status: ReceiverStatus, sender: String): String = when (status) {
        ReceiverStatus.Stopped -> strings.get(R.string.status_stopped)
        ReceiverStatus.Starting -> strings.get(R.string.status_starting)
        is ReceiverStatus.Ready -> strings.get(R.string.status_ready)
        is ReceiverStatus.Connecting -> strings.get(R.string.status_connecting, sender)
        is ReceiverStatus.Mirroring -> strings.get(R.string.status_mirroring, sender) +
            (status.format?.label?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
        is ReceiverStatus.PlayingVideo -> strings.get(R.string.status_video, sender)
        is ReceiverStatus.PlayingAudio -> strings.get(R.string.status_audio, sender)
        is ReceiverStatus.Failed -> strings.get(R.string.status_error, status.code)
    }

    private fun tone(status: ReceiverStatus) = when (status) {
        ReceiverStatus.Stopped -> StatusTone.IDLE
        ReceiverStatus.Starting, is ReceiverStatus.Connecting -> StatusTone.BUSY
        is ReceiverStatus.Ready -> StatusTone.READY
        is ReceiverStatus.Mirroring, is ReceiverStatus.PlayingVideo, is ReceiverStatus.PlayingAudio -> StatusTone.ACTIVE
        is ReceiverStatus.Failed -> StatusTone.ERROR
    }

    private fun connectHint(status: ReceiverStatus, name: String) =
        if (status.isConnected) strings.get(R.string.hint_connected) else strings.get(R.string.hint_ready, name)

    /** "Up to 4K · HEVC · 60 Hz" */
    private fun summary(c: ReceiverConfig): String {
        val res = when {
            c.width >= 3800 -> "4K"; c.width >= 2500 -> "1440p"; c.width >= 1900 -> "1080p"; else -> "${c.height}p"
        }
        return strings.get(R.string.summary_video, res, if (c.hevc) "HEVC" else "H.264", c.refreshHz)
    }
}
