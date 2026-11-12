package dev.better.airplay.presentation.components

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.better.airplay.R
import dev.better.airplay.presentation.core.RowAction
import dev.better.airplay.presentation.core.RowModel

/**
 * Renders a list of [RowModel]s into a vertical container. Views are keyed by row id and updated
 * in place, so re-rendering on every state change never steals or loses D-pad focus.
 */
class RowListBinder(
    private val container: LinearLayout,
    private val onAction: (RowAction) -> Unit,
) : SettingRowView.Listener {

    private val views = LinkedHashMap<String, View>()
    private var order: List<String> = emptyList()

    fun render(rows: List<RowModel>) {
        val ids = rows.map { it.id }
        if (ids != order) rebuild(rows) else rows.forEach { bindOne(views.getValue(it.id), it) }
        order = ids
    }

    fun focusFirst() {
        views.values.firstOrNull { it is SettingRowView && it.isFocusable }?.requestFocus()
    }

    private fun rebuild(rows: List<RowModel>) {
        val focusedId = views.entries.firstOrNull { it.value.hasFocus() }?.key
        val reuse = HashMap(views)
        views.clear()
        container.removeAllViews()
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        rows.forEach { row ->
            val view = reuse[row.id]?.takeIf { (it is TextView) == (row is RowModel.Header) }
                ?: if (row is RowModel.Header) {
                    TextView(ctx).apply { setTextAppearance(R.style.Text_SectionHeader) }
                } else {
                    SettingRowView(ctx).also { it.listener = this }
                }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (row is RowModel.Header) {
                lp.topMargin = if (views.isEmpty()) 0 else (28 * d).toInt()
                lp.bottomMargin = (8 * d).toInt()
                lp.marginStart = (24 * d).toInt()
            } else {
                lp.bottomMargin = (8 * d).toInt()
            }
            container.addView(view, lp)
            views[row.id] = view
            bindOne(view, row)
        }
        // Keep focus on the same row if it still exists, else the nearest focusable row.
        (focusedId?.let { views[it] }?.takeIf { it.isFocusable } ?: if (focusedId != null) firstFocusable() else null)
            ?.requestFocus()
    }

    private fun firstFocusable() = views.values.firstOrNull { it is SettingRowView && it.isFocusable }

    private fun bindOne(view: View, row: RowModel) {
        when {
            row is RowModel.Header && view is TextView -> view.text = row.title
            view is SettingRowView -> view.bind(row)
        }
    }

    override fun onRowFocused(id: String) = onAction(RowAction.Focused(id))
    override fun onRowClicked(id: String) = onAction(RowAction.Clicked(id))
    override fun onRowAdjusted(id: String, delta: Int) = onAction(RowAction.Adjusted(id, delta))
}
