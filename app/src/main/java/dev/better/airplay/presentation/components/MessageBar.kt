package dev.better.airplay.presentation.components

import android.view.View
import android.widget.TextView

/** M3 snackbar-style confirmation at the bottom of the screen; never takes focus. */
class MessageBar(private val view: TextView) {
    private val hide = Runnable {
        view.animate().alpha(0f).translationY(24 * view.resources.displayMetrics.density)
            .setDuration(Motion.exitMs()).setInterpolator(Motion.standard)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    init {
        view.isFocusable = false
        view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun show(text: String, durationMs: Long = 3500) {
        view.removeCallbacks(hide)
        view.text = text
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.translationY = 24 * view.resources.displayMetrics.density
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(1f).translationY(0f).setDuration(Motion.enterMs()).setInterpolator(Motion.focusIn).start()
        view.postDelayed(hide, durationMs)
    }
}
