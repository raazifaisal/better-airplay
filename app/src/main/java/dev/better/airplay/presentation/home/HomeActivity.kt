package dev.better.airplay.presentation.home

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import dev.better.airplay.R
import dev.better.airplay.di.AppContainer
import dev.better.airplay.presentation.advanced.AdvancedActivity
import dev.better.airplay.presentation.components.RowListBinder
import dev.better.airplay.presentation.components.SplitScreen
import dev.better.airplay.presentation.core.ViewModelActivity
import dev.better.airplay.presentation.rename.RenameDialog

/** View: Home. Renders [HomeUiState] and forwards input; no logic of its own. */
class HomeActivity : ViewModelActivity<HomeUiState, HomeAction, HomeEffect, HomeViewModel>() {

    private lateinit var screen: SplitScreen
    private lateinit var list: RowListBinder
    private var firstRender = true

    override fun createViewModel(container: AppContainer) = container.homeViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split)
        screen = SplitScreen(this)
        screen.artworkIcon.setImageResource(R.drawable.ic_airplay)
        list = RowListBinder(screen.rows) { viewModel.onAction(HomeAction.Row(it)) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()   // settings may have changed in Advanced
    }

    override fun render(state: HomeUiState) {
        screen.title.text = state.receiverName
        screen.subtitle.text = state.statusText
        screen.statusDot.visibility = View.VISIBLE
        screen.statusDot.backgroundTintList = ColorStateList.valueOf(getColor(state.statusTone.colorRes()))
        screen.pin.visibility = if (state.pin != null) View.VISIBLE else View.GONE
        screen.pin.text = state.pin
        screen.pin.contentDescription = state.pin?.let { getString(R.string.notif_pin, it.toCharArray().joinToString(" ")) }
        screen.setDescription(state.description)
        list.render(state.rows)
        if (firstRender && state.rows.isNotEmpty()) { list.focusFirst(); firstRender = false }
    }

    override fun handle(effect: HomeEffect) {
        when (effect) {
            HomeEffect.OpenRename -> RenameDialog(this, container.renameViewModel()) { renamedTo ->
                renamedTo?.let { screen.message.show(getString(R.string.msg_renamed, it)) }
            }.show()
            HomeEffect.OpenAdvanced -> startActivity(Intent(this, AdvancedActivity::class.java))
            is HomeEffect.Message -> screen.message.show(effect.text)
        }
    }

    private fun StatusTone.colorRes() = when (this) {
        StatusTone.IDLE -> R.color.status_idle
        StatusTone.BUSY -> R.color.status_busy
        StatusTone.READY -> R.color.status_ready
        StatusTone.ACTIVE -> R.color.status_active
        StatusTone.ERROR -> R.color.status_error
    }
}
