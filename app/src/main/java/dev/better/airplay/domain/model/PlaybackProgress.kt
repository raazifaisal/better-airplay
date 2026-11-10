package dev.better.airplay.domain.model

/**
 * Track position as AirPlay reports it: RTP sample counters (44.1 kHz, 32-bit, wrapping) for the
 * track start, the current playhead and the track end, stamped with the local time it arrived.
 * Between updates the position is extrapolated while [playing].
 */
data class PlaybackProgress(
    val startRtp: Long,
    val currentRtp: Long,
    val endRtp: Long,
    val receivedAtMs: Long,
    val playing: Boolean = true,
) {
    val durationMs: Long get() = samplesToMs(diff(endRtp, startRtp))

    fun positionMsAt(nowMs: Long): Long {
        val base = samplesToMs(diff(currentRtp, startRtp))
        val moved = if (playing) (nowMs - receivedAtMs).coerceAtLeast(0) else 0
        return (base + moved).coerceIn(0, durationMs)
    }

    fun fractionAt(nowMs: Long): Float =
        if (durationMs <= 0) 0f else (positionMsAt(nowMs).toFloat() / durationMs).coerceIn(0f, 1f)

    /** Freeze at the position reached by [nowMs] (used when the sender flushes / pauses). */
    fun pausedAt(nowMs: Long): PlaybackProgress {
        val samples = positionMsAt(nowMs) * SAMPLE_RATE / 1000
        return copy(currentRtp = (startRtp + samples) and MASK, receivedAtMs = nowMs, playing = false)
    }

    /** True if [rtp] lies within this play sequence (start..end, modulo 2^32). */
    fun contains(rtp: Long): Boolean = diff(rtp, startRtp) <= diff(endRtp, startRtp)

    /** iOS occasionally sends a "current" outside start..end right after a resume; such updates are bogus. */
    val isValid: Boolean get() = durationMs > 0 && contains(currentRtp)

    /** Re-anchor on the RTP timestamp of the frame actually being played (shairport-sync's FramePosition). */
    fun withPlayhead(rtp: Long, nowMs: Long) = copy(currentRtp = rtp and MASK, receivedAtMs = nowMs, playing = true)

    /** Continue extrapolating from the frozen position, starting at [nowMs]. */
    fun resumedAt(nowMs: Long): PlaybackProgress = if (playing) this else copy(receivedAtMs = nowMs, playing = true)

    private companion object {
        const val SAMPLE_RATE = 44_100L
        const val MASK = 0xFFFF_FFFFL
        fun diff(a: Long, b: Long) = (a - b) and MASK
        fun samplesToMs(samples: Long) = samples * 1000 / SAMPLE_RATE
    }
}
