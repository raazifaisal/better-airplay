package dev.better.airplay.presentation.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Thin, rounded tvOS-style progress line. Display only. */
class ProgressLineView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    var fraction = 0f
        set(value) { val v = value.coerceIn(0f, 1f); if (v != field) { field = v; invalidate() } }

    var accent: Int
        get() = fill.color
        set(value) { fill.color = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (4 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat(); val r = h / 2
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            rect.set(0f, 0f, maxOf(h, width * fraction), h)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}
