package dev.better.airplay.presentation.rename

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import dev.better.airplay.R

/**
 * View: rename dialog. Renders [RenameUiState] and forwards input. [onDone] receives the new
 * advertised name, or null if nothing changed.
 */
class RenameDialog(
    private val activity: Activity,
    private val viewModel: RenameViewModel,
    private val onDone: (String?) -> Unit,
) : Dialog(activity, R.style.RenameDialogTheme) {

    private lateinit var field: EditText
    private lateinit var help: TextView
    private lateinit var preview: TextView
    private lateinit var save: Button
    private var binding: AutoCloseable? = null
    private var updatingField = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_rename)
        field = findViewById(R.id.field)
        help = findViewById(R.id.help)
        preview = findViewById(R.id.preview)
        save = findViewById(R.id.save)

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!updatingField) viewModel.onAction(RenameAction.TextChanged(s?.toString().orEmpty()))
            }
        })
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { viewModel.onAction(RenameAction.Save); true } else false
        }
        save.setOnClickListener { viewModel.onAction(RenameAction.Save) }
        findViewById<Button>(R.id.use_tv_name).setOnClickListener { viewModel.onAction(RenameAction.UseTvName) }
        findViewById<Button>(R.id.cancel).setOnClickListener { viewModel.onAction(RenameAction.Cancel) }
        setOnCancelListener { viewModel.onAction(RenameAction.Cancel) }
    }

    override fun onStart() {
        super.onStart()
        binding = viewModel.bind(::render, ::handle)
        field.requestFocus()
        field.selectAll()
    }

    override fun onStop() {
        binding?.close()
        binding = null
        super.onStop()
    }

    private fun render(state: RenameUiState) {
        if (field.text.toString() != state.text) {
            updatingField = true
            field.setText(state.text)
            field.setSelection(state.text.length)
            updatingField = false
        }
        help.text = activity.getString(R.string.rename_help, state.tvName)
        preview.text = activity.getString(R.string.rename_preview, state.preview)
        save.isEnabled = state.canSave
        save.alpha = if (state.canSave) 1f else 0.38f
    }

    private fun handle(effect: RenameEffect) {
        when (effect) {
            is RenameEffect.Dismiss -> {
                viewModel.onCleared()
                dismiss()
                onDone(if (effect.renamed) viewModel.state.preview else null)
            }
        }
    }
}
