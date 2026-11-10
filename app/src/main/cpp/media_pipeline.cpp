/* media_pipeline.cpp - see media_pipeline.h. GPL-3.0. */
#include "media_pipeline.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <algorithm>
#include <cmath>
#include <cstring>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

#define TAG "BetterAirPlay/media"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
constexpr size_t kMaxGopBytes = 24 * 1024 * 1024;   // cap re-prime cache (1.8 GB TVs)
constexpr int kSampleRate = 44100;
constexpr int kChannels = 2;
}

// ============================== Video ==============================

VideoPipeline::VideoPipeline(EventSink sink) : sink_(std::move(sink)) {
    thread_ = std::thread(&VideoPipeline::loop, this);
}

VideoPipeline::~VideoPipeline() {
    running_ = false;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
    std::lock_guard<std::mutex> lk(m_);
    releaseCodecLocked();
    if (window_) { ANativeWindow_release(window_); window_ = nullptr; }
}

void VideoPipeline::setWindow(ANativeWindow *window) {
    std::lock_guard<std::mutex> lk(m_);
    if (window == window_) return;
    releaseCodecLocked();   // a codec is bound to its output surface
    if (window_) ANativeWindow_release(window_);
    window_ = window;
    if (window_) ANativeWindow_acquire(window_);
    cv_.notify_all();
}

void VideoPipeline::configure(bool hevc, int maxWidth, int maxHeight) {
    std::lock_guard<std::mutex> lk(m_);
    if (configured_ && hevc == hevc_ && maxWidth == maxW_ && maxHeight == maxH_) return;
    releaseCodecLocked();
    hevc_ = hevc;
    maxW_ = maxWidth > 0 ? maxWidth : 1920;
    maxH_ = maxHeight > 0 ? maxHeight : 1080;
    configured_ = true;
    cv_.notify_all();
}

bool VideoPipeline::startsGop(const uint8_t *d, size_t n, bool hevc) {
    // Look at the first few NAL units for parameter sets (H.264 SPS=7; HEVC VPS=32, SPS=33).
    size_t limit = std::min(n, (size_t) 512);
    for (size_t i = 0; i + 4 < limit; i++) {
        if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 0 && d[i + 3] == 1) {
            uint8_t h = d[i + 4];
            if (hevc) { int t = (h >> 1) & 0x3f; if (t == 32 || t == 33) return true; }
            else if ((h & 0x1f) == 7) return true;
        }
    }
    return false;
}

void VideoPipeline::push(const uint8_t *data, size_t len) {
    if (!data || len == 0) return;
    std::lock_guard<std::mutex> lk(m_);
    std::vector<uint8_t> pkt(data, data + len);
    if (startsGop(data, len, hevc_)) { gop_.clear(); gopBytes_ = 0; }
    if (gopBytes_ + len <= kMaxGopBytes) { gop_.push_back(pkt); gopBytes_ += len; }
    if (codec_) {
        queue_.push_back(std::move(pkt));
        // If the decoder can't keep up, drop the backlog rather than build latency.
        while (queue_.size() > 120) queue_.pop_front();
    }
    cv_.notify_all();
}

void VideoPipeline::flush() {
    std::lock_guard<std::mutex> lk(m_);
    queue_.clear();
}

void VideoPipeline::reset() {
    std::lock_guard<std::mutex> lk(m_);
    queue_.clear();
    gop_.clear();
    gopBytes_ = 0;
    releaseCodecLocked();
    configured_ = false;
}

bool VideoPipeline::ensureCodecLocked() {
    if (codec_) return true;
    if (!window_ || !configured_) return false;
    const char *mime = hevc_ ? "video/hevc" : "video/avc";
    codec_ = AMediaCodec_createDecoderByType(mime);
    if (!codec_) { LOGE("no decoder for %s", mime); return false; }
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, maxW_);
    AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, maxH_);
    AMediaFormat_setInt32(f, "max-width", maxW_);
    AMediaFormat_setInt32(f, "max-height", maxH_);
    AMediaFormat_setInt32(f, "priority", 0);                 // realtime
    if (lowLatency_) AMediaFormat_setInt32(f, "low-latency", 1);
    media_status_t st = AMediaCodec_configure(codec_, f, window_, nullptr, 0);
    AMediaFormat_delete(f);
    if (st != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
        LOGE("decoder configure/start failed (%d) for %s %dx%d", st, mime, maxW_, maxH_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
        configured_ = false;   // wait for a new configure() rather than retrying in a loop
        sink_("video_error", std::to_string((int) st));
        return false;
    }
    LOGI("video decoder started: %s up to %dx%d, replaying %zu cached units", mime, maxW_, maxH_, gop_.size());
    queue_.assign(gop_.begin(), gop_.end());   // start from the last parameter sets / keyframe
    return true;
}

