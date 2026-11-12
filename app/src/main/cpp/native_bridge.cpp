/*
 * native_bridge.cpp - JNI glue between the UxPlay AirPlay core and the Kotlin app.
 *
 * Starts/stops the receiver, advertises through NsdManager, reports session events to Kotlin,
 * and routes mirrored video / AirPlay audio into the native media pipelines (media_pipeline.h).
 *
 * GPL-3.0 (links UxPlay).
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "media_pipeline.h"
#include "playback_info_cache.h"
#include <atomic>
#include <mutex>
#include <string>
#include <cstring>
#include <cstdio>

extern "C" {
#include "raop.h"
#include "dnssd.h"
#include "stream.h"
#include "logger.h"
#include "android_dnssd.h"
}

#define TAG "BetterAirPlay/native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jclass g_bridge = nullptr;          // dev.better.airplay.data.engine.NativeBridge
jmethodID g_onLog = nullptr;        // (ILjava/lang/String;)V
jmethodID g_onEvent = nullptr;      // (Ljava/lang/String;Ljava/lang/String;)V
jmethodID g_onMdnsRegister = nullptr;   // (Ljava/lang/String;Ljava/lang/String;I[B)I
jmethodID g_onMdnsUnregister = nullptr; // (Ljava/lang/String;)V
jmethodID g_onBinary = nullptr;     // (Ljava/lang/String;[B)V

VideoPipeline *g_video = nullptr;   // process-lifetime, created in JNI_OnLoad
AudioPipeline *g_audio = nullptr;
std::atomic<int> g_maxW{1920}, g_maxH{1080};

std::mutex g_lock;
raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
unsigned short g_port = 0;
/* UxPlay keeps a pointer to this (raop_set_lang): it must outlive g_raop. */
std::string g_lang_system;

/* Handover video player state, pushed down from Kotlin; read when the phone polls /playback-info. */
PlaybackInfoCache g_playback;

std::atomic<uint64_t> g_video_frames{0};
std::atomic<uint64_t> g_audio_packets{0};

/* Attach the calling (possibly native) thread to the JVM for the duration of a callback. */
struct JniEnv {
    JNIEnv *env = nullptr;
    bool attached = false;
    JniEnv() {
        if (!g_vm) return;
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true; else env = nullptr;
        }
    }
    ~JniEnv() { if (attached) g_vm->DetachCurrentThread(); }
};

void emit_event(const char *event, const std::string &arg) {
    JniEnv j;
    if (!j.env || !g_bridge) return;
    jstring e = j.env->NewStringUTF(event);
    jstring a = j.env->NewStringUTF(arg.c_str());
    j.env->CallStaticVoidMethod(g_bridge, g_onEvent, e, a);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
    j.env->DeleteLocalRef(e);
    j.env->DeleteLocalRef(a);
}

void emit_binary(const char *event, const void *data, int len) {
    JniEnv j;
    if (!j.env || !g_bridge || !data || len <= 0) return;
    jstring e = j.env->NewStringUTF(event);
    jbyteArray b = j.env->NewByteArray(len);
    j.env->SetByteArrayRegion(b, 0, len, reinterpret_cast<const jbyte *>(data));
    j.env->CallStaticVoidMethod(g_bridge, g_onBinary, e, b);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
    j.env->DeleteLocalRef(e);
    j.env->DeleteLocalRef(b);
}

/* ---------- dnssd hooks -> NsdManager ---------- */

int mdns_register(void *, const char *type, const char *name, unsigned short port,
                  const unsigned char *txt, int txt_len) {
    JniEnv j;
    if (!j.env || !g_bridge) return -65537;
    jstring t = j.env->NewStringUTF(type);
    jstring n = j.env->NewStringUTF(name);
    jbyteArray b = j.env->NewByteArray(txt_len);
    j.env->SetByteArrayRegion(b, 0, txt_len, reinterpret_cast<const jbyte *>(txt));
    jint ret = j.env->CallStaticIntMethod(g_bridge, g_onMdnsRegister, t, n, (jint) port, b);
    if (j.env->ExceptionCheck()) { j.env->ExceptionClear(); ret = -65537; }
    j.env->DeleteLocalRef(t); j.env->DeleteLocalRef(n); j.env->DeleteLocalRef(b);
    return ret;
}

void mdns_unregister(void *, const char *type) {
    JniEnv j;
    if (!j.env || !g_bridge) return;
    jstring t = j.env->NewStringUTF(type);
    j.env->CallStaticVoidMethod(g_bridge, g_onMdnsUnregister, t);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
    j.env->DeleteLocalRef(t);
}

/* ---------- UxPlay callbacks ---------- */

