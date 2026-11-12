package dev.better.airplay.domain.model

/**
 * What the TV's video player is doing during an AirPlay video handover (YouTube, Safari, ...), in the
 * shape the phone asks for on GET /playback-info (it drives the phone's scrubber and play state).
 */
data class VideoPlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    /** 1 = playing (also while stalled, like AVPlayer), 0 = paused. */
    val rate: Float,
    val bufferEmpty: Boolean,
    val bufferFull: Boolean,
    /** Playback reached the end (or failed): the receiver tells the phone the video is over. */
    val ended: Boolean,
    /** The player has prepared the stream and knows its timeline. */
    val ready: Boolean,
) {
    companion object {
        /** Nothing loaded yet (or handover over). */
        val IDLE = VideoPlaybackInfo(0.0, 0.0, 0f, bufferEmpty = true, bufferFull = false, ended = false, ready = false)
        /** Tells the phone the video is over, which releases it. */
        val ENDED = VideoPlaybackInfo(0.0, 0.0, 0f, bufferEmpty = false, bufferFull = false, ended = true, ready = false)

        /**
         * Maps a player's raw state to [VideoPlaybackInfo]. [durationMs] <= 0 means unknown/live.
         * [playIntent] is whether playback should be running (the phone's last play/pause).
         */
        fun fromPlayer(
            durationMs: Long, positionMs: Long, prepared: Boolean,
            playIntent: Boolean, buffering: Boolean, completed: Boolean,
        ): VideoPlaybackInfo {
            val duration = if (durationMs > 0) durationMs / 1000.0 else 0.0
            if (completed) {
                return VideoPlaybackInfo(duration, duration, 0f, bufferEmpty = false, bufferFull = false, ended = true, ready = true)
            }
            if (!prepared) return IDLE
            var position = maxOf(0L, positionMs) / 1000.0
            if (duration > 0) position = minOf(position, duration)
            return VideoPlaybackInfo(
                durationSec = duration,
                positionSec = position,
                rate = if (playIntent) 1f else 0f,
                bufferEmpty = buffering,
                bufferFull = !buffering,
                ended = false,
                ready = true,
            )
        }
    }
}

/** A video player failure, as the platform reports it (MediaPlayer what/extra). */
data class VideoError(val code: Int, val detail: Int)
