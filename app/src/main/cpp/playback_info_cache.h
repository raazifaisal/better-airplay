/*
 * playback_info_cache.h - the handover video player's latest state, for UxPlay's /playback-info.
 *
 * Kotlin pushes the player's state down a couple of times a second (nativeUpdatePlaybackInfo);
 * UxPlay's HTTP thread reads it when the phone polls (on_video_acquire_playback_info). Native never
 * calls up into the app on UxPlay's thread to ask. Header-only and free of UxPlay types so the
 * on-device tests can exercise it directly. GPL-3.0.
 */
#pragma once

#include <mutex>

struct PlaybackSample {
    double duration = 0;    // seconds; 0 = unknown / live
    double position = 0;    // seconds
    float rate = 0;         // 1 playing, 0 paused
    bool bufferEmpty = true;
    bool bufferFull = false;
    bool ended = false;     // the video is over (or failed): the phone should be released
    bool ready = false;     // the player has prepared the stream
};

/** Thread-safe single-value cache. */
class PlaybackInfoCache {
public:
    void update(const PlaybackSample &s) {
        std::lock_guard<std::mutex> lk(m_);
        s_ = s;
    }
    PlaybackSample get() const {
        std::lock_guard<std::mutex> lk(m_);
        return s_;
    }
    void reset() { update(PlaybackSample{}); }

private:
    mutable std::mutex m_;
    PlaybackSample s_;
};

/*
 * Fills UxPlay's playback_info_t (any struct with the same field names) from a sample.
 * UxPlay's contract: duration == -1 means "video finished" (it resets the HLS session and
 * closes the connection, which releases the phone). Time ranges are filled in by UxPlay.
 */
template <class Out>
void fillPlaybackInfo(const PlaybackSample &s, Out *o) {
    o->stallcount = 0;
    o->num_loaded_time_ranges = 0;
    o->num_seekable_time_ranges = 0;
    o->loadedTimeRanges = nullptr;
    o->seekableTimeRanges = nullptr;
    if (s.ended) {
        o->duration = -1.0;
        o->position = -1.0;
        o->seek_start = 0;
        o->seek_duration = 0;
        o->rate = 0;
        o->ready_to_play = false;
        o->playback_buffer_empty = false;
        o->playback_buffer_full = false;
        o->playback_likely_to_keep_up = false;
        return;
    }
    double duration = s.duration > 0 ? s.duration : 0;
    double position = s.position > 0 ? s.position : 0;
    if (duration > 0 && position > duration) position = duration;
    o->duration = duration;
    o->position = position;
    o->seek_start = 0;
    o->seek_duration = duration;
    o->rate = s.ready ? s.rate : 0;
    o->ready_to_play = s.ready;
    o->playback_buffer_empty = s.bufferEmpty;
    o->playback_buffer_full = s.bufferFull;
    o->playback_likely_to_keep_up = s.ready && !s.bufferEmpty;
}
