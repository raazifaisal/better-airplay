package dev.better.airplay.presentation.advanced

import dev.better.airplay.presentation.core.RowAction
import dev.better.airplay.presentation.core.RowModel

/** Model: the Advanced (power user) settings screen. */
data class AdvancedUiState(
    /** What the receiver is actually advertising right now, e.g. "4K · HEVC · 60 Hz · PIN off". */
    val effectiveSummary: String = "",
    /** Left-column help for the focused row, including the ◀ ▶ hint where relevant. */
    val description: String = "",
    val rows: List<RowModel> = emptyList(),
)

sealed interface AdvancedAction {
    data class Row(val action: RowAction) : AdvancedAction
}

sealed interface AdvancedEffect {
    /** Short confirmation, e.g. "Receiver restarted. Reconnect to use the new setting." */
    data class Message(val text: String) : AdvancedEffect
}
