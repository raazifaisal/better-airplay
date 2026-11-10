package dev.better.airplay.presentation.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Material-style switch (display only; the row handles input). Thumb grows and slides when on,
 * like M3's switch. Colours are supplied by the row so they invert correctly on focus.
 */
class SwitchIndicatorView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var progress = 0f   // 0 = off, 1 = on
    private var animator: ValueAnimator? = null

    var checked = false
        private set

    /** onTrack/onThumb: colours for "on"; offTrack/offThumb/offOutline for "off". */
    data class Colors(val onTrack: Int, val onThumb: Int, val offTrack: Int, val offThumb: Int, val offOutline: Int)

    var colors = Colors(0, 0, 0, 0, 0)
        set(value) { field = value; invalidate() }

    init {
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO   // the row announces state
    }

    fun setChecked(value: Boolean, animate: Boolean) {
        if (value == checked && animator == null) return
        checked = value
        animator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate || !Motion.enabled) { progress = target; invalidate(); return }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 220
            interpolator = Motion.focusIn
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((52 * density).toInt(), (32 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        val c = colors
        val on = progress >= 0.5f
        rect.set(1 * density, 1 * density, w - 1 * density, h - 1 * density)
        track.color = if (on) c.onTrack else c.offTrack
        canvas.drawRoundRect(rect, r, r, track)
        if (!on) { outline.color = c.offOutline; canvas.drawRoundRect(rect, r, r, outline) }
        // Thumb: 16dp off -> 24dp on, sliding across.
        val thumbR = (8 + 4 * progress) * density
        val cx = r + (w - 2 * r) * progress
        thumb.color = if (on) c.onThumb else c.offThumb
        canvas.drawCircle(cx, h / 2, thumbR, thumb)
    }
}
