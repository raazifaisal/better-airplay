package dev.better.airplay.presentation.components

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** Sizes its children to the largest box of the given aspect ratio that fits, centred (letterbox/pillarbox). */
class AspectFitLayout(context: Context) : FrameLayout(context) {
    private var aspect = 16f / 9f

    fun setAspect(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val a = width.toFloat() / height
        if (a != aspect) { aspect = a; requestLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) { ch = h; cw = (h * aspect).toInt() }
        val cws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val chs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cws, chs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            val x = (r - l - c.measuredWidth) / 2
            val y = (b - t - c.measuredHeight) / 2
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}
