package dev.better.airplay.presentation.core

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal MVVM base (no AndroidX): holds immutable [State], accepts [Action]s from the View,
 * emits one-off [Effect]s (navigation, toasts). State and effects are delivered on the main thread.
 */
abstract class ViewModel<State : Any, Action : Any, Effect : Any>(initial: State) {

    private val main = Handler(Looper.getMainLooper())
    private val stateObservers = CopyOnWriteArrayList<(State) -> Unit>()
    private val effectObservers = CopyOnWriteArrayList<(Effect) -> Unit>()

    @Volatile var state: State = initial
        private set

    /** Called by the View for every user intent. */
    abstract fun onAction(action: Action)

    /** Release subscriptions; called when the owning View is finally destroyed. */
    open fun onCleared() {}

    protected fun setState(reducer: (State) -> State) {
        main.post {
            val next = reducer(state)
            if (next != state) {
                state = next
                stateObservers.forEach { it(next) }
            }
        }
    }

    protected fun emit(effect: Effect) {
        main.post { effectObservers.forEach { it(effect) } }
    }

    fun bind(onState: (State) -> Unit, onEffect: (Effect) -> Unit = {}): AutoCloseable {
        stateObservers += onState
        effectObservers += onEffect
        onState(state)
        return AutoCloseable {
            stateObservers -= onState
            effectObservers -= onEffect
        }
    }
}
