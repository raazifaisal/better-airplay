package dev.better.airplay.presentation.advanced

import android.os.Bundle
import android.view.View
import dev.better.airplay.R
import dev.better.airplay.di.AppContainer
import dev.better.airplay.presentation.components.RowListBinder
import dev.better.airplay.presentation.components.SplitScreen
import dev.better.airplay.presentation.core.ViewModelActivity

/** View: Advanced settings. Renders [AdvancedUiState] and forwards input; no logic of its own. */
class AdvancedActivity : ViewModelActivity<AdvancedUiState, AdvancedAction, AdvancedEffect, AdvancedViewModel>() {

    private lateinit var screen: SplitScreen
    private lateinit var list: RowListBinder
    private var firstRender = true

    override fun createViewModel(container: AppContainer) = container.advancedViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split)
        screen = SplitScreen(this)
        screen.artworkIcon.setImageResource(R.drawable.ic_wrench)
        screen.title.setText(R.string.adv_title)
        screen.statusDot.visibility = View.GONE
        list = RowListBinder(screen.rows) { viewModel.onAction(AdvancedAction.Row(it)) }
    }

    override fun render(state: AdvancedUiState) {
        screen.subtitle.text = state.effectiveSummary
        screen.setDescription(state.description)
        list.render(state.rows)
        if (firstRender && state.rows.isNotEmpty()) { list.focusFirst(); firstRender = false }
    }

    override fun handle(effect: AdvancedEffect) {
        when (effect) {
            is AdvancedEffect.Message -> screen.message.show(effect.text)
        }
    }
}
