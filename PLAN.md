# better_airplay: an always-on AirPlay receiver for Android TV / Google TV

Goal: a light AirPlay receiver for Android TV / Google TV that starts at boot, advertises
itself all the time, and appears on screen by itself when an iPhone, iPad or Mac connects.
The design comes from the research below; no existing receiver app is used as the model.

Status (2026-11-10): research done; Phase 1 code written. The native library (BoringSSL + libplist + UxPlay +
JNI) builds for armeabi-v7a: 1.0 MB stripped. The Gradle/APK build hasn't run yet: Gradle's background
process can't download dependencies from inside Claude's sandbox, so run `tools/deploy.sh` from your own terminal.
Out of scope: Miracast. Its Wi-Fi Display permissions are signature-only, so a normal app can't be a Miracast receiver.

---

## 1. What the research found

### There is no "AirPlay 3"
The current senders are iOS 27 and macOS 27 (released Sept 2026). Apple has not released a new
protocol version; it keeps changing behavior inside "AirPlay 2". The latest example: iOS 27
changed how sessions end (TEARDOWN). UxPlay patched this in 1.73.7 (Sept 2026).

### What open source can and cannot do

| Feature | Possible in open source? | Notes |
|---|---|---|
| Screen mirroring (H.264) | Yes | Every open-source mirroring receiver uses the "Legacy Protocol" pairing plus FairPlay `fp-setup` (the `playfair` code). |
| Screen mirroring in 4K (H.265) | Yes | AirPlay feature bit 42 ("ScreenMultiCodec"). UxPlay supports it with `-h265`. |
| Audio-only streaming (ALAC/AAC) | Yes | Works with track metadata and cover art. |
| Video handover (YouTube etc., HLS) | Partly | YouTube works well. Some apps fail: playlist URLs with query strings, TikTok. |
| DRM video (Apple TV app, Netflix) | **No** | FairPlay Streaming keys cannot be extracted. Commercial apps can't do this either, unless Apple licenses them (Sony, LG, Samsung). |
| Real AirPlay 2 multi-room audio (PTP timing) | **Not on a normal Android app** | PTP needs UDP ports 319/320. A normal app can't bind ports below 1024 without root. No open-source receiver combines multi-room with mirroring anyway. |

Risk to plan for: UxPlay's README warns that Apple could drop the Legacy Protocol in a
future iOS release. iOS 27 still supports it. We reduce this risk by tracking upstream UxPlay
closely rather than freezing an old copy.

### Implementations, ranked

