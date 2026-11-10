# AirPlay protocol reference for a receiver

Scope: how an Apple TV-class AirPlay receiver behaves toward current senders (iOS/iPadOS/macOS
26–27), and what our Android TV receiver, built on UxPlay's C library, must do for every
sender use case. Research date: 2026-11-10.

UxPlay references are to `FDH2/UxPlay` master at `05b0275` (2026-11-06, version string "1.74")
and to the release tag `v1.73.7` (2026-09-04). Paths such as `lib/http_handlers.h` are in that repository.

Conventions:
- **[U]** marks a claim that no source in the list below confirms (inference or community
  report). Treat it as a hypothesis and test it against a real sender.
- "Sender" = iPhone/iPad/Mac. "Receiver" = us. All plists are Apple property lists. "bplist" =
  binary (`application/x-apple-binary-plist`), "xml plist" = `text/x-apple-plist+xml`.
- This document says where Apple's licensed authentication (MFi) and FairPlay DRM begin. It does
  not describe how to get around them.

---

## 0. Big picture

| Use case | Transport | Session start | Data path | Open-source status |
|---|---|---|---|---|
| Screen mirroring | RTSP/1.0 control on the AirPlay port (7000) + TCP mirror stream | `SETUP` with stream `type 110` | Sender encodes H.264/H.265, sends it over TCP | Works (UxPlay) |
| Mirror audio | Same RTSP session | `SETUP` with stream `type 96`, `ct=8` (AAC-ELD) | RTP/UDP | Works |
| Audio-only ("AirPlay audio") | RTSP | `SETUP` with stream `type 96` (realtime) or `type 103` (buffered) | RTP/UDP (96) or TCP (103) | 96 works in UxPlay. 103 needs AirPlay 2 pairing plus PTP (shairport-sync) |
| Video handover ("AirPlay video") | HTTP/1.1 on the **same port**, plus a reverse-HTTP event channel | `POST /play` | Receiver fetches the media itself (URL) or the sender serves playlists through FCUP | Partly works (YouTube; plain URLs on master) |
| Photos / slideshow | HTTP/1.1 | `PUT /photo` | JPEG in the request body | Not implemented in UxPlay |
| Remote control / now-playing (AirPlay 2) | RTSP `SETUP {isRemoteControlOnly}` plus stream `type 130` | HAP-encrypted event and data channels carrying MediaRemote (MRP) messages | — | Not implemented anywhere open-source as a receiver [U] |

The receiver tells connection types apart by headers (`lib/raop.c` `conn_request`):
- `CSeq` present means an RTSP ("RAOP") control connection.
- `X-Apple-Session-ID` without `CSeq` means an HTTP AirPlay-video connection.
- `Host:` with neither header means the local media player fetching the rewritten HLS playlists
  from the receiver's own HTTP server.

---

## 1. Discovery

### 1.1 Services
The receiver registers two DNS-SD services on the same host, both pointing at the AirPlay TCP port (UxPlay default 7000):
- `_airplay._tcp`, instance name = friendly name (e.g. `Living Room`).
- `_raop._tcp`, instance name = `<MAC hex, no colons>@<friendly name>`.

Since 1.74 UxPlay also ships an internal mDNS responder (`lib/mdnsd/`). It can also use Bonjour/Avahi (`lib/dns_sd/`), or a Bluetooth LE beacon (`Bluetooth_LE_beacon/`), which makes senders issue a header-less `GET /info?txtAirPlay&txtRAOP`.

### 1.2 `_airplay._tcp` TXT (what UxPlay publishes; `lib/dnssdint.h`, `lib/dns_sd/dns_sd.c`)
| Key | UxPlay value | Meaning |
|---|---|---|
| `deviceid` | `AA:BB:CC:DD:EE:FF` | Receiver ID, MAC-shaped. Senders key pairings on it |
| `features` | `0x5A7FFEE6,0x0` (default) | 64-bit feature mask as `lo,hi` hex. See 1.4 |
| `flags` | `0x4` | Status flags (20-bit). Bit 2 = "audio cable attached" |
| `model` | `AppleTV3,2` | `AppleTV6,2` + `srcvers 380.20.1` also works (README) |
| `pk` | 64 hex chars | Ed25519 long-term public key (pairing identity). UxPlay derives it from the MAC, or from a key file (`-key`) |
| `pi` | fixed UUID | Pairing/group identifier (`PublicCUAirPlayPairingIdentifier`) |
| `srcvers` | `220.68` | AirPlay version. **Changes sender behaviour.** airplay2-receiver notes: ≤355 → realtime audio + NTP; ≥355 → PTP + buffered audio; >360 → remote control |
| `vv` | `2` | "Vodka version" (unknown) |
| `pw` | `true`/`false` | Password or PIN required |

Apple TVs also publish `acl`, `gid`, `igl`, `gcgl`, `psi`, `osvers`, `protovers`, `fex`, `btaddr`, `rsf`, `hgid`, `hmid` (pyatv, airplay-spec). These belong to AirPlay 2 group/HomeKit features. Open receivers that do mirroring and video do not need them.

### 1.3 `_raop._tcp` TXT
| Key | UxPlay | Meaning |
|---|---|---|
| `txtvers` `ch` `sr` `ss` | `1` `2` `44100` `16` | Version, channels, sample rate, sample size |
| `cn` | `0,1,2,3` | Codecs: PCM, ALAC, AAC, AAC-ELD |
| `et` | `0,3,5` | Encryption: none, FairPlay, FairPlay SAPv2.5 (`1`=RSA/AirPort, `4`=MFiSAP are not offered) |
| `md` | `0,1,2` | Metadata: text, artwork, progress |
| `ft` | same as `features` | |
| `am` | `AppleTV3,2` | Model |
| `sf` | `0x4` / `0x8c` with `-pin` / `0x84` with `-pw` | Status flags. `0x8` = PINRequired, `0x80` = PasswordRequired |
| `pw` `da` `sv` `tp` `vs` `vn` `rhd` `vv` `pk` | | `tp=UDP`, `vs=220.68`, `vn=65537` |

