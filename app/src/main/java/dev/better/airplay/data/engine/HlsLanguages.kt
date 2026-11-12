package dev.better.airplay.data.engine

import java.util.Locale

/**
 * The TV's preferred languages in the form UxPlay matches against an HLS master playlist's
 * LANGUAGE attributes when it picks the audio/subtitle rendition: "en-IN:hi-IN:en:hi".
 * Never empty: UxPlay dereferences the list without a null check.
 */
internal object HlsLanguages {
    fun of(locales: List<Locale>): String {
        val tags = locales.mapNotNull { l -> l.toLanguageTag().takeIf { l.language.isNotEmpty() && it != "und" } }
        val bases = locales.mapNotNull { l -> l.language.takeIf { it.isNotEmpty() } }
        return (tags + bases).distinct().joinToString(":").ifEmpty { "en" }
    }

    /** The device's current locale list. */
    fun system(): String {
        val list = android.os.LocaleList.getDefault()
        return of((0 until list.size()).map { list[it] })
    }
}