void VideoPipeline::releaseCodecLocked() {
    if (!codec_) return;
    AMediaCodec_stop(codec_);
    AMediaCodec_delete(codec_);
    codec_ = nullptr;
    queue_.clear();
}

void VideoPipeline::drainLocked() {
    AMediaCodecBufferInfo info;
    for (;;) {
        ssize_t o = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
        if (o >= 0) {
            AMediaCodec_releaseOutputBuffer(codec_, (size_t) o, true);   // render now: lowest latency
        } else if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *f = AMediaCodec_getOutputFormat(codec_);
            int32_t w = 0, h = 0, l = 0, t = 0, r = -1, b = -1;
            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_WIDTH, &w);
            AMediaFormat_getInt32(f, AMEDIAFORMAT_KEY_HEIGHT, &h);
            // Per-edge crop keys work back to API 21 (AMediaFormat_getRect needs API 28).
            if (AMediaFormat_getInt32(f, "crop-left", &l) && AMediaFormat_getInt32(f, "crop-top", &t) &&
                AMediaFormat_getInt32(f, "crop-right", &r) && AMediaFormat_getInt32(f, "crop-bottom", &b) &&
                r >= l && b >= t) { w = r - l + 1; h = b - t + 1; }
            AMediaFormat_delete(f);
            sink_("video_size", std::to_string(w) + "x" + std::to_string(h));
        } else if (o == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
            continue;
        } else {
            break;
        }
    }
}

void VideoPipeline::loop() {
    std::unique_lock<std::mutex> lk(m_);
    while (running_) {
        cv_.wait_for(lk, std::chrono::milliseconds(8), [&] { return !running_ || (codec_ && !queue_.empty()); });
        if (!running_) break;
        if (!ensureCodecLocked()) continue;
        while (!queue_.empty()) {
            ssize_t idx = AMediaCodec_dequeueInputBuffer(codec_, 2000);
            if (idx < 0) break;
            size_t cap = 0;
            uint8_t *buf = AMediaCodec_getInputBuffer(codec_, (size_t) idx, &cap);
            const auto &pkt = queue_.front();
            size_t n = std::min(cap, pkt.size());
            if (n < pkt.size()) LOGW("access unit truncated %zu > %zu", pkt.size(), cap);
            memcpy(buf, pkt.data(), n);
            AMediaCodec_queueInputBuffer(codec_, (size_t) idx, 0, n, ptsUs_, 0);
            ptsUs_ += 16667;
            queue_.pop_front();
            drainLocked();
        }
        drainLocked();
    }
}

// ============================== Audio ==============================

AudioPipeline::AudioPipeline(EventSink sink) : sink_(std::move(sink)) {
    thread_ = std::thread(&AudioPipeline::loop, this);
}

