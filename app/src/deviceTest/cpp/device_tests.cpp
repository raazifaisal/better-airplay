/*
 * device_tests.cpp - native tests that run ON the TV (real ARM CPU, real decoders).
 * Built by tools/device-tests.sh, pushed over adb, exit code = number of failures.
 */
#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"
#include "ALACEncoder.h"
#include "media_pipeline.h"
#include "playback_info_cache.h"
#include <atomic>
#include <thread>

static int g_failures = 0, g_checks = 0;
#define CHECK(cond) do { g_checks++; if (!(cond)) { g_failures++; printf("    FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } } while (0)

static const char *g_filter = nullptr;   // optional argv[1]: run only tests whose name contains it

static void run(const char *name, const std::function<void()> &fn) {
    if (g_filter && !strstr(name, g_filter)) return;
    int before = g_failures;
    printf("RUN  %s\n", name);   // printed first so a crash still names the test
    fn();
    printf("%s %s\n", g_failures == before ? "PASS" : "FAIL", name);
}

// ---------------------------------------------------------------- ALAC

static void alacInitWithAirPlayConfig() {
    // Regression: on ARM the decoder misread this big-endian config (endianness macro missing) and failed.
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    CHECK(dec.mConfig.frameLength == 352);
    CHECK(dec.mConfig.sampleRate == 44100);
    CHECK(dec.mConfig.numChannels == 2);
    CHECK(dec.mConfig.bitDepth == 16);
}

static void alacLosslessRoundTrip() {
    const int frames = 352, ch = 2;
    std::vector<int16_t> pcm(frames * ch);
    for (int i = 0; i < frames; i++) {   // stereo test signal: two tones + a little noise
        pcm[i * 2] = (int16_t) (12000 * std::sin(2 * M_PI * 440.0 * i / 44100) + (i * 7919 % 31) - 15);
        pcm[i * 2 + 1] = (int16_t) (9000 * std::sin(2 * M_PI * 660.0 * i / 44100));
    }
    AudioFormatDescription in{44100, kALACFormatLinearPCM, kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked,
                              4, 1, 4, 2, 16, 0};
    AudioFormatDescription out{44100, kALACFormatAppleLossless, 1 /* 16-bit */, 0, (uint32_t) frames, 0, 2, 0, 0};
    ALACEncoder enc;
    enc.SetFrameSize(frames);
    CHECK(enc.InitializeEncoder(out) == 0);
    std::vector<uint8_t> packet(frames * ch * 2 + 64);
    int32_t bytes = frames * ch * 2;
    CHECK(enc.Encode(in, out, (unsigned char *) pcm.data(), packet.data(), &bytes) == 0);
    CHECK(bytes > 0 && bytes < frames * ch * 2 + 64);

    ALACDecoder dec;   // decode with the exact config the app uses for AirPlay
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    BitBuffer bits;
    BitBufferInit(&bits, packet.data(), (uint32_t) bytes);
    std::vector<int16_t> outPcm(frames * ch);
    uint32_t outFrames = 0;
    CHECK(dec.Decode(&bits, (uint8_t *) outPcm.data(), frames, ch, &outFrames) == 0);
    CHECK(outFrames == (uint32_t) frames);
    CHECK(memcmp(pcm.data(), outPcm.data(), pcm.size() * sizeof(int16_t)) == 0);
}

static void alacSurvivesGarbagePackets() {
    // Regression: a malformed packet made the decoder read past the buffer (SIGSEGV on the TV).
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    std::vector<int16_t> out(4096 * 2);
    uint32_t seed = 12345;
    int decoded = 0, rejected = 0;
    for (int i = 0; i < 5000; i++) {
        size_t len = 1 + (seed = seed * 1103515245 + 12345) % 1500;
        std::vector<uint8_t> pkt(len);
        for (auto &b : pkt) b = (uint8_t) ((seed = seed * 1103515245 + 12345) >> 16);
        if (i % 2 == 0) pkt[0] = 0x20;   // half look like ALAC frames, half don't
        int frames = AudioPipeline::decodeAlac(dec, pkt.data(), pkt.size(), out.data(), 4096);
        if (frames < 0) rejected++; else decoded++;
        CHECK(frames <= 4096);
    }
    CHECK(rejected >= 2500);   // every non-0x20 packet is rejected before decoding
    printf("    fuzz: %d decoded, %d rejected, no crash\n", decoded, rejected);
}

// ---- ALAC hardening: exact-size buffers, no padding (FFmpeg-style checked reads) ----

