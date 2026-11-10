package dev.better.airplay.presentation.components

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator

/**
 * Motion tokens (Material 3 Expressive-style springs approximated with framework interpolators).
 * Enter is springy and slightly slower than exit; everything collapses to 0 ms when the user
 * has turned animations off (Settings → Accessibility → Remove animations).
 */
object Motion {
    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    /** Focus gained: spatial spring with a little overshoot. */
    val focusIn: Interpolator = OvershootInterpolator(1.6f)
    /** Focus lost / exits: quick settle. */
    val focusOut: Interpolator = DecelerateInterpolator(1.5f)
    val standard: Interpolator = DecelerateInterpolator(2f)

    fun focusInMs() = if (enabled) 260L else 0L
    fun focusOutMs() = if (enabled) 160L else 0L
    fun enterMs() = if (enabled) 320L else 0L
    fun exitMs() = if (enabled) 200L else 0L
}