1. **[FDH2/UxPlay](https://github.com/FDH2/UxPlay)** (C, GPL-3.0). This is the engine everyone builds on, and it is the only
   maintained one with mirroring, HLS, PIN pairing and remote control (DACP). Latest release is v1.73.7 (Sept 2026); master is 1.74, which
   adds a built-in mDNS responder. It is actively maintained (about 78 commits in 3 months). Patches for iOS 27 are already in.
   Open iOS 27 issues: #535 (some senders mirror only in HEVC) and #539 (audio breaks after pause/resume).
2. **[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)** (GPL-3.0, about 1k stars, v0.0.36, on F-Droid and Play).
   This is UxPlay with an Android layer around it: JNI, MediaCodec, AudioTrack, ExoPlayer for HLS, and support for H.264, H.265 and HLS.
   It is the best reference for the Android glue code. Weaknesses: its UxPlay copy is about 97 commits behind upstream with
   9 local patches, and it is built around an app you open.
   It doesn't wake the TV or start at boot (open issue #92). Open issues report black screens on some Realtek
   Google TVs (#87) and A/V sync drift (#82, #72).
3. **wormhole-display** (pgodlews, GPL-3.0). Another UxPlay port to Android (Meta Portal), with MediaCodec and AudioTrack.
   Useful for borrowing patches.
4. **shairport-sync 5.6.x**: the reference for audio-only AirPlay 2 (PTP, multi-room). It has no video,
   and its PTP part (NQPTP) needs root on Android, so we skip it.
5. Dead or immature: RPiPlay (last commit 2022), the dsafa22 AirplayServer, openairplay/airplay2-receiver (Python, audio only),
   shairplay-rust and rairplay (mirroring not finished), PhairPlay (beta, unclear license).

An October 2026 comparison ([portal-receiver#2](https://github.com/rudysev/portal-receiver/issues/2)) reached the same
conclusion we do: **use UxPlay's C core as a pinned git submodule, write our own Android layer, and borrow
patches from jqssun and wormhole-display without using either as the base.**

### Context
- **Sony and others:** Sony Google TVs have AirPlay 2 because Sony licenses it from Apple and runs it as a
  privileged system component. Samsung (Tizen) and LG (webOS) TVs aren't Android. A sideloaded app can't get Apple's
  certified stack, but it can get close to "system-like" with permissions granted over ADB (section 4).
- **EU Digital Markets Act:** iOS 27 is reported to add native Google Cast as an AirPlay alternative, **in the EU only**.
  That doesn't help a US setup, but it is a possible future fallback.
- **Android TV versions:** the latest shipping version is Android TV 14. Google skipped 15, and Android TV 16 is expected
  in 2026. Your TV is on 11, and only the manufacturer can update it. The design targets 11 and also follows the stricter 14/16 rules.

---

## 2. The test TV (checked live over ADB on 2026-11-10)

| | |
|---|---|
| Model | Vu TV FFM, made by Hisense (`songshan`), name on the network "Family room TV" |
| IP / ADB | `192.168.29.59:5555`, network debugging on, this PC authorized |
| OS | Android TV 11 (API 30), Google TV launcher (`launcherx`), user build. Firmware 01.02.05.04 (built 2026-04-12, security patch 2026-04-01) |
| Updates | Vu still ships security patches but no Android version upgrade. Vu's Android 14 Google TVs are different hardware (Konka-built, Realtek RTK2851, `keoneae`), so their firmware can't be used here. Hisense-built MediaTek MT9632 sets like this one have stayed on Android 11. Flashing firmware from another model risks bricking the TV (panel/TCON mismatch). The OTA apps are `com.hisense.tv.ota` and `com.vt.tv.usbota`. |
| AirPlay flag | `ro.vt.airplay_enable=false`. Tested: `setprop` is refused (the property is on the read-only vendor partition, and the security policy blocks the shell user). The only code that reads it is Hisense's setup wizard (`FTE.apk`, the "AirPlay & HomeKit" page). The licensed AirPlay receiver isn't on this build, and it would need a per-TV Apple certificate this unlicensed TV doesn't have. Dead end; we use our own receiver. |
| SoC | MediaTek/MStar `m7632`. **32-bit userspace**: native code must target `armeabi-v7a` (add `arm64-v8a` for other TVs) |
| RAM | **About 1.8 GB total, about 180 MB free.** This is the main constraint, so the receiver must be tiny while idle. |
| Panel | 4K. The Android UI draws at 1920×1080 (normal on TVs); video plays on a separate 4K plane. HDR10/HLG/HDR10+/Dolby Vision, ALLM (game mode) supported |
| Decoders | `OMX.MS.HEVC.Decoder`: 3840×2160 at 60 fps. `OMX.MS.AVC.Decoder`: 1080p. Neither has `feature-low-latency`, so we have to keep latency low ourselves. |
| HDMI-CEC | Present and enabled |
Practical note: another receiver app installed on the TV currently holds AirPlay port 7000. It has to be disabled while we
test (`pm disable-user`, reversible). I'll ask before doing that.

---

## 3. Architecture

```
 iPhone/Mac ──mDNS──► NsdManager registration (_airplay._tcp + _raop._tcp, with TXT records)
      │
      └──TCP 7000 / UDP──► ┌──────────── ReceiverService (foreground, START_STICKY) ──────────────┐
                           │  libuxplay (C, upstream submodule)                                  │
                           │   raop / pairing / playfair / mirror stream / HLS / DACP            │
                           │        │ JNI (thin: callbacks with NAL units, audio frames, events) │
                           │  SessionController (Kotlin): idle → audio-only → mirror → video     │
                           └──────┬───────────────────────┬──────────────────────────────────────┘
                                  │ session starts         │ audio only
                     wake screen + start ReceiverActivity  │ AudioTrack + MediaSession
                     (allowed: SYSTEM_ALERT_WINDOW)         │ (+ optional now-playing overlay)
                                  ▼
                   ReceiverActivity: fullscreen SurfaceView ← MediaCodec (H.264/H.265)
                                     media3 ExoPlayer for HLS handover
                                     minimal post-processing (ALLM), Back key = disconnect
```

Design decisions:
- **Engine:** upstream UxPlay as a git submodule pinned to a recent master commit, built with the NDK and CMake. Our own
  patches live in a small `patches/` series that we try to get merged upstream, so updating for a new iOS release means bumping the submodule.
  We replace GStreamer with callbacks: UxPlay's `-vrtp`/`-artp` work and jqssun's JNI show where to hook in.
- **Dependencies:** OpenSSL 3 (static, built for Android) and libplist (static). No GStreamer, no FFmpeg (ALAC
  decoding uses Apple's open-source ALAC decoder; AAC-ELD and AAC-LC use MediaCodec). Three decoder files are
  replaced by bounds-checked copies in `app/src/main/cpp/alac_hardened/` (checked bit reads, and the partial-frame
  sample count capped at the frame length, as FFmpeg does). Upstream trusted that count and could overflow its buffers.
- **Discovery:** use Android `NsdManager` to advertise the service, so the system mdnsd answers even while our process is idle,
  and hold a `MulticastLock`. UxPlay 1.74's built-in mDNS is the fallback. **Test on this TV whether it conflicts with
  the system mdnsd on port 5353.**
- **Idle cost:** while idle the app is just the listening sockets and its mDNS registration. No Activity, no decoders, no
  ExoPlayer loaded. Target idle memory: **≤ 25 MB PSS** (check with `dumpsys meminfo`).
- **Showing up without the app open:** when a session starts, the service grabs a wake lock with `ACQUIRE_CAUSES_WAKEUP` and starts
  `ReceiverActivity` (`showWhenLocked` and `turnScreenOn`). Android allows this from the background because of the
  `SYSTEM_ALERT_WINDOW` grant. When the session ends, the activity calls `finish()` and the TV goes back to whatever was
  on screen.
  Fallback if a device blocks this: draw a `TYPE_APPLICATION_OVERLAY` window that holds the SurfaceView, directly from the service.
- **Latency:** the decoders lack `feature-low-latency`, so we use `KEY_LOW_LATENCY` where available, show frames as soon as
  they're decoded (`releaseOutputBuffer(i, true)`) with no PTS queue during mirroring, keep a
  small buffer for jitter, and call `window.setPreferMinimalPostProcessing(true)` to put the TV into game mode.
  Audio uses `AudioTrack` with `PERFORMANCE_MODE_LOW_LATENCY`, synced to the RTP/NTP clock.
- **4K:** advertise H.265 (bit 42) with 3840×2160 when the HEVC decoder reports 2160p60, and fall back to
  1080p H.264 otherwise. Settings let you force 1080p (useful on weak Wi-Fi).
- **Boot:** `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `USER_UNLOCKED` all start `ReceiverService`. The foreground service type is
  `connectedDevice`, which Android 14 and 15 still allow to start from boot. A
  WorkManager job runs every 15 minutes and restarts the service if something killed it.
- **License:** the app is GPL-3.0 because UxPlay is. That's fine for personal use, F-Droid, or an open repo.

### Goal: behave like a real Apple TV (from the protocol research, `docs/AIRPLAY_PROTOCOL.md`)
Mirroring alone isn't enough. What made AirReceiver feel like real AirPlay was **video handover**: the app on the phone
passes the video itself to the TV, so the phone is free and quality is native. That has two paths, and we need both:
- **Direct URL** (Safari, Vimeo, most AVPlayer apps): `POST /play` with an http(s) URL, played by ExoPlayer.
- **`mlhls://` + FCUP** (YouTube): the TV fetches playlists *from the phone* over the reverse channel. UxPlay's
  version is shaped around YouTube only. We need a general, lazy FCUP proxy feeding ExoPlayer (live refresh, keys).
- Plus what senders expect back: playback-state events (`loading`/`playing`/`paused`/`itemPlayedToEnd`), accurate
  `/playback-info`, a play queue (`playlistInsert`/`playlistRemove`) for autoplay-next, and track selection.
- FairPlay-protected streams (Apple TV app, Disney+, Max, Prime) can't play on any unlicensed receiver. Detect
  them, stop the phone from waiting, and offer to open the same service's own app on the TV, which plays it with
  Widevine (see "Protected video fallback" in Phase 6).

### Reliability: fallbacks at every layer
| Layer | Primary | Fallback(s) |
|---|---|---|
| Discovery | NsdManager (system mDNS) | UxPlay's built-in mDNS responder; re-register on Wi-Fi change |
| Mirroring codec | H.265 / 4K (bit 42 on by default; iOS 27 senders may require it) | H.264 1080p (hardware) → H.264 (Android's software decoder) |
| Video handover | Direct URL / FCUP proxy into ExoPlayer | fall back to mirroring (tell the sender we can't play, so it mirrors instead) |
| Showing the picture | Start the player Activity from background | Overlay window from the service |
| Audio output | AudioTrack low-latency | AudioTrack normal; MediaCodec ALAC/AAC → software ALAC |
| Process | Foreground service + boot receiver | watchdog job restarts it; Hisense freezer test in Phase 3 |

### Connecting and disconnecting
- **Connect:** pick the TV in Control Center → it wakes, takes over the screen, nothing to open on the TV.
- **Disconnect from the TV:** Back on the remote (press twice within 2 s to avoid accidents), a "Disconnect"
  button in the notification, or a long-press of Back/Home.
- **Disconnect from the phone:** Stop Mirroring / AirPlay off → TV returns to whatever it was showing.
- **Takeover:** a new sender replaces the current one (UxPlay "nohold"). Optional confirm prompt or allow list later.
- **Stale sessions:** if no stream data arrives for N seconds, close the session cleanly. Never leave a frozen
  frame on screen.

---

## 4. "System component" via ADB: what ADB can and can't do

Without root, ADB **cannot** install into `/system/priv-app`, platform-sign an app, grant `START_ACTIVITIES_FROM_BACKGROUND`,
or let it bind ports below 1024 (needed for PTP multi-room). `dpm set-device-owner` is also blocked because the
TV has a Google account signed in.

What ADB **can** do, and is enough for always-on behavior. This will be `tools/provision.sh`, run once after install:

```sh
PKG=dev.better.airplay
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow        # start the Activity from background + overlay fallback
adb shell dumpsys deviceidle whitelist +$PKG                # exempt from Doze
adb shell cmd appops set $PKG RUN_IN_BACKGROUND allow
adb shell cmd appops set $PKG RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket $PKG active
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS   # API 33+ only
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS # optional: lets the app adjust TV settings itself
```

TV makers often ship their own app killer. **This TV has one:** Hisense's process freezer (`persist.vt.hi_freeze_mode=1`),
configured by `/vendor/etc/freeze_list.json` and `/vendor/etc/sys_white_list.json`. While Netflix, YouTube, Disney+, Prime
Video or the launcher are in front (`curfreezeList`), other background processes can be frozen, and only the system apps on
its lists are exempt. The lists are on the read-only vendor partition, so we can't add ourselves.
**Phase 3 must test this specifically:** start YouTube, then try to AirPlay from the phone. If the receiver gets frozen,
fallbacks are (a) check whether a foreground-service process is exempt, (b) keep only a tiny wake-up listener alive,
(c) document the limitation.

---

## 5. Build phases

Each phase ends with a check run on the real TV over ADB (`192.168.29.59:5555`).

**Phase 0: Hardware checks (half a day)**
- Install the upstream jqssun build only as a hardware probe. It tells us whether UxPlay plus MediaCodec works on this
  SoC (black-screen bugs like jqssun #87, HEVC 4K decode, AAC-ELD support) before we write our own layer.
- **Standby test:** turn the TV off with the remote and check whether port 7000 still answers and the receiver still
  shows in the iPhone's AirPlay list. If the network sleeps in standby, "wake on AirPlay" needs the TV's network-standby
  setting (if it has one). Otherwise it only works while the TV is on.
- Measure glass-to-glass latency (film a stopwatch on the phone next to the TV) to get our own numbers to improve on.

**Phase 1: Build setup + discovery + pairing**
- Install NDK r27+ and CMake via `sdkmanager`. Android Studio's bundled JDK is at `C:\Program Files\Android\Android Studio\jbr`.
- Gradle project (Kotlin, minSdk 26, targetSdk 35, ABIs armeabi-v7a and arm64-v8a), UxPlay submodule, OpenSSL and libplist
  build scripts, and a JNI stub.
- `tools/deploy.sh`: build, install, provision, launch, follow logcat.
- Done when: "better_airplay" shows in the iPhone's Screen Mirroring list, pairing completes in the logs, and
  stream setup succeeds.

**Phase 2: Mirroring (H.264 + AAC-ELD)**
- NAL units from the native callback go to MediaCodec and then a SurfaceView. AAC-ELD audio goes to MediaCodec and then AudioTrack. Handle rotation and resolution changes.
- Done when: an iPhone mirrors to the TV at 1080p60, with smooth video and correct sound, for 30 minutes.

**Phase 3: Always on**
- Foreground service, boot receivers, watchdog, provisioning script, background Activity start, wake on session start,
  Back key disconnects, return to the previous app.
- Done when: after `adb reboot` with no interaction, the receiver is discoverable within about 60 s, and mirroring
  from the phone takes over the screen from Netflix/YouTube and returns there afterwards. It still works after 24 h idle.

**Phase 4: Quality**
- H.265/4K, game mode (ALLM), the latency work above, A/V sync that stays put across reconnects (jqssun #82),
  a performance overlay toggled from the remote.
- Done when: 4K HEVC mirroring is stable, with glass-to-glass latency under about 150 ms in low-latency mode.

**Phase 5: Audio-only AirPlay**
- ALAC and AAC through AudioTrack, plus MediaSession with metadata and cover art. Play/pause/next from the TV remote go back to the phone via DACP.
  Volume follows the sender.
- No full-screen takeover; optionally a small now-playing card.
- Done when: Apple Music from the iPhone plays on the TV, controllable from the phone and the TV remote.

**Phase 6: Video handover (HLS)**
- YouTube and other apps that hand over a video URL play in media3 ExoPlayer, with seek/pause synced to the phone.
  Fix the query-string and relative-URL playlist cases (jqssun #79).
- Done when: "AirPlay" from the YouTube iOS app plays a 4K video on the TV while the phone is free.

**Phase 6b: Protected video fallback ("open it in the TV's app")**
- **Detect protected content**, using any one of these signals, in the order they arrive:
  1. The sending app, from the `/play` plist (`clientBundleID`, `clientProcName`), matched against a table of
     services known to use FairPlay.
  2. A `POST /fp-setup2` request (FairPlay Streaming setup), which only protected playback sends.
  3. A playlist with `EXT-X-KEY` / `EXT-X-SESSION-KEY` using `KEYFORMAT="com.apple.streamingkeydelivery"` or an
     `skd://` key URI.
- **Release the phone immediately:** answer the request with an error and send `state:"stopped"` on the event
  channel, so the phone doesn't spin for 50 s. The exact error key iOS expects is [U]; capture it from a real session.
- **On the TV:** a full-screen card in the player's style: "This video is protected. Open Disney+ on this TV?"
  - **Open** (focused by default) launches the TV app.
  - **Not now** dismisses the card.
  - If the app isn't installed, the button opens its Play Store page instead.
  - If the handoff carries a title ID or a public link (for example a `tv.apple.com` URL), try to open that title
    directly. Otherwise open the app's home screen. Which senders include an ID is [U]; log the full `/play` plist
    of each service to find out.
- **Service table** (sender bundle ID → Android TV package), in one data file, unit-tested, easy to extend:

  | Service | iOS bundle ID | Android TV package |
  |---|---|---|
  | Apple TV app | `com.apple.tv` | `com.apple.atve.androidtv.appletv` (installed on the test TV) |
  | Prime Video | `com.amazon.aiv.AIVApp` | `com.amazon.amazonvideo.livingroom` (installed on the test TV) |
  | Disney+ | `com.disney.disneyplus` | `com.disney.disneyplus` [U on the TV package] |
  | Max | `com.wbd.stream` [U] | `com.wbd.stream` [U] |
  | JioHotstar and other regional services | [U] | [U] |

  Rows marked [U] are filled in from real session logs and `pm list packages` on the TV before they ship.
- **Settings:** "When a video is protected": ask (default), always open the app, or show a message only.
- **Tests first:** detection from recorded `/play` plists and playlists (including truncated and bit-flipped
  copies), the bundle-to-package table, and the not-installed path.
- Done when: playing a Disney+ or Apple TV app video over AirPlay frees the phone at once, and one press of OK on
  the TV remote opens that service's app on the TV.

**Phase 7: Polish**
- A small settings screen for TV (name, PIN, resolution, latency mode, auto-takeover on/off), settings changeable over ADB,
  crash reporting to a local log you can pull with adb, and a script for updating the UxPlay submodule.

**Ongoing:** watch UxPlay releases and the iOS 27.x/28 betas. Every iOS release is a regression test against this TV.

---

## 6. Testing

- **Automated (from the PC):** `pyatv` / `atvremote` can stream audio to an AirPlay receiver, which gives audio and pairing
  tests without a phone. `curl` against port 7000 (`GET /info`) checks the feature bits and TXT records.
  `adb logcat -s BetterAirPlay:* uxplay:*` follows the logs.
- **Manual (needs Apple devices):** mirroring from an iPhone and, if available, a Mac. Run a regression checklist per iOS version.
- **Resource checks:** `adb shell dumpsys meminfo dev.better.airplay` while idle and while mirroring, and
  `dumpsys activity services` to confirm the service survives.

## 7. Open questions for you
1. What mattered most in AirReceiver when it worked well (latency, picture quality, audio, video handover)?
2. Which senders: iPhone only, or also an iPad or Mac?
3. Is GPL-3.0 OK? It's required if we use UxPlay. The only alternative is writing the protocol from scratch,
   which is not worth it.
4. Personal sideload only, or eventually F-Droid / Play Store?