/** Places data so its last byte is followed by an unreadable page: any over-read faults (Electric Fence). */
class GuardedBuffer {
public:
    GuardedBuffer() {
        page_ = (size_t) sysconf(_SC_PAGESIZE);
        size_ = page_ * 3;
        base_ = (uint8_t *) mmap(nullptr, size_, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        mprotect(base_ + page_ * 2, page_, PROT_NONE);
    }
    ~GuardedBuffer() { munmap(base_, size_); }
    const uint8_t *place(const uint8_t *data, size_t len) {   // len <= 2 pages
        uint8_t *p = base_ + page_ * 2 - len;
        memcpy(p, data, len);
        return p;
    }
private:
    uint8_t *base_; size_t page_, size_;
};

struct BitWriter {
    std::vector<uint8_t> bytes; int n = 0;
    void put(uint32_t v, int bits) {
        for (int i = bits - 1; i >= 0; i--, n++) {
            if (n % 8 == 0) bytes.push_back(0);
            if ((v >> i) & 1) bytes.back() |= (uint8_t) (0x80 >> (n % 8));
        }
    }
};

/** Real ALAC packets from Apple's encoder: tone (compressed), noise (escape/uncompressed), silence, partial. */
static std::vector<std::vector<uint8_t>> realAlacPackets(std::vector<std::vector<int16_t>> *pcmOut = nullptr) {
    std::vector<std::vector<uint8_t>> packets;
    uint32_t seed = 99;
    for (int kind = 0; kind < 4; kind++) {
        const int frames = kind == 3 ? 100 : 352, ch = 2;
        std::vector<int16_t> pcm(frames * ch);
        for (int i = 0; i < frames; i++) {
            int16_t l = 0, r = 0;
            if (kind == 0 || kind == 3) { l = (int16_t) (12000 * std::sin(2 * M_PI * 440.0 * i / 44100)); r = (int16_t) (l / 2); }
            if (kind == 1) { l = (int16_t) ((seed = seed * 1103515245 + 12345) >> 16); r = (int16_t) ((seed = seed * 1103515245 + 12345) >> 16); }
            pcm[i * 2] = l; pcm[i * 2 + 1] = r;
        }
        AudioFormatDescription in{44100, kALACFormatLinearPCM, kALACFormatFlagIsSignedInteger | kALACFormatFlagIsPacked,
                                  4, 1, 4, 2, 16, 0};
        AudioFormatDescription out{44100, kALACFormatAppleLossless, 1, 0, 352, 0, 2, 0, 0};
        ALACEncoder enc;
        enc.SetFrameSize(352);
        enc.InitializeEncoder(out);
        std::vector<uint8_t> packet(352 * ch * 2 + 64);
        int32_t bytes = frames * ch * 2;
        if (enc.Encode(in, out, (unsigned char *) pcm.data(), packet.data(), &bytes) != 0) continue;
        packet.resize((size_t) bytes);
        packets.push_back(packet);
        if (pcmOut) pcmOut->push_back(pcm);
    }
    return packets;
}

static void alacDecodesExactSizeBuffers() {
    // Every real packet, and every truncation of it, ends right at a guard page: no read may cross it.
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    std::vector<std::vector<int16_t>> pcm;
    auto packets = realAlacPackets(&pcm);
    CHECK(packets.size() == 4);
    GuardedBuffer guard;
    std::vector<int16_t> out(352 * 2);
    for (size_t k = 0; k < packets.size(); k++) {
        const auto &p = packets[k];
        CHECK(p[0] == 0x20);   // stereo element, as AirPlay sends
        const uint8_t *at = guard.place(p.data(), p.size());
        int frames = AudioPipeline::decodeAlac(dec, at, p.size(), out.data(), 352);
        int want = (int) (pcm[k].size() / 2);
        CHECK(frames == want);   // includes the 100-sample partial frame
        CHECK(frames == want && memcmp(out.data(), pcm[k].data(), (size_t) want * 4) == 0);
        for (size_t len = 1; len < p.size(); len++) {
            at = guard.place(p.data(), len);
            CHECK(AudioPipeline::decodeAlac(dec, at, len, out.data(), 352) <= 352);
        }
    }
}

static void alacRejectsOversizedPartialFrame() {
    // Apple's decoder trusts the 32-bit partial-frame sample count and writes that many samples into
    // frameLength-sized buffers. FFmpeg rejects counts above the frame length; so must we.
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    GuardedBuffer guard;
    for (uint32_t samples : {353u, 4096u, 100000u, 0xFFFFFFFFu}) {
        BitWriter w;
        w.put(1, 3);       // ID_CPE (stereo)
        w.put(0, 4);       // element instance tag
        w.put(0, 12);      // unused header
        w.put(0x9, 4);     // partial frame + escape (uncompressed) flags
        w.put(samples, 32);
        w.bytes.resize(3000, 0x00);
        std::vector<int16_t> out(352 * 2 + 64, 0x7A7A);   // tail is a canary
        const uint8_t *at = guard.place(w.bytes.data(), w.bytes.size());
        CHECK(w.bytes[0] == 0x20);
        CHECK(AudioPipeline::decodeAlac(dec, at, w.bytes.size(), out.data(), 352) == -1);
        bool canary = true;
        for (size_t i = 352 * 2; i < out.size(); i++) canary &= out[i] == 0x7A7A;
        CHECK(canary);
    }
}

static void alacMutatedPacketsFuzz() {
    // Bit-flipped real packets get past the header checks, unlike random bytes, so they reach deep code.
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    auto packets = realAlacPackets();
    GuardedBuffer guard;
    std::vector<int16_t> out(352 * 2 + 64);
    uint32_t seed = 7;
    int decoded = 0, rejected = 0;
    bool canaryOk = true, boundsOk = true;
    for (int i = 0; i < 40000; i++) {
        std::vector<uint8_t> p = packets[(size_t) i % packets.size()];
        int flips = 1 + (int) ((seed = seed * 1103515245 + 12345) >> 16) % 8;
        for (int f = 0; f < flips; f++) {
            seed = seed * 1103515245 + 12345;
            size_t bit = (seed >> 8) % (p.size() * 8);
            if (bit >= 8) p[bit / 8] ^= (uint8_t) (0x80 >> (bit % 8));   // keep byte 0 so it passes validFrame
        }
        if (i % 5 == 0) p.resize(1 + (seed >> 4) % p.size());          // and truncations
        std::fill(out.begin() + 352 * 2, out.end(), (int16_t) 0x7A7A);
        const uint8_t *at = guard.place(p.data(), p.size());
        int frames = AudioPipeline::decodeAlac(dec, at, p.size(), out.data(), 352);
        if (frames < 0) rejected++; else decoded++;
        boundsOk &= frames <= 352;
        for (size_t j = 352 * 2; j < out.size(); j++) canaryOk &= out[j] == 0x7A7A;
    }
    CHECK(boundsOk);
    CHECK(canaryOk);
    printf("    mutation fuzz: %d decoded, %d rejected, no over-read or over-write\n", decoded, rejected);
}

static void alacDecodeCost() {
    // Measures what the per-packet safety costs on this CPU, against the old 16 KB padded copy.
    ALACDecoder dec;
    CHECK(dec.Init((void *) kAirPlayAlacConfig, sizeof(kAirPlayAlacConfig)) == 0);
    auto packets = realAlacPackets();
    std::vector<int16_t> out(352 * 2);
    const int n = 20000;
    auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < n; i++) {
        const auto &p = packets[(size_t) i % 3];
        AudioPipeline::decodeAlac(dec, p.data(), p.size(), out.data(), 352);
    }
    auto t1 = std::chrono::steady_clock::now();
    std::vector<uint8_t> padded;
    for (int i = 0; i < n; i++) {
        const auto &p = packets[(size_t) i % 3];
        padded.assign(p.size() + 16384, 0);
        memcpy(padded.data(), p.data(), p.size());
    }
    auto t2 = std::chrono::steady_clock::now();
    double decodeUs = std::chrono::duration<double, std::micro>(t1 - t0).count() / n;
    double padUs = std::chrono::duration<double, std::micro>(t2 - t1).count() / n;
    printf("    per packet: checked decode %.1f us, old 16 KB pad+copy alone %.1f us (packet = 7982 us of audio)\n",
           decodeUs, padUs);
    CHECK(decodeUs < 800);   // a packet is 8 ms of audio; decoding must stay a small fraction of it
}

