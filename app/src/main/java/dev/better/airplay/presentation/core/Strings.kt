package dev.better.airplay.presentation.core

import android.content.Context

/** Text lookup for ViewModels, so they produce display text without holding a Context. */
interface Strings {
    /** [id] is an R.string resource id. */
    fun get(id: Int, vararg args: Any): String
}

class ResourceStrings(private val context: Context) : Strings {
    override fun get(id: Int, vararg args: Any): String = context.getString(id, *args)
}
