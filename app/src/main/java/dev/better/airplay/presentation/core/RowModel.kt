package dev.better.airplay.presentation.core

/**
 * Display model for one item in a tvOS-style settings list (right column).
 * [description] is shown in the left column while the row has focus.
 */
sealed interface RowModel {
    val id: String

    data class Header(override val id: String, val title: String) : RowModel

    /** Opens another screen or dialog. Shows the current value and a chevron. */
    data class Navigation(
        override val id: String,
        val title: String,
        val value: String?,
        val description: String,
    ) : RowModel

    /** Runs a command. */
    data class Action(
        override val id: String,
        val title: String,
        val value: String?,
        val description: String,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
    ) : RowModel

    /** One of several options; ◀ ▶ cycles in place, OK steps forward. */
    data class Choice(
        override val id: String,
        val title: String,
        val options: List<String>,
        val selected: Int,
        val description: String,
    ) : RowModel {
        val value get() = options.getOrElse(selected) { "" }
    }

    data class Toggle(
        override val id: String,
        val title: String,
        val checked: Boolean,
        val description: String,
    ) : RowModel

    /** Numeric value adjusted with ◀ ▶; [valueLabel] is pre-formatted (e.g. "+40 ms"). */
    data class Stepper(
        override val id: String,
        val title: String,
        val valueLabel: String,
        val description: String,
        val atMin: Boolean,
        val atMax: Boolean,
    ) : RowModel
}

/** Row intents shared by list screens. */
sealed interface RowAction {
    data class Focused(val id: String) : RowAction
    data class Clicked(val id: String) : RowAction
    /** ◀ = -1, ▶ = +1 */
    data class Adjusted(val id: String, val delta: Int) : RowAction
}

val RowModel.description: String?
    get() = when (this) {
        is RowModel.Header -> null
        is RowModel.Navigation -> description
        is RowModel.Action -> description
        is RowModel.Choice -> description
        is RowModel.Toggle -> description
        is RowModel.Stepper -> description
    }