static void aacEldValidation() {
    const uint8_t ok[] = {0x8d, 0x01}, bad[] = {0x20, 0x01};
    CHECK(AudioPipeline::validFrame(8, ok, sizeof(ok)));
    CHECK(!AudioPipeline::validFrame(8, bad, sizeof(bad)));
    CHECK(AudioPipeline::validFrame(2, bad, sizeof(bad)));   // 0x20 = ALAC
    CHECK(!AudioPipeline::validFrame(2, ok, sizeof(ok)));
    CHECK(!AudioPipeline::validFrame(2, ok, 0));
}

// ---------------------------------------------------------------- pure helpers

static void gopDetection() {
    const uint8_t h264Sps[] = {0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28};
    const uint8_t h264Idr[] = {0, 0, 0, 1, 0x65, 0x88, 0x84};
    const uint8_t hevcVps[] = {0, 0, 0, 1, 0x40, 0x01, 0x0c};
    const uint8_t hevcIdr[] = {0, 0, 0, 1, 0x26, 0x01, 0xaf};
    CHECK(VideoPipeline::startsGop(h264Sps, sizeof(h264Sps), false));
    CHECK(!VideoPipeline::startsGop(h264Idr, sizeof(h264Idr), false));
    CHECK(VideoPipeline::startsGop(hevcVps, sizeof(hevcVps), true));
    CHECK(!VideoPipeline::startsGop(hevcIdr, sizeof(hevcIdr), true));
    CHECK(!VideoPipeline::startsGop(h264Sps, 3, false));   // too short to hold a start code
}

