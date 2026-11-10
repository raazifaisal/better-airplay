package dev.better.airplay.presentation.components

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.better.airplay.R
import dev.better.airplay.presentation.core.RowModel

/**
 * One row of a tvOS-style settings list, styled with Material 3 Expressive:
 * at rest it's flat text on the surface; on focus it lifts, fills with the primary colour and
 * morphs from a rounded rectangle to a full pill. ◀ ▶ adjust Choice/Toggle/Stepper rows in place.
 */
class SettingRowView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onRowFocused(id: String)
        fun onRowClicked(id: String)
        fun onRowAdjusted(id: String, delta: Int)
    }

    var listener: Listener? = null
    var model: RowModel? = null
        private set

    private val d = resources.displayMetrics.density
    private val title = TextView(context)
    private val value = TextView(context)
    private val prev = ImageView(context)
    private val next = ImageView(context)
    private val chevron = ImageView(context)
    private val switch = SwitchIndicatorView(context)

    private val bg = GradientDrawable()
    private var focusFraction = 0f
    private var animator: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    private val cSurface = context.getColor(android.R.color.transparent)
    private val cPrimary = context.getColor(R.color.m3_primary)
    private val cOnPrimary = context.getColor(R.color.m3_on_primary)
    private val cError = context.getColor(R.color.m3_error)
    private val cOnError = context.getColor(R.color.m3_on_error)
    private val cOnSurface = context.getColor(R.color.m3_on_surface)
    private val cOnSurfaceVariant = context.getColor(R.color.m3_on_surface_variant)
    private val cOutline = context.getColor(R.color.m3_outline)
    private val cSurfaceHighest = context.getColor(R.color.m3_surface_container_highest)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (64 * d).toInt()
        setPadding((24 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
        isFocusable = true
        isClickable = true
        background = bg
        clipToOutline = false

        title.apply {
            textSize = 20f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextAppearance(R.style.Text_RowTitle)
        }
        value.apply {
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextAppearance(R.style.Text_RowValue)
        }
        prev.setImageResource(R.drawable.ic_chevron_left)
        next.setImageResource(R.drawable.ic_chevron_right)
        chevron.setImageResource(R.drawable.ic_chevron_right)
        listOf(prev, next, chevron).forEach { it.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val icon = (24 * d).toInt()
        addView(prev, LayoutParams(icon, icon).apply { marginStart = (12 * d).toInt() })
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
        })
        addView(next, LayoutParams(icon, icon))
        addView(chevron, LayoutParams(icon, icon).apply { marginStart = (8 * d).toInt() })
        addView(switch, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = (16 * d).toInt()
        })

        setOnClickListener { model?.let { listener?.onRowClicked(it.id) } }
        applyFocusFraction(0f)
    }

    fun bind(m: RowModel) {
        val old = model
        model = m
        prev.visibility = GONE; next.visibility = GONE; chevron.visibility = GONE; switch.visibility = GONE
        value.visibility = VISIBLE
        var enabled = true
        when (m) {
            is RowModel.Header -> Unit // headers use a separate view
            is RowModel.Navigation -> {
                title.text = m.title; value.text = m.value.orEmpty(); chevron.visibility = VISIBLE
                contentDescription = listOfNotNull(m.title, m.value).joinToString(", ")
            }
            is RowModel.Action -> {
                title.text = m.title; value.text = m.value.orEmpty(); enabled = m.enabled
                contentDescription = listOfNotNull(m.title, m.value).joinToString(", ")
            }
            is RowModel.Choice -> {
                title.text = m.title; value.text = m.value
                prev.visibility = VISIBLE; next.visibility = VISIBLE
                prev.alpha = if (m.selected > 0) 1f else 0.3f
                next.alpha = if (m.selected < m.options.lastIndex) 1f else 0.3f
                contentDescription = "${m.title}, ${m.value}"
            }
            is RowModel.Toggle -> {
                title.text = m.title; value.visibility = GONE; switch.visibility = VISIBLE
                switch.setChecked(m.checked, animate = old is RowModel.Toggle && old.id == m.id)
                contentDescription = "${m.title}, ${context.getString(if (m.checked) R.string.opt_on else R.string.opt_off)}"
            }
            is RowModel.Stepper -> {
                title.text = m.title; value.text = m.valueLabel
                prev.visibility = VISIBLE; next.visibility = VISIBLE
                prev.alpha = if (m.atMin) 0.3f else 1f
                next.alpha = if (m.atMax) 0.3f else 1f
                contentDescription = "${m.title}, ${m.valueLabel}"
            }
        }
        isEnabled = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.38f
        // Choice arrows only appear while focused (keeps the list calm); stepper arrows always show.
        if (m is RowModel.Choice) {
            val show = if (isFocused) VISIBLE else INVISIBLE
            prev.visibility = show; next.visibility = show
        }
        applyFocusFraction(focusFraction)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) model?.let { listener?.onRowFocused(it.id) }
        model?.let { if (it is RowModel.Choice) bind(it) }
        animateFocus(gainFocus)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val m = model
        val adjustable = m is RowModel.Choice || m is RowModel.Toggle || m is RowModel.Stepper
        if (adjustable && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            listener?.onRowAdjusted(m!!.id, if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun animateFocus(focused: Boolean) {
        animator?.cancel()
        val target = if (focused) 1f else 0f
        animator = ValueAnimator.ofFloat(focusFraction, target).apply {
            duration = if (focused) Motion.focusInMs() else Motion.focusOutMs()
            interpolator = if (focused) Motion.focusIn else Motion.focusOut
            addUpdateListener { applyFocusFraction(it.animatedValue as Float) }
            start()
        }
        animate().translationZ(if (focused) 8 * d else 0f)
            .setDuration(if (focused) Motion.focusInMs() else Motion.focusOutMs()).start()
    }

    /** f: 0 = resting, 1 = focused (may overshoot slightly for the springy feel). */
    private fun applyFocusFraction(f: Float) {
        focusFraction = f
        val c = f.coerceIn(0f, 1f)
        val destructive = (model as? RowModel.Action)?.destructive == true
        val fill = if (destructive) cError else cPrimary
        val onFill = if (destructive) cOnError else cOnPrimary
        bg.setColor(argb.evaluate(c, cSurface, fill) as Int)
        // Shape morph: 16dp rounded rect -> full pill.
        bg.cornerRadius = (16 + (height / d / 2 - 16).coerceAtLeast(0f) * c) * d
        val s = 1f + 0.03f * f
        scaleX = s; scaleY = s
        val titleColor = argb.evaluate(c, if (destructive) cError else cOnSurface, onFill) as Int
        val valueColor = argb.evaluate(c, cOnSurfaceVariant, onFill) as Int
        title.setTextColor(titleColor)
        value.setTextColor(valueColor)
        val tint = ColorStateList.valueOf(valueColor)
        prev.imageTintList = tint; next.imageTintList = tint; chevron.imageTintList = tint
        switch.colors = SwitchIndicatorView.Colors(
            onTrack = argb.evaluate(c, cPrimary, cOnPrimary) as Int,
            onThumb = argb.evaluate(c, cOnPrimary, cPrimary) as Int,
            offTrack = argb.evaluate(c, cSurfaceHighest, cOnPrimary) as Int,
            offThumb = argb.evaluate(c, cOutline, cPrimary) as Int,
            offOutline = argb.evaluate(c, cOutline, cOnPrimary) as Int,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyFocusFraction(focusFraction)
    }
}