AudioPipeline::~AudioPipeline() {
    running_ = false;
    cv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void AudioPipeline::push(int ct, const uint8_t *data, size_t len, uint32_t rtp) {
    if (!data || len == 0) return;
    std::lock_guard<std::mutex> lk(m_);
    lastPacket_ = std::chrono::steady_clock::now();
    if (idle_) { idle_ = false; sink_("audio_resumed", ""); }
    queue_.push_back({ct, std::vector<uint8_t>(data, data + len), rtp});
    while (queue_.size() > 400) queue_.pop_front();   // never let audio lag build up
    cv_.notify_all();
}

float AudioPipeline::gainForDb(float db) {
    // AirPlay sends -144 for mute, otherwise -30..0 dB.
    if (db <= -144.0f) return 0.0f;
    return std::pow(10.0f, std::min(0.0f, std::max(db, -30.0f)) / 20.0f);
}

void AudioPipeline::setVolumeDb(float db) { gain_ = gainForDb(db); }

bool AudioPipeline::validFrame(int ct, const uint8_t *data, size_t len) {
    if (!data || len == 0) return false;
    switch (ct) {
    case 2: return data[0] == 0x20;
    case 8: return data[0] == 0x8c || data[0] == 0x8d || data[0] == 0x8e ||
                   data[0] == 0x80 || data[0] == 0x81 || data[0] == 0x82;
    case 4: return data[0] == 0xff;
    default: return true;
    }
}

int AudioPipeline::decodeAlac(ALACDecoder &dec, const uint8_t *data, size_t len, int16_t *out, uint32_t maxFrames) {
    // The hardened decoder (alac_hardened/) bounds every read by `len` and every write by the frame
    // length, so it decodes straight from the packet: no copy, no padding. It never writes to the input.
    const uint32_t frameLength = dec.mConfig.frameLength;
    if (!validFrame(2, data, len) || len > 65535 || frameLength == 0 || maxFrames < frameLength) return -1;
    BitBuffer bits;
    BitBufferInit(&bits, const_cast<uint8_t *>(data), (uint32_t) len);
    uint32_t frames = 0;
    if (dec.Decode(&bits, (uint8_t *) out, frameLength, kChannels, &frames) != 0) return -1;
    return (int) std::min(frames, frameLength);
}

void AudioPipeline::flush() {
    std::lock_guard<std::mutex> lk(m_);
    queue_.clear();
    idle_ = true;   // the next packet reports audio_resumed
}

void AudioPipeline::reset() {
    std::lock_guard<std::mutex> lk(m_);
    idle_ = true;
    queue_.clear();
    queue_.push_back({-1, {}, 0});   // sentinel: the audio thread releases decoder + stream
    cv_.notify_all();
}

bool AudioPipeline::ensureDecoderLocked(int ct) {
    if (ct == ct_ && (aac_ || alac_)) return true;
    releaseDecoderLocked();
    if (ct == 8 || ct == 4) {
        aac_ = AMediaCodec_createDecoderByType("audio/mp4a-latm");
        if (!aac_) { LOGE("no AAC decoder"); return false; }
        AMediaFormat *f = AMediaFormat_new();
        AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, kSampleRate);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, kChannels);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_IS_ADTS, 0);
        if (ct == 8) {
            AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_AAC_PROFILE, 39);   // AAC-ELD
            AMediaFormat_setBuffer(f, "csd-0", (void *) kAirPlayAacEldCsd, sizeof(kAirPlayAacEldCsd));
        } else {
            AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_AAC_PROFILE, 2);    // AAC-LC
            AMediaFormat_setBuffer(f, "csd-0", (void *) kAirPlayAacLcCsd, sizeof(kAirPlayAacLcCsd));
        }
        bool ok = AMediaCodec_configure(aac_, f, nullptr, nullptr, 0) == AMEDIA_OK && AMediaCodec_start(aac_) == AMEDIA_OK;
        AMediaFormat_delete(f);
        if (!ok) { LOGE("AAC decoder start failed (ct=%d)", ct); AMediaCodec_delete(aac_); aac_ = nullptr; return false; }
    } else if (ct == 2) {
        alac_ = new ALACDecoder();
        if (alac_->Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) != 0) {
            LOGE("ALAC init failed"); delete alac_; alac_ = nullptr; return false;
        }
    } else {
        LOGW("unsupported audio compression type ct=%d", ct);
        return false;
    }
    ct_ = ct;
    LOGI("audio decoder ready: ct=%d (%s)", ct, ct == 2 ? "ALAC" : ct == 8 ? "AAC-ELD" : "AAC-LC");
    sink_("audio_codec", std::to_string(ct));
    return true;
}

void AudioPipeline::releaseDecoderLocked() {
    if (aac_) { AMediaCodec_stop(aac_); AMediaCodec_delete(aac_); aac_ = nullptr; }
    delete alac_;
    alac_ = nullptr;
    ct_ = -1;
}

bool AudioPipeline::ensureStream(bool lowLatency) {
    if (stream_ && streamLowLatency_ == lowLatency) return true;
    closeStream();
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setSampleRate(b, kSampleRate);
    AAudioStreamBuilder_setChannelCount(b, kChannels);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(b, lowLatency ? AAUDIO_PERFORMANCE_MODE_LOW_LATENCY
                                                         : AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStream *s = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGE("AAudio open failed: %s", AAudio_convertResultToText(r)); return false; }
    if (AAudioStream_requestStart(s) != AAUDIO_OK) { AAudioStream_close(s); return false; }
    stream_ = s;
    streamLowLatency_ = lowLatency;
    LOGI("audio output open (%s, burst %d frames)", lowLatency ? "low latency" : "normal",
         AAudioStream_getFramesPerBurst(s));
    return true;
}