static void volumeCurve() {
    CHECK(AudioPipeline::gainForDb(-144.0f) == 0.0f);
    CHECK(std::fabs(AudioPipeline::gainForDb(0.0f) - 1.0f) < 1e-6f);
    CHECK(std::fabs(AudioPipeline::gainForDb(-30.0f) - 0.0316f) < 1e-3f);
    CHECK(AudioPipeline::gainForDb(-50.0f) == AudioPipeline::gainForDb(-30.0f));   // clamped
    CHECK(AudioPipeline::gainForDb(6.0f) <= 1.0f);                                  // never amplify
}

// ---------------------------------------------------------------- real hardware on this TV

static bool configures(const char *mime, int w, int h, const uint8_t *csd, size_t csdLen, bool audio) {
    AMediaCodec *c = AMediaCodec_createDecoderByType(mime);
    if (!c) return false;
    AMediaFormat *f = AMediaFormat_new();
    AMediaFormat_setString(f, AMEDIAFORMAT_KEY_MIME, mime);
    if (audio) {
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_SAMPLE_RATE, 44100);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_IS_ADTS, 0);
        if (csd) AMediaFormat_setBuffer(f, "csd-0", (void *) csd, csdLen);
    } else {
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_WIDTH, w);
        AMediaFormat_setInt32(f, AMEDIAFORMAT_KEY_HEIGHT, h);
    }
    bool ok = AMediaCodec_configure(c, f, nullptr, nullptr, 0) == AMEDIA_OK && AMediaCodec_start(c) == AMEDIA_OK;
    AMediaCodec_stop(c);
    AMediaCodec_delete(c);
    AMediaFormat_delete(f);
    return ok;
}

static void decodersAvailable() {
    CHECK(configures("video/hevc", 3840, 2160, nullptr, 0, false));   // 4K mirroring
    CHECK(configures("video/avc", 1920, 1080, nullptr, 0, false));    // H.264 fallback
    CHECK(configures("audio/mp4a-latm", 0, 0, kAirPlayAacEldCsd, sizeof(kAirPlayAacEldCsd), true));   // mirror audio
    CHECK(configures("audio/mp4a-latm", 0, 0, kAirPlayAacLcCsd, sizeof(kAirPlayAacLcCsd), true));
}

static void audioOutputOpens() {
    for (auto mode : {AAUDIO_PERFORMANCE_MODE_LOW_LATENCY, AAUDIO_PERFORMANCE_MODE_NONE}) {
        AAudioStreamBuilder *b = nullptr;
        CHECK(AAudio_createStreamBuilder(&b) == AAUDIO_OK);
        AAudioStreamBuilder_setSampleRate(b, 44100);
        AAudioStreamBuilder_setChannelCount(b, 2);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setPerformanceMode(b, mode);
        AAudioStream *s = nullptr;
        CHECK(AAudioStreamBuilder_openStream(b, &s) == AAUDIO_OK);
        if (s) AAudioStream_close(s);
        AAudioStreamBuilder_delete(b);
    }
}

// ---------------------------------------------------------------- video handover: /playback-info cache

// Same field names as UxPlay's playback_info_t (raop.h); fillPlaybackInfo is a template over them.
struct FakePlaybackInfo {
    uint32_t stallcount = 99;
    double duration = 99, position = 99, seek_start = 99, seek_duration = 99;
    float rate = 99;
    bool ready_to_play = true, playback_buffer_empty = true, playback_buffer_full = true, playback_likely_to_keep_up = true;
    int num_loaded_time_ranges = 9, num_seekable_time_ranges = 9;
    void *loadedTimeRanges = (void *) 1, *seekableTimeRanges = (void *) 1;
};