void cb_log(void *, int level, const char *msg) {
    JniEnv j;
    if (!j.env || !g_bridge || !msg) return;
    jstring m = j.env->NewStringUTF(msg);
    j.env->CallStaticVoidMethod(g_bridge, g_onLog, (jint) level, m);
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
    j.env->DeleteLocalRef(m);
}

void cb_conn_init(void *) { emit_event("conn_init", ""); }
void cb_conn_destroy(void *) {
    g_playback.reset();
    if (g_video) g_video->reset();
    if (g_audio) g_audio->reset();
    g_video_frames = 0;
    g_audio_packets = 0;
    emit_event("conn_destroy", "");
}
void cb_conn_reset(void *, int reason) { emit_event("conn_reset", std::to_string(reason)); }
void cb_conn_feedback(void *) {}

void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    if (data && g_video) g_video->push(data->data, (size_t) data->data_len);
    uint64_t n = ++g_video_frames;
    if (n == 1 || n % 600 == 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), "frames=%llu h265=%d last_len=%d", (unsigned long long) n,
                 data ? (int) data->is_h265 : -1, data ? data->data_len : -1);
        emit_event("video_frames", buf);
    }
}

void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    if (data && g_audio) g_audio->push((int) data->ct, data->data, (size_t) data->data_len, data->rtp_time);
    uint64_t n = ++g_audio_packets;
    if (n == 1 || n % 1000 == 0) {
        char buf[96];
        snprintf(buf, sizeof(buf), "packets=%llu ct=%d", (unsigned long long) n, data ? (int) data->ct : -1);
        emit_event("audio_packets", buf);
    }
}

void cb_audio_flush(void *) { if (g_audio) g_audio->flush(); emit_event("audio_flush", ""); }
void cb_video_flush(void *) { if (g_video) g_video->flush(); }
void cb_video_pause(void *) { emit_event("video_pause", ""); }
void cb_video_resume(void *) { emit_event("video_resume", ""); }
double cb_audio_set_client_volume(void *) { return 0.0; }
void cb_audio_set_volume(void *, float volume) {
    if (g_audio) g_audio->setVolumeDb(volume);
    emit_event("volume", std::to_string(volume));
}

void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia,
                         uint64_t *audioFormat) {
    char buf[128];
    snprintf(buf, sizeof(buf), "ct=%d spf=%d screen=%d media=%d fmt=0x%llx", ct ? *ct : -1, spf ? *spf : -1,
             usingScreen ? *usingScreen : -1, isMedia ? *isMedia : -1,
             audioFormat ? (unsigned long long) *audioFormat : 0ULL);
    emit_event("audio_format", buf);
}

void cb_audio_set_metadata(void *, const void *buf, int buflen) { emit_binary("metadata", buf, buflen); }
void cb_audio_set_coverart(void *, const void *buf, int buflen) { emit_binary("coverart", buf, buflen); }
/* UxPlay calls this on TEARDOWN of the audio stream: iOS does that on pause (connection stays). */
void cb_audio_stop_coverart_rendering(void *) { emit_event("audio_stream_stopped", ""); }
void cb_audio_set_progress(void *, uint32_t *start, uint32_t *curr, uint32_t *end) {
    char buf[96];
    snprintf(buf, sizeof(buf), "%u/%u/%u", start ? *start : 0, curr ? *curr : 0, end ? *end : 0);
    emit_event("progress", buf);
}

void cb_video_reset(void *, reset_type_t type) {
    if (g_video) g_video->reset();
    emit_event("video_reset", std::to_string((int) type));
}

int cb_video_set_codec_and_size(void *, video_codec_t codec, unsigned short ws, unsigned short hs,
                                unsigned short x, unsigned short y, unsigned short w, unsigned short h) {
    if (g_video) g_video->configure(codec == VIDEO_CODEC_H265, g_maxW, g_maxH);
    char buf[128];
    snprintf(buf, sizeof(buf), "codec=%d source=%ux%u offset=%u,%u size=%ux%u", (int) codec, ws, hs, x, y, w, h);
    emit_event("video_codec", buf);
    return 0;
}

void cb_report_client_request(void *, char *deviceid, char *model, char *name, bool *admit) {
    std::string s = std::string(name ? name : "?") + " | " + (model ? model : "?") + " | " + (deviceid ? deviceid : "?");
    emit_event("client_request", s);
    if (admit) *admit = true;   // access control (allow/deny list, confirm prompt) comes in Phase 7
}

void cb_display_pin(void *, char *pin) { emit_event("display_pin", pin ? pin : ""); }
void cb_register_client(void *, const char *device_id, const char *, const char *name) {
    emit_event("client_registered", std::string(name ? name : "?") + " | " + (device_id ? device_id : "?"));
}
bool cb_check_register(void *, const char *) { return true; }
const char *cb_passwd(void *, int *len) { if (len) *len = 0; return nullptr; }
void cb_export_dacp(void *, const char *active_remote, const char *dacp_id) {
    emit_event("dacp", std::string(dacp_id ? dacp_id : "") + " " + (active_remote ? active_remote : ""));
}