void AudioPipeline::closeStream() {
    if (!stream_) return;
    AAudioStream_requestStop(stream_);
    AAudioStream_close(stream_);
    stream_ = nullptr;
}

void AudioPipeline::writePcm(int16_t *pcm, int frames) {
    if (frames <= 0) return;
    float g = muted_ ? 0.0f : gain_.load();
    if (g < 0.999f) {
        for (int i = 0; i < frames * kChannels; i++) pcm[i] = (int16_t) std::lround(pcm[i] * g);
    }
    // Mirroring audio (AAC-ELD) wants low latency; music (ALAC) prefers a deeper, steadier buffer.
    if (!ensureStream(ct_ == 8)) return;
    aaudio_result_t r = AAudioStream_write(stream_, pcm, frames, 50 * 1000000LL);
    if (r == AAUDIO_ERROR_DISCONNECTED) closeStream();   // output device changed; reopen next time
}

void AudioPipeline::loop() {
    std::vector<int16_t> pcm(4096 * kChannels);
    while (running_) {
        Packet p;
        {
            std::unique_lock<std::mutex> lk(m_);
            cv_.wait_for(lk, std::chrono::milliseconds(50), [&] { return !running_ || !queue_.empty(); });
            if (!running_) break;
            if (queue_.empty()) {
                // No packets for a moment while a stream was active: the sender paused.
                if (!idle_ && std::chrono::steady_clock::now() - lastPacket_ > std::chrono::milliseconds(500)) {
                    idle_ = true;
                    sink_("audio_idle", "");
                }
                continue;
            }
            p = std::move(queue_.front());
            queue_.pop_front();
        }
        // Decoder and stream are owned by this thread only.
        if (p.ct < 0) { releaseDecoderLocked(); closeStream(); continue; }
        if (!ensureDecoderLocked(p.ct)) continue;

        if (!validFrame(p.ct, p.data.data(), p.data.size())) {
            LOGW("skipping invalid audio frame (ct=%d, first byte 0x%02x)", p.ct, p.data.empty() ? 0 : p.data[0]);
            continue;
        }
        if (alac_) {
            int frames = decodeAlac(*alac_, p.data.data(), p.data.size(), pcm.data(), (uint32_t) (pcm.size() / kChannels));
            if (frames > 0) writePcm(pcm.data(), frames);
            // Report the playhead (RTP of the frame just played), like shairport-sync's FramePosition.
            auto now = std::chrono::steady_clock::now();
            if (p.rtp && now - lastPlayheadReport_ > std::chrono::milliseconds(500)) {
                lastPlayheadReport_ = now;
                sink_("audio_rtp", std::to_string(p.rtp));
            }
        } else if (aac_) {
            ssize_t in = AMediaCodec_dequeueInputBuffer(aac_, 10000);
            if (in >= 0) {
                size_t cap = 0;
                uint8_t *buf = AMediaCodec_getInputBuffer(aac_, (size_t) in, &cap);
                size_t n = std::min(cap, p.data.size());
                memcpy(buf, p.data.data(), n);
                AMediaCodec_queueInputBuffer(aac_, (size_t) in, 0, n, 0, 0);
            }
            AMediaCodecBufferInfo info;
            for (;;) {
                ssize_t o = AMediaCodec_dequeueOutputBuffer(aac_, &info, 2000);
                if (o < 0) { if (o == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || o == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue; break; }
                size_t sz = 0;
                uint8_t *ob = AMediaCodec_getOutputBuffer(aac_, (size_t) o, &sz);
                int frames = info.size / (int) (sizeof(int16_t) * kChannels);
                frames = std::min(frames, (int) (pcm.size() / kChannels));
                if (ob && frames > 0) {
                    memcpy(pcm.data(), ob + info.offset, (size_t) frames * sizeof(int16_t) * kChannels);
                    writePcm(pcm.data(), frames);
                }
                AMediaCodec_releaseOutputBuffer(aac_, (size_t) o, false);
            }
        }
    }
    releaseDecoderLocked();
    closeStream();
}
