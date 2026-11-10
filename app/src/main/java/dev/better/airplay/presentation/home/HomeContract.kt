package dev.better.airplay.presentation.home

import dev.better.airplay.presentation.core.RowAction
import dev.better.airplay.presentation.core.RowModel

/** Status colour role; the View maps it to theme colours (always paired with text, never colour alone). */
enum class StatusTone { IDLE, BUSY, READY, ACTIVE, ERROR }

/** Model: everything the Home View renders. */
data class HomeUiState(
    val receiverName: String = "",
    val statusText: String = "",
    val statusTone: StatusTone = StatusTone.IDLE,
    /** Left-column text: the focused row's description, or a how-to-connect hint. */
    val description: String = "",
    /** Shown large in the left column while a new device is pairing. */
    val pin: String? = null,
    val rows: List<RowModel> = emptyList(),
)

sealed interface HomeAction {
    data class Row(val action: RowAction) : HomeAction
}

sealed interface HomeEffect {
    data object OpenRename : HomeEffect
    data object OpenAdvanced : HomeEffect
    data class Message(val text: String) : HomeEffect
}

object HomeRows {
    const val NAME = "name"
    const val DISCONNECT = "disconnect"
    const val ADVANCED = "advanced"
    const val RESTART = "restart"
}