static void playbackInfoPlaying() {
    PlaybackSample s;
    s.duration = 600; s.position = 30; s.rate = 1; s.bufferEmpty = false; s.bufferFull = true; s.ready = true;
    FakePlaybackInfo o;
    fillPlaybackInfo(s, &o);
    CHECK(o.duration == 600 && o.position == 30 && o.rate == 1.0f);
    CHECK(o.seek_start == 0 && o.seek_duration == 600);
    CHECK(o.ready_to_play && o.playback_likely_to_keep_up && o.playback_buffer_full && !o.playback_buffer_empty);
    CHECK(o.stallcount == 0 && o.loadedTimeRanges == nullptr && o.num_seekable_time_ranges == 0);
}

static void playbackInfoEndedReleasesThePhone() {
    // UxPlay treats duration == -1 as "video finished": it resets HLS and drops the connection.
    PlaybackSample s;
    s.duration = 600; s.position = 600; s.ended = true; s.ready = true;
    FakePlaybackInfo o;
    fillPlaybackInfo(s, &o);
    CHECK(o.duration == -1.0 && o.position == -1.0 && o.rate == 0 && !o.ready_to_play);
}

static void playbackInfoNotReadyAndClamping() {
    FakePlaybackInfo o;
    fillPlaybackInfo(PlaybackSample{}, &o);   // nothing loaded yet: a valid, empty answer (not "finished")
    CHECK(o.duration == 0 && o.position == 0 && o.rate == 0 && !o.ready_to_play && !o.playback_likely_to_keep_up);
    PlaybackSample s;
    s.duration = 100; s.position = 250; s.rate = 1; s.ready = true;
    fillPlaybackInfo(s, &o);
    CHECK(o.position == 100);                  // never past the end
    s.position = -5; s.duration = -1;          // live/unknown duration, bogus position
    fillPlaybackInfo(s, &o);
    CHECK(o.position == 0 && o.duration == 0 && o.seek_duration == 0);
}

static void playbackInfoCacheIsConsistentAcrossThreads() {
    // Writer publishes samples where position == duration / 2; readers must never see a torn mix.
    PlaybackInfoCache cache;
    std::atomic<bool> stop{false};
    std::atomic<int> torn{0};
    std::thread writer([&] {
        for (int i = 1; i <= 200000; i++) {
            PlaybackSample s;
            s.duration = i * 2.0; s.position = i; s.ready = true; s.rate = 1;
            cache.update(s);
        }
        stop = true;
    });
    std::vector<std::thread> readers;
    for (int r = 0; r < 3; r++) readers.emplace_back([&] {
        while (!stop) {
            PlaybackSample s = cache.get();
            if (s.ready && s.position * 2 != s.duration) torn++;
        }
    });
    writer.join();
    for (auto &t : readers) t.join();
    CHECK(torn == 0);
    cache.reset();
    CHECK(!cache.get().ready && cache.get().position == 0);
}

int main(int argc, char **argv) {
    if (argc > 1) g_filter = argv[1];
    setvbuf(stdout, nullptr, _IONBF, 0);   // unbuffered: output survives a crash
    run("alac: init with AirPlay config (endianness regression)", alacInitWithAirPlayConfig);
    run("alac: lossless round trip with AirPlay config", alacLosslessRoundTrip);
    run("alac: 5000 garbage packets don't crash the decoder", alacSurvivesGarbagePackets);
    run("alac: real + partial packets decode from exact-size buffers", alacDecodesExactSizeBuffers);
    run("alac: oversized partial-frame sample count is rejected", alacRejectsOversizedPartialFrame);
    run("alac: 40000 bit-flipped real packets, no over-read/over-write", alacMutatedPacketsFuzz);
    run("alac: per-packet decode cost", alacDecodeCost);
    run("audio: frame validation (ALAC 0x20, AAC-ELD 0x8x)", aacEldValidation);
    run("video: GOP detection (H.264 / HEVC)", gopDetection);
    run("audio: AirPlay volume curve", volumeCurve);
    run("handover: playback-info while playing", playbackInfoPlaying);
    run("handover: ended video reports -1 (releases the phone)", playbackInfoEndedReleasesThePhone);
    run("handover: not-ready answer and clamping", playbackInfoNotReadyAndClamping);
    run("handover: playback-info cache is consistent across threads", playbackInfoCacheIsConsistentAcrossThreads);
    run("device: HEVC 4K, H.264, AAC-ELD, AAC-LC decoders configure", decodersAvailable);
    run("device: AAudio 44.1k stereo output opens", audioOutputOpens);
    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures;
}
