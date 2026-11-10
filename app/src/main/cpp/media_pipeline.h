/*
 * media_pipeline.h - hardware video decode to a Surface (NDK MediaCodec) and audio decode
 * (AAC-ELD / AAC-LC via MediaCodec, ALAC via Apple's decoder) to AAudio. GPL-3.0.
 */
#pragma once

#include <android/native_window.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct AMediaCodec;
struct AAudioStreamStruct;
class ALACDecoder;

/** ALACSpecificConfig AirPlay uses: frameLength 352, 16-bit, pb 40, mb 10, kb 14, 2 ch, maxRun 255, 44100 Hz. */
inline constexpr uint8_t kAirPlayAlacConfig[24] = {0x00, 0x00, 0x01, 0x60, 0x00, 0x10, 0x28, 0x0a, 0x0e, 0x02, 0x00, 0xff,
                                                   0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xac, 0x44};
inline constexpr uint8_t kAirPlayAacEldCsd[4] = {0xf8, 0xe8, 0x50, 0x00};   // AAC-ELD 44100 stereo, 480 spf
inline constexpr uint8_t kAirPlayAacLcCsd[2] = {0x12, 0x10};                // AAC-LC 44100 stereo

using EventSink = std::function<void(const char *event, const std::string &arg)>;

/** Mirrored video: Annex-B H.264/HEVC access units -> MediaCodec -> ANativeWindow, rendered immediately. */
class VideoPipeline {
public:
    explicit VideoPipeline(EventSink sink);
    ~VideoPipeline();

    void setWindow(ANativeWindow *window);        // takes a reference; nullptr detaches
    void configure(bool hevc, int maxWidth, int maxHeight);
    void push(const uint8_t *data, size_t len);   // one access unit (may carry VPS/SPS/PPS in-band)
    void flush();                                 // drop queued data, keep codec
    void reset();                                 // release codec and cached GOP (session ended)
    void setLowLatency(bool enabled) { lowLatency_ = enabled; }

    /** True if an Annex-B access unit begins a new GOP (carries SPS, or VPS/SPS for HEVC). */
    static bool startsGop(const uint8_t *d, size_t n, bool hevc);

private:
    void loop();
    bool ensureCodecLocked();
    void releaseCodecLocked();
    void drainLocked();

    EventSink sink_;
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<std::vector<uint8_t>> queue_;
    std::vector<std::vector<uint8_t>> gop_;   // since last parameter sets, to re-prime a new codec
    size_t gopBytes_ = 0;
    ANativeWindow *window_ = nullptr;
    AMediaCodec *codec_ = nullptr;
    bool hevc_ = false;
    bool configured_ = false;
    bool needsReprime_ = false;
    int maxW_ = 1920, maxH_ = 1080;
    int64_t ptsUs_ = 0;
    std::atomic<bool> lowLatency_{true};
    std::atomic<bool> running_{true};
    std::thread thread_;
};

/** AirPlay audio: ct=8 AAC-ELD (mirroring), ct=4 AAC-LC, ct=2 ALAC (music) -> PCM S16 44.1k stereo -> AAudio. */
class AudioPipeline {
public:
    explicit AudioPipeline(EventSink sink);
    ~AudioPipeline();

    void push(int ct, const uint8_t *data, size_t len, uint32_t rtp = 0);
    void setVolumeDb(float db);     // AirPlay volume: -30..0 dB, -144 = mute
    /** Linear gain for an AirPlay volume in dB (-144 = mute, otherwise clamped to -30..0). */
    static float gainForDb(float db);
    /** First-byte sanity check before decoding (as UxPlay does): ALAC frames start 0x20, AAC-ELD 0x80-0x82/0x8c-0x8e. */
    static bool validFrame(int ct, const uint8_t *data, size_t len);
    /**
     * Decode one ALAC packet safely: rejects invalid frames and decodes from a zero-padded copy, because the
     * bit reader can run past the end of a short/corrupt packet. Returns frames decoded, or -1 if rejected.
     */
    static int decodeAlac(ALACDecoder &dec, const uint8_t *data, size_t len, int16_t *out, uint32_t maxFrames);
    void setMuted(bool muted) { muted_ = muted; }
    void flush();
    void reset();

private:
    struct Packet { int ct; std::vector<uint8_t> data; uint32_t rtp; };
    void loop();
    bool ensureDecoderLocked(int ct);
    void releaseDecoderLocked();
    bool ensureStream(bool lowLatency);
    void closeStream();
    void writePcm(int16_t *pcm, int frames);

    EventSink sink_;
    std::mutex m_;
    std::condition_variable cv_;
    std::deque<Packet> queue_;
    int ct_ = -1;
    AMediaCodec *aac_ = nullptr;
    ALACDecoder *alac_ = nullptr;
    AAudioStreamStruct *stream_ = nullptr;
    bool streamLowLatency_ = false;
    std::atomic<float> gain_{1.0f};
    // Fast pause detection: iOS flushes instantly on pause but tears the stream down ~8 s later.
    std::chrono::steady_clock::time_point lastPacket_{};
    bool idle_ = true;   // guarded by m_
    std::chrono::steady_clock::time_point lastPlayheadReport_{};   // audio thread only
    std::atomic<bool> muted_{false};
    std::atomic<bool> running_{true};
    std::thread thread_;
};