/* Video handover. "video_play" carries "<location>\n<start seconds>": a newline can't appear in a URL,
 * so URLs with spaces or '@' survive. The location is UxPlay's local HLS server
 * (http://localhost:<port>/master.m3u8, YouTube via FCUP) or a direct http(s) URL (Safari). */
void cb_on_video_play(void *, const char *location, const float start) {
    g_playback.reset();   // never answer with the previous video's progress
    emit_event("video_play", std::string(location ? location : "") + "\n" + std::to_string(start));
}
void cb_on_video_scrub(void *, const float pos) { emit_event("video_scrub", std::to_string(pos)); }
void cb_on_video_rate(void *, const float rate) { emit_event("video_rate", std::to_string(rate)); }
void cb_on_video_stop(void *) { emit_event("video_stop", ""); }
void cb_on_video_acquire_playback_info(void *, playback_info_t *info) {
    if (info) fillPlaybackInfo(g_playback.get(), info);
}
/* playlistRemove: the phone is switching items (next video, ads). UxPlay keeps the returned position
 * to resume this item later; the app pauses its player. */
float cb_on_video_playlist_remove(void *) {
    PlaybackSample s = g_playback.get();
    float position = s.ended ? 0.0f : (float) s.position;
    emit_event("video_playlist_remove", std::to_string(position));
    return position;
}
/* Filters the handed-over HLS master playlist (YouTube) before the player sees it. Format:
 * "CODEC:maxHeight[,maxHeight60fps];..."; a codec that isn't listed is kept up to 2160p, so
 * unwanted codecs are listed with height 0. Android's MediaPlayer HLS can't read YouTube's VP9
 * (and AV1) variants: each attempt to switch onto one failed (-1007) and stuttered, and kept the
 * stream stuck near 480p. Keep H.264 up to 1080p, which it plays smoothly at 60 fps. */
const char *const kHlsProfile = "AVC:1080;HEVC:0;VP9:0;AV1:0";
void cb_get_custom_profile(void *, const char **profile) { if (profile) *profile = kHlsProfile; }

void stop_locked() {
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) { raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
    g_port = 0;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("dev/better/airplay/data/engine/NativeBridge");
    if (!local) return JNI_ERR;
    g_bridge = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_onLog = env->GetStaticMethodID(g_bridge, "onLog", "(ILjava/lang/String;)V");
    g_onEvent = env->GetStaticMethodID(g_bridge, "onEvent", "(Ljava/lang/String;Ljava/lang/String;)V");
    g_onMdnsRegister = env->GetStaticMethodID(g_bridge, "onMdnsRegister", "(Ljava/lang/String;Ljava/lang/String;I[B)I");
    g_onMdnsUnregister = env->GetStaticMethodID(g_bridge, "onMdnsUnregister", "(Ljava/lang/String;)V");
    g_onBinary = env->GetStaticMethodID(g_bridge, "onBinary", "(Ljava/lang/String;[B)V");
    if (!g_onLog || !g_onEvent || !g_onMdnsRegister || !g_onMdnsUnregister || !g_onBinary) return JNI_ERR;

    android_dnssd_hooks_t hooks{};
    hooks.register_service = mdns_register;
    hooks.unregister_service = mdns_unregister;
    android_dnssd_set_hooks(&hooks);
    ntp_global_init();

    // Media pipelines live for the whole process; their threads idle when no session is active.
    g_video = new VideoPipeline(emit_event);
    g_audio = new AudioPipeline(emit_event);
    return JNI_VERSION_1_6;
}

