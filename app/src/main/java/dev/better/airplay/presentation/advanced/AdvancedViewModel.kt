package dev.better.airplay.presentation.advanced

import android.os.Handler
import android.os.Looper
import dev.better.airplay.R
import dev.better.airplay.domain.model.AccessMode
import dev.better.airplay.domain.model.CodecPreference
import dev.better.airplay.domain.model.FrameRateCap
import dev.better.airplay.domain.model.FrameRateMatching
import dev.better.airplay.domain.model.LatencyMode
import dev.better.airplay.domain.model.ReceiverConfig
import dev.better.airplay.domain.model.ReceiverSettings
import dev.better.airplay.domain.model.RefreshRate
import dev.better.airplay.domain.model.Resolution
import dev.better.airplay.domain.usecase.BuildReceiverConfig
import dev.better.airplay.domain.usecase.GetSettings
import dev.better.airplay.domain.usecase.UpdateSettings
import dev.better.airplay.presentation.core.RowAction
import dev.better.airplay.presentation.core.RowModel
import dev.better.airplay.presentation.core.Strings
import dev.better.airplay.presentation.core.ViewModel
import dev.better.airplay.presentation.core.description

class AdvancedViewModel(
    private val getSettings: GetSettings,
    private val updateSettings: UpdateSettings,
    private val buildConfig: BuildReceiverConfig,
    private val strings: Strings,
) : ViewModel<AdvancedUiState, AdvancedAction, AdvancedEffect>(AdvancedUiState()) {

    private val main = Handler(Looper.getMainLooper())
    private var draft: ReceiverSettings = getSettings()
    private var focusedRow: String? = null
    private val commitRunnable = Runnable { commit() }

    override fun onAction(action: AdvancedAction) {
        val a = (action as AdvancedAction.Row).action
        when (a) {
            is RowAction.Focused -> { focusedRow = a.id; publish() }
            is RowAction.Clicked -> click(a.id)
            is RowAction.Adjusted -> adjust(a.id, a.delta)
        }
    }

    override fun onCleared() {
        main.removeCallbacks(commitRunnable)
        commit()
    }

    // ---- intents ----

    private fun click(id: String) {
        if (id == ID_RESET) {
            draft = ReceiverSettings(customName = draft.customName)
            commitNow(); return
        }
        when (val spec = specs.firstOrNull { it.id == id }) {
            is Spec.Choice<*> -> { draft = spec.step(draft, +1, wrap = true); commitNow() }
            is Spec.Toggle -> { draft = spec.set(draft, !spec.get(draft)); commitNow() }
            is Spec.Stepper -> { draft = spec.set(draft, 0); scheduleCommit() }   // OK resets to 0
            null -> Unit
        }
    }

    private fun adjust(id: String, delta: Int) {
        when (val spec = specs.firstOrNull { it.id == id }) {
            is Spec.Choice<*> -> { draft = spec.step(draft, delta, wrap = false); scheduleCommit() }
            is Spec.Toggle -> { draft = spec.set(draft, delta > 0); scheduleCommit() }
            is Spec.Stepper -> {
                val v = (spec.get(draft) + delta * spec.step).coerceIn(spec.min, spec.max)
                draft = spec.set(draft, v); scheduleCommit()
            }
            null -> Unit
        }
    }

    /** Debounce rapid ◀ ▶ presses so the receiver restarts once, after the user settles. */
    private fun scheduleCommit() {
        publish()
        main.removeCallbacks(commitRunnable)
        main.postDelayed(commitRunnable, COMMIT_DELAY_MS)
    }

    private fun commitNow() {
        main.removeCallbacks(commitRunnable)
        commit()
    }

    private fun commit() {
        val target = draft
        val effect = updateSettings { target }
        when (effect) {
            UpdateSettings.Effect.RESTARTED -> emit(AdvancedEffect.Message(strings.get(R.string.msg_saved_restarted)))
            UpdateSettings.Effect.APPLIED_NOW -> emit(AdvancedEffect.Message(strings.get(R.string.msg_saved_next_stream)))
            UpdateSettings.Effect.NONE -> Unit
        }
        publish()
    }

    // ---- model building ----

    private fun publish() {
        val s = draft
        val config = buildConfig.preview(s)
        val rows = buildList {
            sections.forEach { (header, ids) ->
                add(RowModel.Header("h_$header", strings.get(header)))
                ids.forEach { id -> specs.first { it.id == id }.let { add(it.toRow(s, config)) } }
            }
            add(RowModel.Header("h_reset", strings.get(R.string.sec_maintenance)))
            add(RowModel.Action(ID_RESET, strings.get(R.string.set_reset), null,
                strings.get(R.string.set_reset_desc), destructive = true))
        }
        val focused = rows.firstOrNull { it.id == focusedRow }
        val adjustable = focused is RowModel.Choice || focused is RowModel.Toggle || focused is RowModel.Stepper
        val help = focused?.description?.let {
            if (adjustable) it + "\n\n" + strings.get(R.string.hint_adjust) else it
        } ?: strings.get(R.string.adv_intro)
        setState {
            AdvancedUiState(
                effectiveSummary = effectiveSummary(config),
                description = help,
                rows = rows,
            )
        }
    }

    private fun effectiveSummary(c: ReceiverConfig) = strings.get(
        R.string.adv_effective,
        resLabel(c.width, c.height),
        if (c.hevc) "HEVC" else "H.264",
        c.refreshHz,
        c.maxFps,
        strings.get(if (c.requirePin) R.string.pin_on else R.string.pin_off),
    )

    private fun resLabel(w: Int, h: Int) = when {
        w >= 3800 -> "4K"; w >= 2500 -> "1440p"; w >= 1900 -> "1080p"; else -> "${h}p"
    }

    private fun Spec.toRow(s: ReceiverSettings, c: ReceiverConfig): RowModel = when (this) {
        is Spec.Choice<*> -> RowModel.Choice(id, strings.get(title), labels(c), indexIn(s), strings.get(desc))
        is Spec.Toggle -> RowModel.Toggle(id, strings.get(title), get(s), strings.get(desc))
        is Spec.Stepper -> {
            val v = get(s)
            RowModel.Stepper(id, strings.get(title), format(v), strings.get(desc), v <= min, v >= max)
        }
    }

    // ---- setting catalog ----

    private sealed interface Spec {
        val id: String; val title: Int; val desc: Int

        class Choice<T>(
            override val id: String, override val title: Int, override val desc: Int,
            val values: List<T>,
            val label: (T, ReceiverConfig) -> String,
            val get: (ReceiverSettings) -> T,
            val set: (ReceiverSettings, T) -> ReceiverSettings,
        ) : Spec {
            fun labels(c: ReceiverConfig) = values.map { label(it, c) }
            fun indexIn(s: ReceiverSettings) = values.indexOf(get(s)).coerceAtLeast(0)
            fun step(s: ReceiverSettings, delta: Int, wrap: Boolean): ReceiverSettings {
                val i = indexIn(s) + delta
                val next = if (wrap) Math.floorMod(i, values.size) else i.coerceIn(0, values.lastIndex)
                return set(s, values[next])
            }
        }

        class Toggle(
            override val id: String, override val title: Int, override val desc: Int,
            val get: (ReceiverSettings) -> Boolean,
            val set: (ReceiverSettings, Boolean) -> ReceiverSettings,
        ) : Spec

        class Stepper(
            override val id: String, override val title: Int, override val desc: Int,
            val min: Int, val max: Int, val step: Int,
            val format: (Int) -> String,
            val get: (ReceiverSettings) -> Int,
            val set: (ReceiverSettings, Int) -> ReceiverSettings,
        ) : Spec
    }

    private val specs: List<Spec> = listOf(
        Spec.Choice("resolution", R.string.set_resolution, R.string.set_resolution_desc,
            Resolution.entries.toList(),
            { r, c -> when (r) {
                Resolution.AUTO -> strings.get(R.string.opt_auto_value, resLabel(c.width, c.height))
                Resolution.UHD_2160 -> "4K (2160p)"
                Resolution.QHD_1440 -> "1440p"
                Resolution.FHD_1080 -> "1080p"
                Resolution.HD_720 -> "720p"
            } },
            { it.resolution }, { s, v -> s.copy(resolution = v) }),
        Spec.Choice("codec", R.string.set_codec, R.string.set_codec_desc,
            CodecPreference.entries.toList(),
            { v, c -> when (v) {
                CodecPreference.AUTO -> strings.get(
                    if (c.hevc || draft.codec != CodecPreference.AUTO) R.string.opt_codec_auto else R.string.opt_codec_auto_no_hevc)
                CodecPreference.H264_ONLY -> strings.get(R.string.opt_codec_h264)
            } },
            { it.codec }, { s, v -> s.copy(codec = v) }),
        Spec.Choice("refresh", R.string.set_refresh, R.string.set_refresh_desc,
            RefreshRate.entries.toList(),
            { v, c -> if (v == RefreshRate.AUTO) strings.get(R.string.opt_auto_value, "${c.refreshHz} Hz") else "${v.hz} Hz" },
            { it.refreshRate }, { s, v -> s.copy(refreshRate = v) }),
        Spec.Choice("fps", R.string.set_fps, R.string.set_fps_desc,
            FrameRateCap.entries.toList(), { v, _ -> "${v.fps} fps" },
            { it.frameRateCap }, { s, v -> s.copy(frameRateCap = v) }),
        Spec.Choice("latency", R.string.set_latency, R.string.set_latency_desc,
            LatencyMode.entries.toList(),
            { v, _ -> strings.get(when (v) {
                LatencyMode.LOW -> R.string.opt_latency_low
                LatencyMode.BALANCED -> R.string.opt_latency_balanced
                LatencyMode.SMOOTH -> R.string.opt_latency_smooth
            }, v.bufferMs) },
            { it.latencyMode }, { s, v -> s.copy(latencyMode = v) }),
        Spec.Toggle("overscan", R.string.set_overscan, R.string.set_overscan_desc,
            { it.overscanCompensation }, { s, v -> s.copy(overscanCompensation = v) }),

        Spec.Toggle("mirror_audio", R.string.set_mirror_audio, R.string.set_mirror_audio_desc,
            { it.mirrorAudio }, { s, v -> s.copy(mirrorAudio = v) }),
        Spec.Stepper("audio_delay", R.string.set_audio_delay, R.string.set_audio_delay_desc,
            ReceiverSettings.AUDIO_DELAY_MIN_MS, ReceiverSettings.AUDIO_DELAY_MAX_MS, ReceiverSettings.AUDIO_DELAY_STEP_MS,
            { v -> if (v == 0) strings.get(R.string.opt_in_sync) else "%+d ms".format(v) },
            { it.audioDelayMs }, { s, v -> s.copy(audioDelayMs = v) }),
        Spec.Toggle("passthrough", R.string.set_passthrough, R.string.set_passthrough_desc,
            { it.surroundPassthrough }, { s, v -> s.copy(surroundPassthrough = v) }),

        Spec.Toggle("hdr", R.string.set_hdr, R.string.set_hdr_desc,
            { it.hdrPassthrough }, { s, v -> s.copy(hdrPassthrough = v) }),
        Spec.Choice("match_fps", R.string.set_match_fps, R.string.set_match_fps_desc,
            FrameRateMatching.entries.toList(),
            { v, _ -> strings.get(when (v) {
                FrameRateMatching.OFF -> R.string.opt_off
                FrameRateMatching.SEAMLESS_ONLY -> R.string.opt_match_seamless
                FrameRateMatching.ALWAYS -> R.string.opt_match_always
            }) },
            { it.frameRateMatching }, { s, v -> s.copy(frameRateMatching = v) }),

        Spec.Choice("access", R.string.set_access, R.string.set_access_desc,
            AccessMode.entries.toList(),
            { v, _ -> strings.get(if (v == AccessMode.EVERYONE) R.string.opt_access_everyone else R.string.opt_access_pin) },
            { it.access }, { s, v -> s.copy(access = v) }),

        Spec.Toggle("stats", R.string.set_stats, R.string.set_stats_desc,
            { it.statsOverlay }, { s, v -> s.copy(statsOverlay = v) }),
    )

    private val sections: List<Pair<Int, List<String>>> = listOf(
        R.string.sec_mirroring to listOf("resolution", "codec", "refresh", "fps", "latency", "overscan"),
        R.string.sec_audio to listOf("mirror_audio", "audio_delay", "passthrough"),
        R.string.sec_playback to listOf("hdr", "match_fps"),
        R.string.sec_access to listOf("access"),
        R.string.sec_diagnostics to listOf("stats"),
    )

    // Must come after the property declarations above: Kotlin runs initializers in declaration order.
    init { publish() }

    private companion object {
        const val ID_RESET = "reset"
        const val COMMIT_DELAY_MS = 1200L
    }
}
