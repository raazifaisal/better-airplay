package dev.better.airplay.presentation.rename

import dev.better.airplay.domain.model.ReceiverName
import dev.better.airplay.domain.port.DeviceInfo
import dev.better.airplay.domain.usecase.RenameReceiver
import dev.better.airplay.domain.usecase.ResolveReceiverName
import dev.better.airplay.presentation.core.ViewModel

class RenameViewModel(
    private val renameReceiver: RenameReceiver,
    resolveName: ResolveReceiverName,
    device: DeviceInfo,
) : ViewModel<RenameUiState, RenameAction, RenameEffect>(RenameUiState()) {

    private val tvName = ReceiverName.sanitize(device.deviceName())
    private val current = resolveName()

    init {
        setState { derive(current) }
    }

    override fun onAction(action: RenameAction) {
        when (action) {
            is RenameAction.TextChanged -> setState { derive(action.text) }
            RenameAction.Save -> {
                val s = state
                if (s.canSave) emit(RenameEffect.Dismiss(renameReceiver(s.text)))
            }
            RenameAction.UseTvName -> emit(RenameEffect.Dismiss(renameReceiver(null)))
            RenameAction.Cancel -> emit(RenameEffect.Dismiss(false))
        }
    }

    private fun derive(text: String): RenameUiState {
        val preview = ReceiverName.sanitize(text).ifEmpty { tvName }
        return RenameUiState(
            text = text,
            tvName = tvName,
            preview = preview,
            canSave = preview != current,
        )
    }
}