/** Attach (or detach with null) the Surface mirrored video renders into. */
extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeSetSurface(JNIEnv *env, jclass, jobject surface) {
    if (!g_video) return;
    ANativeWindow *window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    g_video->setWindow(window);           // pipeline takes its own reference
    if (window) ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeSetAudioMuted(JNIEnv *, jclass, jboolean muted) {
    if (g_audio) g_audio->setMuted(muted);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeSetLowLatency(JNIEnv *, jclass, jboolean enabled) {
    if (g_video) g_video->setLowLatency(enabled);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeUpdatePlaybackInfo(JNIEnv *, jclass, jdouble duration,
                                                                          jdouble position, jfloat rate,
                                                                          jboolean bufferEmpty, jboolean bufferFull,
                                                                          jboolean ended, jboolean ready) {
    PlaybackSample s;
    s.duration = duration;
    s.position = position;
    s.rate = rate;
    s.bufferEmpty = bufferEmpty;
    s.bufferFull = bufferFull;
    s.ended = ended;
    s.ready = ready;
    g_playback.update(s);
}

/*
 * start(name, hwAddr[6], keyFile, width, height, refreshRate, maxFps, h265, hls, overscanned,
 *       audioDelayMicros, pinMode, languages) -> AirPlay TCP port, or negative error.
 * pinMode: 0 = anyone may connect, 1 = on-screen PIN on first connection (remembered by the sender).
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeStart(JNIEnv *env, jclass, jstring jname, jbyteArray jhw,
                                                 jstring jkeyfile, jint width, jint height, jint refreshRate,
                                                 jint maxFps, jboolean h265, jboolean hls, jboolean overscanned,
                                                 jint audioDelayMicros, jint pinMode, jstring jlanguages) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_raop) return g_port;
    g_maxW = width;
    g_maxH = height;

    std::string name = jstr(env, jname);
    std::string keyfile = jstr(env, jkeyfile);
    if (env->GetArrayLength(jhw) != 6) return -10;
    char hw[6];
    env->GetByteArrayRegion(jhw, 0, 6, reinterpret_cast<jbyte *>(hw));
    char mac[18];
    snprintf(mac, sizeof(mac), "%02X:%02X:%02X:%02X:%02X:%02X", (unsigned char) hw[0], (unsigned char) hw[1],
             (unsigned char) hw[2], (unsigned char) hw[3], (unsigned char) hw[4], (unsigned char) hw[5]);

    int err = 0;
    unsigned char pin_pw = pinMode == 1 ? 1 : 0;
    g_dnssd = dnssd_init(name.c_str(), (int) name.size(), hw, 6, pin_pw, &err);
    if (!g_dnssd) { LOGE("dnssd_init failed: %d", err); return -20; }
    dnssd_set_airplay_features(g_dnssd, 0, hls ? 1 : 0);   // AirPlay video (URL handover)
    dnssd_set_airplay_features(g_dnssd, 4, hls ? 1 : 0);   // HLS
    dnssd_set_airplay_features(g_dnssd, 42, h265 ? 1 : 0); // ScreenMultiCodec: H.265 / 4K mirroring
    dnssd_set_airplay_features(g_dnssd, 27, 1);            // legacy pairing (no MFi needed)

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.get_custom_profile = cb_get_custom_profile;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

    g_raop = raop_init(&cbs);
    if (!g_raop) { stop_locked(); return -30; }
    raop_set_log_callback(g_raop, cb_log, nullptr);
    raop_set_log_level(g_raop, LOGGER_INFO);
    if (raop_init2(g_raop, 1 /* nohold: new sender can take over */, mac, keyfile.c_str())) {
        LOGE("raop_init2 failed");
        free(g_raop); g_raop = nullptr; stop_locked();
        return -31;
    }
    raop_set_plist(g_raop, "width", width);
    raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", refreshRate);
    raop_set_plist(g_raop, "maxFPS", maxFps);
    raop_set_plist(g_raop, "overscanned", overscanned ? 1 : 0);
    raop_set_plist(g_raop, "audio_delay_micros", audioDelayMicros);
    if (hls) raop_set_plist(g_raop, "hls", 1);
    /* System languages, used to pick the audio rendition of handed-over HLS (YouTube). UxPlay
     * strlen()s this without a null check, so it must always be set (it crashed the app when unset). */
    g_lang_system = jstr(env, jlanguages);
    if (g_lang_system.empty()) g_lang_system = "en";
    raop_set_lang(g_raop, nullptr, nullptr, g_lang_system.c_str());
    /* PIN pairing is enabled by the "pin" plist item (sets use_pin). Value 0 (< 10000) means a random
     * 4-digit PIN per pairing, reported through display_pin; senders remember the pairing afterwards. */
    if (pin_pw == 1) raop_set_plist(g_raop, "pin", 0);

    unsigned short tcp[3] = {7100, 7000, 7001};
    unsigned short udp[3] = {7011, 6001, 6000};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) { LOGE("raop_start_httpd failed"); stop_locked(); return -40; }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);
    g_port = port;

    int r1 = dnssd_register_raop(g_dnssd, port);
    int r2 = dnssd_register_airplay(g_dnssd, port);
    LOGI("receiver '%s' up on port %u (raop mdns=%d, airplay mdns=%d)", name.c_str(), port, r1, r2);
    emit_event("started", std::to_string(port));
    return port;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeStop(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    stop_locked();
    emit_event("stopped", "");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_better_airplay_data_engine_NativeBridge_nativeDisconnectClients(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_raop) {
        raop_remove_hls_connections(g_raop);
        raop_remove_known_connections(g_raop);
    }
}
