package dev.better.airplay.presentation.rename

/** Model: the rename dialog. */
data class RenameUiState(
    val text: String = "",
    /** The TV's own name, used when the field is cleared or "Use TV Name" is chosen. */
    val tvName: String = "",
    /** The sanitized name that would be advertised if saved now (shown as a live preview). */
    val preview: String = "",
    val canSave: Boolean = false,
)

sealed interface RenameAction {
    data class TextChanged(val text: String) : RenameAction
    data object Save : RenameAction
    data object UseTvName : RenameAction
    data object Cancel : RenameAction
}

sealed interface RenameEffect {
    data class Dismiss(val renamed: Boolean) : RenameEffect
}
