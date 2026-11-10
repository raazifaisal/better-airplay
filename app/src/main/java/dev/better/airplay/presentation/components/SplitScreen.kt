package dev.better.airplay.presentation.components

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.better.airplay.R

/** Typed view references for layout/activity_split.xml (the shared tvOS-style split screen). */
class SplitScreen(activity: Activity) {
    val artworkIcon: ImageView = activity.findViewById(R.id.artwork_icon)
    val title: TextView = activity.findViewById(R.id.title)
    val statusDot: View = activity.findViewById(R.id.status_dot)
    val subtitle: TextView = activity.findViewById(R.id.subtitle)
    val pin: TextView = activity.findViewById(R.id.pin)
    val description: TextView = activity.findViewById(R.id.description)
    val rows: LinearLayout = activity.findViewById(R.id.rows)
    val message = MessageBar(activity.findViewById(R.id.message))

    /** Cross-fade the description when focus moves, so the change is noticed but not distracting. */
    fun setDescription(text: String) {
        if (description.text.toString() == text) return
        if (!Motion.enabled || description.text.isNullOrEmpty()) { description.text = text; return }
        description.animate().alpha(0f).setDuration(90).withEndAction {
            description.text = text
            description.animate().alpha(1f).setDuration(140).start()
        }.start()
    }
}
