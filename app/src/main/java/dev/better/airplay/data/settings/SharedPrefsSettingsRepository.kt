package dev.better.airplay.data.settings

import android.content.Context
import android.content.SharedPreferences
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.port.SettingsRepository

/** Persists [ReceiverSettings]; enums are stored by name so reordering them is safe. */
class SharedPrefsSettingsRepository(context: Context) : SettingsRepository {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaults = ReceiverSettings()

    @Volatile private var cache: ReceiverSettings? = null

    override fun load(): ReceiverSettings = cache ?: read().also { cache = it }

    override fun save(settings: ReceiverSettings) {
        cache = settings
        prefs.edit()
            .putString(K_NAME, settings.customName)
            .putString(K_RES, settings.resolution.name)
            .putString(K_CODEC, settings.codec.name)
            .putString(K_REFRESH, settings.refreshRate.name)
            .putString(K_FPS, settings.frameRateCap.name)
            .putBoolean(K_OVERSCAN, settings.overscanCompensation)
            .putString(K_LATENCY, settings.latencyMode.name)
            .putBoolean(K_MIRROR_AUDIO, settings.mirrorAudio)
            .putInt(K_AUDIO_DELAY, settings.audioDelayMs)
            .putBoolean(K_PASSTHROUGH, settings.surroundPassthrough)
            .putBoolean(K_HDR, settings.hdrPassthrough)
            .putString(K_FRM, settings.frameRateMatching.name)
            .putString(K_ACCESS, settings.access.name)
            .putBoolean(K_STATS, settings.statsOverlay)
            .apply()
    }

    private fun read() = ReceiverSettings(
        customName = prefs.getString(K_NAME, null)?.takeIf { it.isNotBlank() },
        resolution = prefs.enum(K_RES, defaults.resolution),
        codec = prefs.enum(K_CODEC, defaults.codec),
        refreshRate = prefs.enum(K_REFRESH, defaults.refreshRate),
        frameRateCap = prefs.enum(K_FPS, defaults.frameRateCap),
        overscanCompensation = prefs.getBoolean(K_OVERSCAN, defaults.overscanCompensation),
        latencyMode = prefs.enum(K_LATENCY, defaults.latencyMode),
        mirrorAudio = prefs.getBoolean(K_MIRROR_AUDIO, defaults.mirrorAudio),
        audioDelayMs = prefs.getInt(K_AUDIO_DELAY, defaults.audioDelayMs),
        surroundPassthrough = prefs.getBoolean(K_PASSTHROUGH, defaults.surroundPassthrough),
        hdrPassthrough = prefs.getBoolean(K_HDR, defaults.hdrPassthrough),
        frameRateMatching = prefs.enum(K_FRM, defaults.frameRateMatching),
        access = prefs.enum(K_ACCESS, defaults.access),
        statsOverlay = prefs.getBoolean(K_STATS, defaults.statsOverlay),
    )

    private inline fun <reified E : Enum<E>> SharedPreferences.enum(key: String, default: E): E =
        getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private companion object {
        const val K_NAME = "custom_name"
        const val K_RES = "resolution"
        const val K_CODEC = "codec"
        const val K_REFRESH = "refresh_rate"
        const val K_FPS = "frame_rate_cap"
        const val K_OVERSCAN = "overscan"
        const val K_LATENCY = "latency_mode"
        const val K_MIRROR_AUDIO = "mirror_audio"
        const val K_AUDIO_DELAY = "audio_delay_ms"
        const val K_PASSTHROUGH = "surround_passthrough"
        const val K_HDR = "hdr_passthrough"
        const val K_FRM = "frame_rate_matching"
        const val K_ACCESS = "access"
        const val K_STATS = "stats_overlay"
    }
}