### 1.4 Features bitmask (union of airplay-spec `features.md`, emanuelecozzi, UxPlay `uxplay.cpp`, java-airplay `AirPlayFeature.java`)
| Bit | Name | UxPlay default | Relevance |
|---|---|---|---|
| 0 | Video (SupportsAirPlayVideoV1) | off; **on with `-hls`** | Needed for the "play video on receiver" route |
| 1 | Photo | on | Advertised, but UxPlay has no `/photo` handler (see §6) |
| 2 | VideoFairPlay | on | Says the receiver can play FairPlay-protected video. UxPlay can't. Effect of clearing it is [U] |
| 3 | VideoVolumeControl | off | java-airplay sets it with video |
| 4 | VideoHTTPLiveStreams | off; **on with `-hls`** | HLS handover |
| 5 | Slideshow | on | |
| 6 | (unnamed) | on | Also in UxPlay's and java-airplay's compact `/server-info` set |
| 7 | Screen | on | Mirroring |
| 8 | ScreenRotate | off | UxPlay comment: unsure whether HLS needs it |
| 9 | Audio | on | Without it, senders stop after ~60 s (UxPlay issue #463) |
| 11 | AudioRedundant | on | |
| 12 | FPSAPv2pt5_AES_GCM | on | |
| 13 | PhotoCaching | on | |
| 14 | Authentication_4 (FairPlay) | on | Legacy fp-setup auth |
| 15/16/17 | Metadata artwork / progress / text | on | Classic SET_PARAMETER metadata |
| 18–21 | AudioFormat1–4 | on | 19+20 are "required for AirPlay 2" per spec |
| 22 | (unnamed) | on | |
| 23 | Authentication_1 (RSA) | off | AirPort Express only |
| 26 | HasUnifiedAdvertiserInfo / Authentication_8 (MFi) | off | **MFi boundary** |
| 27 | SupportsLegacyPairing | on (UxPlay can turn it off) | Sender does `/pair-setup` + `/pair-verify` |
| 30 | RAOP | on | RAOP on this port. `_raop._tcp` not strictly needed |
| 32 | IsCarPlay / SupportsVolume when clear | off | |
| 33 | SupportsAirPlayVideoPlayQueue | off | java-airplay sets it. Probably governs `playlistInsert` use [U] |
| 34 | SupportsAirPlayFromCloud | off | Effective only with status-flags bit 6 (emanuelecozzi) |
| 38 | SupportsUnifiedMediaControl / CoreUtils pairing | off | HAP-based |
| 40 | SupportsBufferedAudio | off | Needs `srcvers ≥ 354.54.6`. Multiroom |
| 41 | SupportsPTP | off | Needs `srcvers ≥ 366`. Multiroom; ports 319/320 |
| 42 | SupportsScreenMultiCodec | off; **on with `-h265`** | Lets the sender pick H.265. **iOS 27: some senders mirror only HEVC (#535)** |
| 43 | SupportsSystemPairing | off | HAP |
| 46 | SupportsHKPairingAndAccessControl | off | HomeKit. shairport-sync sets 46+47 to be added to the Home app |
| 48 | SupportsTransientPairing | off | HAP transient pairing |
| 49 | SupportsAirPlayVideoV2 (java-airplay name) | off | [U] |
| 50 | MetadataFeature (bplist now-playing) | off | AirPlay 2 metadata via `POST /command` |
| 51 | SupportsUnifiedPairSetupAndMFi | off | **MFi boundary** |
| 52 | SupportsSetPeersExtendedMessage | off | PTP groups |
| 54–61 | APSync, WoL, Hangdog remote, AudioStreamConnectionSetup, AudioMediaDataControl, RFC2198 | off | Not needed |

Effective UxPlay masks: default `0x5A7FFEE6,0x0`; `-hls` → `0x5A7FFEF7,0x0`; `-h265` sets the high word to `0x400`.

**What makes iOS offer video handover vs mirror-only:**
- With bits 0 and 4 set (UxPlay `-hls`), the AirPlay button in AVPlayer-based apps (YouTube, Safari `<video>`, etc.) sends the video itself with `POST /play`.
- With both clear, the sender treats the receiver as a screen/audio target. AVKit apps then send **audio only** (ALAC, type 96) and keep video on the phone (UxPlay README), unless the user starts mirroring.
- Bits 3, 33, 34, 49 are set together in java-airplay's `VIDEO_PLAYBACK` group. Whether iOS 26–27 changes behaviour based on them is [U].

### 1.5 `GET /info` (RTSP/1.0, on the control connection; `raop_handler_info`)
1. The sender first sends `GET /info` with a bplist body `{qualifier: ["txtAirPlay"]}`. The receiver replies `{txtAirPlay: <raw TXT bytes>}` (or `txtRAOP`).
2. A second `GET /info` (with `CSeq`, no body) gets the full bplist: `deviceID`, `macAddress`, `pk` (32-byte data), `features` (uint64), `name`, `pi`, `vv`, `statusFlags` (UxPlay: 68 = 0x44), `keepAliveLowPower` (bool), `keepAliveSendStatsAsBody`, `sourceVersion`, `model`, `initialVolume` (dB), `audioLatencies[]` and `audioFormats[]` (types 100/101, formats mask `0x3fffffc`), and `displays[]`.
3. `displays[0]` drives mirroring resolution: `uuid`, `width`/`height`, `widthPixels`/`heightPixels`, `widthPhysical`/`heightPhysical` (0), `rotation` (false), `refreshRate` (real, **seconds per frame**, e.g. 1/60), `maxFPS`, `overscanned`, `features` (UxPlay: 14).
4. Real Apple TVs also return `playbackCapabilities {supportsInterstitials, supportsFPSSecureStop, supportsUIForAudioOnlyContent}`, `supportedFormats {audioStream, screenStream, bufferStream, lowLatencyAudioStream}`, `receiverHDRCapability` ("4k30"), `hasUDPMirroringSupport`, `volumeControlType`, `featuresEx` (pyatv capture of an AppleTV6,2). UxPlay sends none of these.
5. The HTTP-side `GET /server-info` (xml plist) is separate. UxPlay answers with a compact profile: `features=0x27F` (bits 0–6, 9), `model`, `srcvers`, `protovers 1.0`, `osBuildVersion 12B435`, `deviceid`, `macAddress`, `vv`.
6. java-airplay found that putting the full mask or a modern `srcvers` in `/server-info` pushes YouTube onto `/fp-setup2` (FairPlay v1 over HTTP, not implementable), so it returns `srcvers 101.28` there. **Keep `/server-info` compact.**

---

## 2. Pairing and authentication without MFi

### 2.1 What open receivers do (UxPlay `lib/pairing.c`, `lib/raop_handlers.h`, `lib/srp.c`)
RTSP order on the control connection:
1. `GET /info` ×2 (§1.5).
2. **Legacy pair-setup** (only if feature bit 27 is set): `POST /pair-setup`, body = sender's 32-byte Ed25519 public key. Response = receiver's 32-byte Ed25519 `pk`.
3. **Legacy pair-verify**: `POST /pair-verify`, two steps, `application/octet-stream`.
   - Step `0x01`: 4-byte header + X25519 ephemeral key + Ed25519 key. The receiver replies with its X25519 key + an encrypted Ed25519 signature.
   - Step `0x00`: the sender's signature. The receiver verifies it.
   - Result: an ECDH shared secret, later hashed into the session AES key (SHA-512(aeskey‖ecdh)[0:16]).
   - UxPlay README: with bit 27 off, most senders skip pairing and connect about 5 s faster, but no per-client shared secret exists. Apple TV uses that secret to tell up to ~12 clients apart. UxPlay turns pairing back on for `-pin`.
4. **FairPlay SAP setup**: `POST /fp-setup` twice (16-byte then 164-byte challenge). The receiver must produce FairPlay responses. All open receivers (UxPlay, RPiPlay, shairport-sync's AP1 path, java-airplay) use the reverse-engineered "playfair" code for **type 0x03** challenges only (`lib/playfair/`, `fairplay_playfair.c`). UxPlay README: "the legal status of that library is unclear."
   - Other challenge types get `501`.
   - HTTP `POST /fp-setup2` (FairPlay v1 variant) is answered `421 Misdirected Request` (`http_handler_fpsetup2`).
5. **`SETUP` (session)**: bplist with `ekey` (72 bytes, FairPlay-wrapped AES key) and `eiv` (16 bytes). The receiver unwraps `ekey` through the fp-setup state. That AES key (plus the ECDH hash) protects the mirror stream and the RTP audio.

### 2.2 Access control that works without Apple certificates
| Mechanism | Wire | UxPlay |
|---|---|---|
| Onscreen PIN, Apple-style one-time pairing | `POST /pair-pin-start` (receiver shows PIN), then `POST /pair-setup-pin` bplists: `{method:"pin", user}` → `{pk, salt}`, then `{pk, proof}` → `{proof}` (SRP-6a), then `{epk, authTag}` → `{epk, authTag}` (AES-GCM-wrapped Ed25519 keys) | `-pin [nnnn]`, `-reg` persists trusted client keys. Later sessions pass `pair-verify` with the registered key (`check_register` callback) |
| Password / PIN-as-password | `401` + `WWW-Authenticate: Digest realm="raop", nonce=…` on the first `SETUP`; sender retries with RFC 2617 `Authorization` | `-pw [pwd]`. With no pwd, a random 4-digit PIN is shown each time |
| Device allow/block list | `deviceID` / `model` / `name` in the first `SETUP` | `-restrict`, `-allow`, `-block` via `report_client_request` |

### 2.3 What is gated (do not advertise)
- **MFi authentication** (`et=4` MFiSAP, `POST /auth-setup`, feature bits 26/51): the receiver must prove possession of an Apple-issued MFi authentication coprocessor or certificate. Open receivers cannot. Never set these bits.
- **HomeKit pairing** (HAP SRP pair-setup with TLV8 bodies, `X-Apple-HKP` header, bits 43/46/48, ChaCha20-Poly1305-encrypted RTSP): needs no Apple certificate. shairport-sync (via `pair_ap`) and airplay2-receiver implement it for AirPlay 2 audio, with transient pairing and Home-app pairing.
  - It does **not** unlock mirroring or video in any open receiver today. java-airplay says its "HAP/DataStream sessions are video-only today" for its *sender* side.
  - Treat HAP as optional, for buffered audio and multiroom only.
- **FairPlay Streaming (FPS) DRM** for video content: protected HLS (`#EXT-X-KEY` with `KEYFORMAT="com.apple.streamingkeydelivery"`, `skd://` key URIs) needs an Apple-licensed FairPlay client implementation on the receiver. Licensed TV makers (Samsung, LG, Sony, …) have one. Open receivers do not and cannot. See §4.7.
- **Risk**: UxPlay README warns Apple may drop the "Legacy Protocol" (legacy pairing + fp-setup) in a future iOS. iOS 27 still supports it.

---

## 3. Screen mirroring

### 3.1 RTSP sequence (sender → receiver, one TCP control connection, `CSeq` on every request)
| # | Request | Body (bplist) | Receiver response |
|---|---|---|---|
| 1 | `GET /info` ×2 | qualifier / none | §1.5 |
| 2 | `POST /pair-setup`, `POST /pair-verify` ×2 | binary | §2.1 (skipped if bit 27 off) |
| 3 | `POST /fp-setup` ×2 | binary | FairPlay responses |
| 4 | `SETUP` (session) | `ekey`, `eiv`, `et`, `deviceID`, `macAddress`, `model`, `name`, `osName`, `osVersion`, `osBuildVersion`, `sourceVersion`, `sessionUUID`, `timingProtocol:"NTP"`, `timingPort`; iOS 27 adds `combinedGetInfoWithControlSetup:true`, `sessionCorrelationUUID`, `internalBuild`, `diagnosticsAndUsage`, header `X-Apple-ProtocolVersion: 1` | `{timingPort: <our NTP port>, eventPort: 0}` |
| 5 | `GET_PARAMETER` / `SET_PARAMETER` | `text/parameters` `volume` | |
| 6 | `SETUP` (video) | `streams:[{type:110, streamConnectionID:<uint64>, …}]` | `{streams:[{type:110, dataPort:<tcp port>}]}` |
| 7 | `SETUP` (audio) | `streams:[{type:96, ct:8, spf:480, audioFormat:0x1000000 (AAC-ELD/44100/2), controlPort, isMedia, usingScreen:true, latencyMin/Max}]` | `{streams:[{type:96, dataPort, controlPort}]}` |
| 8 | `RECORD` | — | header `Audio-Latency: <samples>` |
| 9 | `POST /feedback` every ~2 s | — | `200` (heartbeat; UxPlay times out the client if these stop) |
| 10 | `SET_PARAMETER volume: <dB>` | `text/parameters` | `-144` = mute, `-30..0` range |
| 11 | `TEARDOWN` | `{streams:[{type:110}]}` / `[{type:96}]` / none | §7 |

- `isRemoteControlOnly:true` or `timingProtocol:"PTP"` in step 4 means the sender wants AirPlay 2 modes UxPlay does not support (UxPlay logs an error and carries on).
- The iOS 27 key `combinedGetInfoWithControlSetup` is ignored by UxPlay. Issue #535: in that report it turned out to be harmless, and the real failure was HEVC without bit 42.

### 3.2 Mirror TCP stream framing (`lib/raop_rtp_mirror.c`, airplay-spec `stream_packets.md`)
The sender connects to `dataPort` and sends repeated `[128-byte header][payload]`. Header fields are little-endian:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | payload size |
| 4 | 1 | type: `0x00` video (AES-CTR encrypted), `0x01` codec config (unencrypted), `0x02` heartbeat (old senders), `0x05` "streaming report" (bplist stats, ~1/s, no timestamp) |
| 5 | 1 | `0x10` on IDR video packets |
| 6–7 | 2 | option: `0x16 0x01` H.264 config; `0x1e 0x01` H.265 config; `0x56`/`0x5e` = config sent while **the stream is pausing** (sender asleep or screen locked). The next `0x16`/`0x1e` resumes |
| 8 | 8 | NTP timestamp in the sender's clock (presentation time) |
| 16/20 | 4+4 | float width/height of the source (codec packets) |
| 40/44 | 4+4 | duplicate source width/height |
| 48/52 | 4+4 | float x/y offset |
| 56/60 | 4+4 | float width/height |

Codec packet payloads:
- **H.264**: `avcC` record (version, profile, level, SPS, PPS). UxPlay converts it to Annex-B start codes and puts SPS/PPS before the next IDR, which has the same timestamp.
- **H.265**: payload carries `hvc1` at offset 4. VPS/SPS/PPS sit at offset `0x75` with markers `a0/a1/a2 00 01 00`, each followed by a 2-byte length. Without bit 42, an HEVC-capable sender sends a codec packet with **no payload**. UxPlay detects that and reports "use -h265".
- New codec packets arrive on rotation and on resolution changes. The decoder must reconfigure on every one.

Video payloads are AVCC length-prefixed NAL units. They are encrypted with AES-128-CTR, with key and IV derived from the session AES key and `streamConnectionID` (`lib/mirror_buffer.c`). This is AirPlay's link encryption, not content DRM.

Pixels the sender marks as DRM-protected (e.g. Apple TV app playback while mirroring) are blanked by the sender. Only their audio arrives (UxPlay README).

### 3.3 Timing
- **NTP**: the receiver is an NTP *client* of the sender. It sends timing requests (RTP-style, payload types 82/83) from its `timingPort` to the sender's `timingPort` every ~3 s, and computes the clock offset (`lib/raop_ntp.c`; 1.74 can use kernel receive timestamps).
- Mirror video and audio timestamps are converted to local time with that offset. Mirror latency is low by design. UxPlay renders video as soon as it arrives unless sync is on.
- PTP is used only in AirPlay 2 buffered-audio sessions (§5.3).

### 3.4 Resolution, refresh and codec choice
- The receiver offers `displays[0].width/height/refreshRate/maxFPS` in `/info`. Height is the controlling value. The sender scales width to aspect (UxPlay `-s WxH@R`, `-fps`).
- The sender picks the actual size, reported in codec packets. iPhones send portrait-shaped frames.
- H.265 needs bit 42 **and** a 4K height request (UxPlay `-h265` sets the default to 3840x2160@30). Only recent devices (M-series Macs/iPads, some iPhones) send HEVC.
- `overscanned` reserves a border.
- macOS "Use As Separate Display" (extended desktop) uses the same type-110 stream. The receiver's advertised display size becomes the virtual display size [U: not confirmed in sources; test on macOS 26/27].

### 3.5 Device class
Advertising as `AppleTV*` makes iOS treat the receiver as a remote-playback (speaker-class) route: the iPhone microphone is disabled while mirroring. Mac receivers show as "computer" routes and keep the mic. Making an open receiver advertise as display-class is an open question (UxPlay #463) [U].

---

## 4. Video handover ("AirPlay video")

This is what happens when a user taps AirPlay inside YouTube, Safari, an `AVPlayerViewController` app, or the Photos app, and the receiver advertises bits 0+4. The phone stays a remote control and the receiver plays the media.

### 4.1 Connections
1. The RTSP control connection may already exist (mirroring or audio) or is opened first for pairing, fp-setup and `SETUP` [U for the cold-start order; UxPlay's code assumes a RAOP connection may be running and stops its mirror, audio and NTP services when the first HTTP AirPlay connection arrives].
2. One or more **HTTP/1.1** connections to the same port, each carrying `X-Apple-Session-ID: <uuid>` (same for the whole playback session), `X-Apple-Device-ID`, `User-Agent: AirPlay/<ver>`.
3. One of them is upgraded by `POST /reverse` to a **reverse-HTTP (PTTH/1.0) event channel**. From then on the *receiver* sends HTTP requests (`POST /event`) to the *sender* on that socket.
4. If the sender was mirroring, it sends `GET /server-info` (UxPlay sets `hls_pending`), then `TEARDOWN {type:110}` on RTSP, then the HTTP sequence.

### 4.2 HTTP endpoints (sender → receiver)
| Request | Body / query | Response | Notes |
|---|---|---|---|
| `GET /server-info` | — | xml plist (§1.5) | keep compact |
| `POST /reverse` | headers `Upgrade: PTTH/1.0`, `Connection: Upgrade`, `X-Apple-Purpose: event` | `101 Switching Protocols`, `Upgrade: PTTH/1.0`, `Connection: Upgrade` | UxPlay allows **one** EVENT socket |
| `POST /play` | bplist (modern) or `text/parameters` (legacy iTunes) | `200` | §4.3 |
| `POST /scrub?position=<sec>` | — | `200` | seek |
| `GET /scrub` | — | `text/parameters` `duration: x\nposition: y` | legacy. UxPlay does not route it |
| `POST /rate?value=<f>` | — | `200` | 0 = pause, 1 = play |
| `POST /stop` | — | `200` | end session (java-airplay also accepts `/stop2`) |
| `GET /playback-info` | — | xml plist: `duration`, `position`, `rate`, `readyToPlay`, `playbackBufferEmpty`, `playbackBufferFull`, `playbackLikelyToKeepUp`, `loadedTimeRanges[{start,duration}]`, `seekableTimeRanges[...]`, (`stallCount`) | polled ~1/s, drives the phone's scrubber |
| `PUT /setProperty?<name>` | bplist `{value: …}` | bplist `{errorCode: 0}` | §4.5 |
| `POST /getProperty?<name>` | — | bplist `{errorCode:0, value:…}` | spec: `playbackAccessLog`, `playbackErrorLog`. UxPlay returns empty 200 |
| `POST /action` | bplist `{type, params}` | `200` | `unhandledURLResponse`, `playlistInsert`, `playlistRemove` |
| `POST /fp-setup2` | FairPlay v1 | (UxPlay `421`) | see §1.5 |
| `PUT /setProperty?selectedMediaArray` | track list | | audio/subtitle choice |

### 4.3 `POST /play` body (bplist)
Keys confirmed in UxPlay or java-airplay code:
- `Content-Location`: the media URL (see 4.4).
- `Start-Position-Seconds` (real).
- `uuid`: the item ID, reused by `playlistRemove` / `playlistInsert` and events.
- `clientProcName`: e.g. `YouTube`, `Crunchyroll`.
- `volume` (linear).

Legacy senders use `Start-Position` (fraction 0–1). Other keys seen in captures (`clientBundleID`, `rate`, `playbackRestrictions`, `SenderMACAddress`, `model`, `osBuildVersion`, `mightSupportStorePastisKeyRequests`, …) are [U]. Log and store all keys.

### 4.4 Two content models

**(A) Direct URL**: `Content-Location: http(s)://…` (a progressive MP4, or an HLS `.m3u8`)
- The receiver's player fetches the URL from the internet itself.
- Used by Safari / WebKit `<video>`, Vimeo, VK Video and most AVPlayer apps that play a public URL (UxPlay #578, #629; README notes Safari via the AirPlay button).
- UxPlay: **only on master after 1.73.7** (commit `6f92504`, 2026-08-07). `v1.73.7` rejects any URL not ending in `/master.m3u8`.
- Risks:
  - Signed URLs or cookies the sender had but the receiver lacks [U].
  - Stream selection on weak decoders: UxPlay's `-rpi/-custom` filter only sees FCUP masters. Remus Lazar's demuxer-probe patch (#578/#581) applies it to direct URLs too.

**(B) `mlhls://localhost/…` + FCUP**: the sender serves the playlists
- `Content-Location: mlhls://localhost/master.m3u8` (YouTube; the `mlhls` scheme is visible in java-airplay tests). The playlists live inside the sender's media stack. The receiver asks for them over the reverse channel ("FCUP" = sender fulfils URL requests).
- **Order** (UxPlay `http_handler_play` → `fcup_request` → `http_handler_action`):
  1. Sender: `POST /reverse` → receiver: `101`.
  2. Sender: `POST /play {Content-Location: mlhls://localhost/master.m3u8, uuid, Start-Position-Seconds, clientProcName}` → `200`.
  3. Receiver → sender (on the reverse socket): `POST /event`, `Content-Type: text/x-apple-plist+xml`, `X-Apple-Session-ID`, body:
     ```
     {type:"unhandledURLRequest", sessionID:1,
      request:{FCUP_Response_URL:"mlhls://localhost/master.m3u8", FCUP_Response_RequestID:n,
               FCUP_Response_ClientInfo:1, FCUP_Response_ClientRef:40030004, sessionID:1,
               FCUP_Response_Headers:{X-Playback-Session-Id:<session>, User-Agent:"AppleCoreMedia/1.0.0.11B554a (Apple TV; …)"}}}
     ```
     The sender answers the reverse request with `200`.
  4. Sender: `POST /action` bplist `{type:"unhandledURLResponse", params:{FCUP_Response_URL, FCUP_Response_RequestID, FCUP_Response_StatusCode, FCUP_Response_Data:<playlist bytes>, (FCUP_Response_Headers)}}` → `200`.
  5. The receiver rewrites the master (`mlhls://localhost/…` → `http://localhost:<port>/…`, optional language and codec filtering), then FCUPs **each media playlist** listed in it, one after another.
  6. After the last one, the player starts on `http://localhost:<port>/master.m3u8`. The receiver's own HTTP server returns the stored playlists (requests with `Host:` and no session ID), expanding YouTube's `#YT-EXT-CONDENSED-URL` media playlists to standard form.
  7. Media **segments** in YouTube's playlists are absolute `https://…googlevideo.com` URLs, so the player fetches them directly.
  8. Whether other apps put segments or keys behind `mlhls://` too (needing FCUP for every segment) is [U]. Build the proxy so it can.
- Ads: YouTube interleaves short `/play` items. UxPlay keeps up to 10 playlists (`MAX_AIRPLAY_VIDEO`), drops items <90 s, and resumes a re-requested `uuid` at its saved position.

### 4.5 Queue, properties and events (needed for "behave like Apple TV")
- **`playlistRemove`** `{params:{item:{uuid}}}`: stop the current item and save its position. UxPlay does this.
- **`playlistInsert`** `{params:{item:{…same keys as /play…}}}`: start the new item. Used by VK Video (#629) and Crunchyroll (#437) to move to the next video **without a new `/play`**. UxPlay logs `FIXME` and returns `400`, so the phone spins for ~50 s and gives up.
- **Reverse state events** (receiver → sender, `POST /event`, `category:"video"`): airplay-spec lists `state` = `loading|playing|paused|stopped`. #629 and java-airplay (`PropertyListUtil`) report what iOS 26 requires after a `playlistInsert`:
  - `{category:video, sessionID:<odd counter 1,3,5…>, state:"loading", params:{uuid}}`.
  - Then `state:"playing"` with `params:{uuid, duration, position, rate, readyToPlay, playbackBufferEmpty/Full, playbackLikelyToKeepUp, loadedTimeRanges, seekableTimeRanges}`. **Top-level copies are ignored.**
  - At end of item: `loading` → `{type:"itemPlayedToEnd", uuid}` → `state:"stopped", reason:"ended"` → `{type:"itemRemoved", sessionID:<X-Apple-Session-ID string>, uuid}` → `{type:"currentItemChanged"}` → `stopped`.
  - A later `playlistRemove` of an already-ended item must get `200`.
  - **Reverse requests must be serialized**: send the next only after the sender answered the previous one.
  - UxPlay sends no state events at all, only FCUP requests.
- **`setProperty`** names seen (UxPlay list):
  - `actionAtItemEnd`: 0 advance, 1 pause, 2 none.
  - `selectedMediaArray`: audio/subtitle rendition choice.
  - `forwardEndTime` / `reverseEndTime`: CMTime dicts.
  - `interstitialEvents`, `textMarkupArray`, `isInterestedInDateRange`.
  - Reply `{errorCode:0}`.
- **`/playback-info`**: must always return a plist. When the item ended, UxPlay closes the connection and resets. When info is not available it returns an empty 200. Apple TV reports `readyToPlay:false` while loading [U].

### 4.6 Which apps use which path
| Sender app | Path | UxPlay v1.73.7 | UxPlay master | Notes |
|---|---|---|---|---|
| YouTube (iOS) | (B) mlhls + FCUP, `clientProcName=YouTube`, condensed playlists, AI-dub audio renditions | Works | Works (+`-lang/-slang`) | Live streams (#471) and ads are edge cases |
| Safari / WebKit `<video>` (iOS, macOS) | (A) direct URL (MP4 or HLS) | No | Works | YouTube *web* in Safari gives a dubbed-language default (#601) |
| Vimeo, VK Video, generic AVPlayer apps with public URLs | (A); VK uses `playlistInsert` for next item | No | First item works; next item fails (#629) | |
| Crunchyroll and similar | `playlistInsert` (+ likely DRM) | No | No | #437 |
| Photos app videos (local files) | [U] probably (B) or a sender-hosted HTTP URL | Untested | Untested | Must test |
| Apple TV app, Disney+, Max, Prime Video, Apple Music videos | HLS with **FairPlay Streaming** | Impossible | Impossible | Licensed-receiver only. Netflix dropped AirPlay support in 2019 [U: not from the sources above] |
| Apps that disable external playback | Fall back to mirroring | — | — | Protected frames blank in mirror |

### 4.7 Why UxPlay "only works with YouTube" and what arbitrary AVPlayer apps need
Gaps found in the code (`lib/http_handlers.h`, `lib/airplay_video.c`):
1. `v1.73.7` accepts only a `Content-Location` containing `/master.m3u8` (the YouTube mlhls shape). Master adds plain `http(s)`.
2. FCUP handling assumes YouTube:
   - The master is recognised by the substring `/master.m3u8`.
   - All media playlists are prefetched once, in order.
   - `#YT-EXT-CONDENSED-URL` is expanded.
   - Live playlists are never refreshed.
   - Relative URIs and query strings are fragile (PLAN.md notes failures).
   - `FCUP_Response_StatusCode` is ignored.
3. `playlistInsert` is not implemented, and there is no queue model (`actionAtItemEnd`).
4. No reverse `POST /event` state machine. Senders that wait for `loading`/`playing` hang.
5. `getProperty` returns nothing. `selectedMediaArray` is ignored, so audio and subtitle choices are made on the receiver.
6. A single EVENT socket. No support for `isRemoteControlOnly` / stream types 120 ("Playback") and 130 ("Remote Control"), which AirPlay 2 senders may use toward real Apple TVs. Type 120 is undocumented anywhere public [U].
7. Feature bit 2 (VideoFairPlay) is advertised although FPS cannot work, so DRM apps try handover and then fail [U whether clearing it makes them fall back gracefully].

**What we need in the Android layer:**
- A generic URL router: (A) → ExoPlayer `MediaItem` with the URL. Set the `User-Agent` to an AppleCoreMedia string. Pass through any headers found in the plist [U].
- (B) → serve `http://127.0.0.1:<port>/…` from a **lazy FCUP proxy**: on each player request, map the path back to `mlhls://localhost/<path>` and issue FCUP. Support playlists, segments and keys. Re-FCUP live media playlists every target duration.
- Queue semantics keyed by `uuid` (`/play`, `playlistInsert`, `playlistRemove`, `actionAtItemEnd`), plus resume positions.
- The reverse-event state machine from §4.5, sent serially from one thread on the EVENT socket.
- Accurate `/playback-info` from ExoPlayer: position, duration, buffering, ranges, `rate` 0 when paused.
- DRM detection: if a playlist carries `EXT-X-KEY`/`EXT-X-SESSION-KEY` with `KEYFORMAT="com.apple.streamingkeydelivery"` or `skd://`, stop at once, send `state:"stopped"` with an error [U on the exact error key], and show "This content is protected and can't be played on this receiver". Do not hang the phone.
- `selectedMediaArray` → ExoPlayer track selection. RTSP `SET_PARAMETER volume` → player volume (UxPlay master routes volume to the HLS player, PR #536, 2026-07-20).

---

## 5. Audio-only AirPlay

### 5.1 Realtime (type 96), what UxPlay implements
1. `SETUP` session (as §3.1 step 4).
2. `SETUP streams:[{type:96, ct:2 (ALAC), spf:352, audioFormat:0x40000 (ALAC/44100/16/2), controlPort, isMedia:true, usingScreen:false}]` → `{type:96, dataPort, controlPort}`.
3. `RECORD`, then UDP RTP to `dataPort`: PT 96 audio, AES-128-CBC with the session key/IV (`eiv`).
4. The control port carries PT 84 sync (NTP ↔ RTP time), PT 85 retransmit request (receiver → sender), and PT 86 retransmit reply. Timing uses NTP (§3.3).
5. Codecs by `ct`: 1 LPCM, 2 ALAC, 4 AAC-LC, 8 AAC-ELD, 32 Opus. `audioFormat` bit table: emanuelecozzi "Audio formats" (e.g. `0x400000` AAC-LC/44100/2, `0x1000000` AAC-ELD/44100/2).
6. `FLUSH` with `RTP-Info: seq=…` on pause or seek.

Without video bits, AVKit "video" apps also arrive here as ALAC audio-only.

### 5.2 Metadata, volume, remote control
- `SET_PARAMETER` on the RTSP channel (`raop_handler_set_parameter`):
  - `text/parameters` `volume: <dB>` (-144 mute, -30..0).
  - `text/parameters` `progress: start/curr/end` (RTP timestamps; position = (curr−start)/44100).
  - `application/x-dmap-tagged` DMAP now-playing (`minm` title, `asar` artist, `asal` album).
  - `image/jpeg|png` cover art.
  - Feature bits 15/16/17 and `md=0,1,2` enable these.
- AirPlay 2 alternative: bit 50 → bplist now-playing via `POST /command`; shairport-sync recommends it for AP2. Not in UxPlay.
- `GET_PARAMETER volume` → the receiver reports its volume (UxPlay master keeps it consistent with the sender, commit `412c5b7`).
- **DACP** (classic remote control):
  - The sender includes `DACP-ID: <64-bit hex>` and `Active-Remote: <token>` headers.
  - The receiver browses `_dacp._tcp` for `iTunes_Ctrl_<DACP-ID>`, then sends `GET /ctrl-int/1/<cmd>` with header `Active-Remote`.
  - Commands: `play`, `pause`, `playpause`, `nextitem`, `previtem`, `stop`, `volumeup`, `volumedown`, `mutetoggle`, `beginff`, `beginrew`, `playresume`, `shuffle_songs`.
  - UxPlay only **exports** DACP-ID/Active-Remote (`export_dacp`, `-dacp` file). It does not send commands.
- **AirPlay 2 equivalent**: `SETUP {isRemoteControlOnly:true}` → `eventPort`. Then `SETUP streams:[{type:130, channelID, clientUUID, seed, controlType:2, wantsDedicatedSocket}]` → `{type:130, streamID, dataPort}`. The data channel carries MediaRemote (MRP) protobufs inside DataStream framing, ChaCha20-Poly1305 with HKDF salt `DataStream-Salt<seed>` (pyatv). This requires HAP pairing. No open-source receiver implements it.

### 5.3 Buffered (type 103) and multiroom
- Needs bits 40 (+`srcvers ≥ 354.54.6`) and 41 (+`srcvers ≥ 366`) and HAP pairing.
- `SETUP` session with `timingProtocol:"PTP"`, `timingPeerInfo`, `groupUUID`, then `SETUP streams:[{type:103, audioFormat, ct, spf, shk:<32-byte key>}]`. The receiver answers `{type:103, dataPort:<TCP>, controlPort, audioBufferSize}`.
- Then `SETPEERS`/`SETPEERSX`, `SETRATEANCHORTIME` (start/stop against the PTP clock), `FLUSHBUFFERED`.
- Formats include AAC-LC 44.1/48 k, ALAC 48k/24, 5.1/7.1 AAC (shairport-sync `AIRPLAY2.md`).
- **PTP needs UDP 319 (event) and 320 (general)** with exclusive access (shairport-sync's NQPTP). Ports below 1024 need root/`CAP_NET_BIND_SERVICE`, so a normal Android app can't bind them. We do not offer bits 40/41, and senders then use realtime type 96 (shairport-sync can "revert to classic AirPlay").

---

## 6. Photos and slideshows
- Spec (2012, iOS 5 era):
  - `PUT /photo` with JPEG body and headers `X-Apple-AssetKey: <uuid>`, `X-Apple-Transition` (e.g. `Dissolve`), `X-Apple-AssetAction: cacheOnly|displayCached` (bit 13 photo caching).
  - `GET /slideshow-features` (themes), `PUT /slideshows/1` (slideshow state).
  - Events `category:"photo"` (`stopped`) and `category:"slideshow"` (`loading|playing|stopped`) on the reverse channel.
- UxPlay advertises bits 1, 5 and 13 but routes **no** `/photo` or `/slideshow*` handler. Such requests are logged "Unhandled".
- Whether current iOS 26–27 Photos still uses `PUT /photo` toward an AppleTV-model receiver, or mirrors or uses video handover for stills, is **[U]**. ConnectSDK issue #157 ("POST Photo not working anymore") suggests Apple changed it long ago.
- Action: capture what iOS 26/27 Photos sends to our receiver. Then either implement a minimal `/photo` (decode JPEG, show full-screen, reply 200), or clear bits 1/5/13 so Photos falls back to mirroring.

---

## 7. Session lifecycle

### 7.1 TEARDOWN
- `TEARDOWN {streams:[{type:96}]}` stops audio only; mirroring continues.
- `TEARDOWN {streams:[{type:110}]}` stops mirroring. If `GET /server-info` came first (`hls_pending`), it is a mirror→video switch (`RESET_TYPE_RTP_TO_HLS_TEARDOWN`).
- `TEARDOWN` with no `streams` ends the session: destroy RTP and mirror state, shut down HLS connections. The response carries `Connection: close`.
- **iOS 27 change** (UxPlay commit `546820c`, 2026-08-29, released in **1.73.7**):
  - iOS 27 no longer sends `TEARDOWN type 110` when the user stops mirroring. It sends only the stream-less TEARDOWN.
  - 1.73.7 therefore also resets the video renderer in the stream-less branch (`raop_handlers.h`: "fix for iOS >= 27").
  - 1.73.7 also fixes a security advisory in `raop_handlers.h` (GHSA-479c-ww7g-wgp8: stack overflow + NULL dereference).
- Other iOS 27 issues:
  - #539: iOS re-SETUPs audio on every media resume. A renderer that ignores a same-codec SETUP goes silent. Our AudioTrack layer must restart on every audio `SETUP`.
  - #535: enable H.265 (bit 42) by default.

### 7.2 Mode switching
- **Mirror → video**: `GET /server-info` → `TEARDOWN 110` → `POST /reverse` → `POST /play`. UxPlay stops mirror, audio and NTP when the first `X-Apple-Session-ID` connection appears.
- **Video → mirror**: `POST /stop` (or `playlistRemove`), HTTP connections close. A new RTSP `SETUP type 110` destroys stored HLS state (`raop_destroy_airplay_video`).
- **Mirror ↔ audio-only**: stop mirroring, start an audio-mode connection. Cover-art display stops and restarts (README).
- Liveness: `/feedback` every ~2 s. UxPlay's httpd drops dead connections after 60 s (commit `e60d981`).

### 7.3 Multiple senders, takeover
- Apple TV accepts several paired clients (separate pair-verify secrets) and lets a new sender take over.
- UxPlay supports **one** client: a second RTSP connection gets `409 Conflict: Server is connected to another client`, unless `-nohold`. With `-nohold`, all existing connections drop and the newcomer wins (`RESET_TYPE_NOHOLD`).
- For a TV, "newest sender wins" (`-nohold`) matches Apple TV user expectations. Whether Apple TV prompts before takeover is [U].

### 7.4 PIN and password
See §2.2.
- `-pin`: one-time SRP pairing; the client is remembered via `-reg`. `sf=0x8c`, `pw=true`.
- `-pw`: HTTP Digest on every connection; `sf=0x84`.
- The TV must show the PIN on screen (`display_pin` callback) even when our app is in the background.

---

## 8. Ports
| Port | Proto | Use | UxPlay `-p` legacy default |
|---|---|---|---|
| AirPlay/RTSP+HTTP | TCP | control, video-handover HTTP, local HLS server | 7000 |
| (second TCP) | TCP | (UxPlay reserves it) | 7001 |
| Mirror data | TCP | type-110 stream | 7100 |
| Audio data | UDP | type-96 RTP | 6000 |
| Audio control | UDP | sync/retransmit | 6001 |
| Timing | UDP | NTP client | 7011 |
| mDNS | UDP | 5353 multicast | Android needs `WifiManager.MulticastLock` |
| PTP | UDP | 319/320 | not used (needs root) |
By default UxPlay picks random ports unless `-p` is given. All of these are ≥1024 and fine on Android.

---

## 9. Receiver behaviour checklist

Status is UxPlay master `05b0275`. "Ours" = what the Android layer must add on top.

| Feature | Endpoints / stream | UxPlay status (files) | Ours |
|---|---|---|---|
| mDNS advertise | `_airplay._tcp`, `_raop._tcp` TXT | Supported (`dnssd.c`, `dns_sd/`, `mdnsd/`) | Android `NsdManager` *or* UxPlay `mdnsd` + MulticastLock. Re-advertise on network change. Boot-time foreground service |
| Feature mask | TXT `features`, `/info` | Supported (set in `uxplay.cpp`, which we replace) | Our own setter: bits 0+4 (video), 42 (HEVC if MediaCodec has an HEVC decoder). Decide bits 1/5/13 (photo) and 2 (FairPlay) after testing |
| `/info` display caps | `GET /info` `displays[]` | Supported, static (`raop_handlers.h`) | Fill from `Display.getMode()` (4K/1080p, refresh). `initialVolume` from our volume |
| Legacy pairing | `/pair-setup`, `/pair-verify` | Supported (`pairing.c`) | Persist the Ed25519 key (stable `pk` across reboots) |
| FairPlay SAP (type 0x03) | `/fp-setup` | Supported (`fairplay_playfair.c`, `playfair/`) | Nothing (track upstream in case Apple drops the legacy protocol) |
| `/fp-setup2`, MFi `auth-setup` | — | Unsupported by design | Never advertise bits 26/51 |
| HomeKit / transient pairing | TLV8 pair-setup, `X-Apple-HKP` | Missing | Skip (only buffered/multiroom audio benefits) |
| PIN pairing | `/pair-pin-start`, `/pair-setup-pin` | Supported (`raop_handlers.h`, `srp.c`) | On-screen PIN overlay; persist registered clients |
| Password | Digest on `SETUP` | Supported | Settings UI |
| Allow/deny clients | first `SETUP` `deviceID/name/model` | Supported via callback | Prompt-to-allow UI on the TV |
| Mirroring H.264 | `SETUP type 110` + TCP stream | Supported (`raop_rtp_mirror.c`, `mirror_buffer.c`) | MediaCodec `video/avc` on a Surface. Reconfigure on each codec packet. Low-latency mode. Pause/resume on `0x56/0x5e` |
| Mirroring H.265 | bit 42, `hvc1` codec packet | Supported (`-h265` path) | Turn on by default (iOS 27 #535). `video/hevc` decoder. Request a 4K height when the panel and decoder allow |
| Mirror audio AAC-ELD | `SETUP type 96 ct=8` | Supported (`raop_rtp.c`, `raop_buffer.c`) | MediaCodec AAC-ELD (CSD from `audioFormat`) → AudioTrack; A/V sync from NTP-mapped timestamps (watch drift, cf. jqssun #82/#72) |
| NTP timing | `timingPort` | Supported (`raop_ntp.c`) | Nothing |
| macOS extended display | type 110 | Same stream [U] | Test on macOS 26/27 |
| iOS 27 TEARDOWN | stream-less `TEARDOWN` | Supported since 1.73.7 (`raop_handlers.h`) | `video_reset` → hide surface, return to idle |
| Audio re-SETUP on resume | repeated `SETUP type 96` | Library OK; renderer bug #539 is GStreamer-side | Restart AudioTrack on every audio SETUP |
| Audio-only ALAC | `SETUP type 96 ct=2` | Supported | ALAC decode (UxPlay's renderer uses GStreamer; we need a decoder, e.g. Apple's open-source ALAC or FFmpeg. Check whether MediaCodec offers ALAC on target devices [U]) |
| AAC-LC audio | `ct=4` | Supported (parsing) | MediaCodec AAC |
| Metadata / art / progress | `SET_PARAMETER` dmap / jpeg / `progress` | Supported (callbacks) | Now-playing screen + Android `MediaSession` |
| Volume | `SET_PARAMETER volume` (dB) | Supported | Map to stream gain, not system volume [design choice] |
| DACP remote | `DACP-ID`, `Active-Remote` | Partial: export only (`raop.c` `export_dacp`) | Browse `_dacp._tcp`; send `/ctrl-int/1/<cmd>` from TV remote / MediaSession buttons |
| AirPlay 2 remote control | `isRemoteControlOnly`, type 130 | Missing | Skip (HAP + MRP) |
| Buffered audio / PTP | type 103, bits 40/41, SETPEERS, SETRATEANCHORTIME | Missing | Skip (ports 319/320 need root) |
| Video handover, YouTube | `/play mlhls`, FCUP | Supported (`http_handlers.h`, `airplay_video.c`, `fcup_request.h`) | ExoPlayer on `http://127.0.0.1:<port>/master.m3u8` (jqssun's approach) |
| Video handover, direct URL | `/play http(s)` | Partial: master only, not 1.73.7 | ExoPlayer direct; AppleCoreMedia UA; variant cap for weak SoCs |
| Generic FCUP proxy | `/event unhandledURLRequest` ↔ `/action unhandledURLResponse` | Partial (YouTube-shaped, prefetch-all) | Lazy proxy for playlists, segments and keys; live refresh; relative URIs and query strings |
| Queue | `/action playlistInsert` / `playlistRemove`, `actionAtItemEnd` | Partial: remove only; insert = FIXME 400 (#629) | Implement per §4.5 |
| Reverse state events | `POST /event category:video` | Missing | Implement and serialize (§4.5) |
| Transport control | `/scrub`, `/rate`, `/stop` | Supported | Map to ExoPlayer seek/pause/stop |
| Playback info | `GET /playback-info` | Partial (callback-driven) | Always answer from ExoPlayer state |
| Track selection | `setProperty?selectedMediaArray` | Ignored | ExoPlayer `TrackSelectionParameters` |
| `getProperty` | access/error logs | Empty 200 | Reply `{errorCode:0, value:[]}` |
| FairPlay-protected video | `EXT-X-KEY skd://` | Impossible | Detect, stop, tell the user and the phone quickly |
| Photos / slideshow | `PUT /photo`, `/slideshow*` | Missing (bits advertised) | Capture iOS 27 behaviour; implement minimal `/photo` or clear bits |
| Multiple senders | 2nd RTSP connection | Partial: single client, `409` unless `-nohold` | Default "newest wins"; optional confirm prompt |
| Wake/foreground on connect | (Android only) | n/a | Start activity, wake screen / HDMI-CEC on first `SETUP` or `/play` (jqssun #92 gap) |

---

## 10. Sources
- UxPlay source and README (`lib/raop.c`, `lib/raop_handlers.h`, `lib/http_handlers.h`, `lib/airplay_video.c`, `lib/fcup_request.h`, `lib/raop_rtp_mirror.c`, `lib/mirror_buffer.c`, `lib/dnssdint.h`, `lib/dns_sd/dns_sd.c`, `uxplay.cpp`): https://github.com/FDH2/UxPlay (master `05b0275`; release notes v1.73.7).
- UxPlay issues: #437 (Crunchyroll playlistInsert), #463 (display-class), #471 (YT live), #535 (iOS 27 / HEVC), #539 (audio re-SETUP), #578/#581 (direct-URL filter), #601 (Safari YouTube language), #629 (playlistInsert + event state machine).
- Unofficial AirPlay Protocol Specification (features, status flags, service discovery, video, photos, screen mirroring, audio): https://openairplay.github.io/airplay-spec/ (source: github.com/openairplay/airplay-spec).
- Original 2012 spec (reverse HTTP, event states, photo caching): https://nto.github.io/AirPlay.html
- AirPlay 2 internals (features with srcvers conditions, SETUP stream types 96/103/110/120/130, `ct` values, audio format bitmask): https://emanuelecozzi.net/docs/airplay2/ (site certificate expired at fetch time; content retrieved directly).
- pyatv protocol docs (AirPlay TXT, remote-control session, type 130, DataStream encryption, `/info` capture of AppleTV6,2): https://pyatv.dev/documentation/protocols/
- shairport-sync `AIRPLAY2.md`, `rtsp.c`, `shairport.c` (buffered audio, NQPTP ports 319/320, HomeKit bits 46/47): https://github.com/mikebrady/shairport-sync
- openairplay/airplay2-receiver (`ap2-receiver.py`, `ap2/connections/stream.py`: srcvers thresholds, X-Apple-HKP values, REALTIME=96/BUFFERED=103): https://github.com/openairplay/airplay2-receiver
- serezhka/java-airplay (feature enum incl. bits 49/54–61, compact `/server-info`, playlistInsert, reverse video events): https://github.com/serezhka/java-airplay
- ConnectSDK-iOS #157 (photo protocol change): https://github.com/ConnectSDK/Connect-SDK-iOS/issues/157
