package dev.better.airplay.presentation.core

import android.app.Activity
import android.os.Bundle
import dev.better.airplay.App
import dev.better.airplay.di.AppContainer

/**
 * Base View for screens: creates (or retains across configuration changes) its [ViewModel],
 * binds while started, and clears it when the screen finishes.
 */
abstract class ViewModelActivity<S : Any, A : Any, E : Any, VM : ViewModel<S, A, E>> : Activity() {

    protected lateinit var viewModel: VM
        private set
    private var binding: AutoCloseable? = null

    protected val container: AppContainer get() = (application as App).container

    protected abstract fun createViewModel(container: AppContainer): VM
    protected abstract fun render(state: S)
    protected open fun handle(effect: E) {}

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = (lastNonConfigurationInstance as? VM) ?: createViewModel(container)
    }

    @Deprecated("Framework API; used to retain the ViewModel without AndroidX")
    override fun onRetainNonConfigurationInstance(): Any = viewModel

    override fun onStart() {
        super.onStart()
        binding = viewModel.bind(::render, ::handle)
    }

    override fun onStop() {
        binding?.close()
        binding = null
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) viewModel.onCleared()
        super.onDestroy()
    }
}
