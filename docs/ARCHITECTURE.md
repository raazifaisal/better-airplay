# Architecture

Clean architecture with MVVM in the presentation layer. No AndroidX: the small framework pieces
(ViewModel base, retention, DI) are hand-written, so the APK stays light. The package is `dev.better.airplay`.

```
            presentation ──────────┐
   (Views, ViewModels, Models)     │ calls use cases, reads StatusStore
                                   ▼
 platform ───────────────────►  domain  ◄─────────────── data
 (Service, Boot, Controller)    (pure Kotlin:            (UxPlay JNI engine, NsdManager,
  implements ReceiverController  models, ports,           SharedPreferences, MediaCodec caps)
                                 use cases, StatusStore)  implements ports
                                   ▲
                    di/AppContainer wires everything (one instance per process)
```

**Dependency rule:** `domain` imports nothing from Android or the other layers. `data`, `platform` and
`presentation` depend on `domain`, never on each other, except that `di` knows every layer
in order to wire them. The native code (`app/src/main/cpp`) sits behind `data/engine`.

## Singletons and lifetimes

| Object | Lifetime | Owner |
|---|---|---|
| `AppContainer` | process | `App.onCreate` (created exactly once) |
| Repositories, `DeviceInfo`, `UxPlayEngine`, `NsdServiceAdvertiser`, `StatusStore`, `ReceiverController`, all use cases | process, created lazily on first use, never re-created | `AppContainer` (`by lazy`) |
| `NativeBridge` / native receiver | process (Kotlin `object` + one native server) | `UxPlayEngine` |
| ViewModels | per screen, kept across configuration changes, cleared when the screen finishes | `ViewModelActivity` (`onRetainNonConfigurationInstance`) |
| `RenameViewModel` | per dialog | `RenameDialog` |

`ReceiverService`, `BootReceiver` and every Activity reach the graph through `(application as App).container`,
so the service and the UI share the same engine and the same `StatusStore`.

## Domain (`domain/`)

| Kind | Name | Purpose |
|---|---|---|
| Model | `ReceiverSettings` | Every user setting (name, resolution, codec, refresh, FPS cap, latency, overscan, audio, passthrough, HDR, frame-rate matching, PIN, stats). `engineProjection()` = fields that require a receiver restart |
| Model | `Resolution`, `CodecPreference`, `RefreshRate`, `FrameRateCap`, `LatencyMode`, `FrameRateMatching`, `AccessMode` | Setting value types |
| Model | `ReceiverStatus` (sealed), `ReceiverSnapshot`, `Sender`, `VideoFormat` | Live state |
| Model | `ReceiverConfig` | Fully resolved engine configuration (no AUTO values) |
| Model | `DisplayCapabilities` | What the TV can decode |
| Model | `ReceiverName` | Naming rules (sanitize to mDNS limits) |
| Interface (port) | `ReceiverEngine`, `EngineListener`, `EngineEvent`, `StartResult` | AirPlay protocol engine |
| Interface (port) | `SettingsRepository` | Settings persistence |
| Interface (port) | `DeviceInfo` | TV name, capabilities, name changes |
| Interface (port) | `ReceiverController` | Commands to the long-running receiver |
| State | `StatusStore` | Single source of truth for status (observable, thread-safe) |
| Use case | `GetSettings`, `ResolveReceiverName`, `BuildReceiverConfig` (+ `preview`), `StartReceiver`, `StopReceiver`, `TrackSession`, `RenameReceiver`, `UpdateSettings` | All behaviour |

## Data (`data/`), implementations of domain ports

| Class | Implements | Notes |
|---|---|---|
| `engine/UxPlayEngine` | `ReceiverEngine` | Wraps the JNI bridge; maps native events via `NativeEventParser` |
| `engine/NativeBridge` | n/a | JNI surface (`native_bridge.cpp`); `Sink` interface for callbacks |
| `engine/NativeEventParser` | n/a | Native string events → `EngineEvent` |
| `discovery/NsdServiceAdvertiser` | n/a (used by engine) | `_airplay._tcp` / `_raop._tcp` via NsdManager + multicast lock |
| `settings/SharedPrefsSettingsRepository` | `SettingsRepository` | Enums stored by name; in-memory cache |
| `settings/StableDeviceId` | n/a | Persistent AirPlay device id (random locally-administered MAC) |
| `device/AndroidDeviceInfo` | `DeviceInfo` | `Settings.Global.DEVICE_NAME` + observer; MediaCodec capability probe (cached) |

## Platform (`platform/`)

| Class | Role |
|---|---|
| `ReceiverService` | Foreground service hosting the engine; runs use cases; follows the TV name; notification with Disconnect |
| `ServiceReceiverController` | `ReceiverController` → service intents (`ensureRunning`, `restart`, `disconnect`) |
| `BootReceiver` | Starts the receiver on boot / app update |

## Presentation (`presentation/`): MVVM

Each screen has three parts: **Contract** (the Model: `UiState` + `Action` + `Effect`), **ViewModel**, and **View**.

| Screen | Model (Contract) | ViewModel | View |
|---|---|---|---|
| Home | `HomeUiState`, `HomeAction`, `HomeEffect`, `StatusTone`, `HomeRows` | `HomeViewModel` | `HomeActivity` |
| Advanced | `AdvancedUiState`, `AdvancedAction`, `AdvancedEffect` | `AdvancedViewModel` (setting catalog, ◀ ▶ adjust, 1.2 s debounced commit) | `AdvancedActivity` |
| Rename | `RenameUiState`, `RenameAction`, `RenameEffect` | `RenameViewModel` | `RenameDialog` |

Shared presentation pieces:

| Kind | Name | Purpose |
|---|---|---|
| Base | `core/ViewModel<State, Action, Effect>` | State + effects delivered on the main thread; `onAction`, `onCleared` |
| Base | `core/ViewModelActivity` | Creates/retains/binds/clears the ViewModel |
| Interface | `core/Strings` (+ `ResourceStrings`) | Text lookup so ViewModels don't hold a `Context` |
| Model | `core/RowModel` (Header, Navigation, Action, Choice, Toggle, Stepper), `core/RowAction` | List item display model + intents, shared by Home and Advanced |
| View | `components/SettingRowView` | Renders any `RowModel`; M3 Expressive focus (fill + pill shape morph + lift); ◀ ▶ handling |
| View | `components/SwitchIndicatorView` | M3-style switch |
| View | `components/RowListBinder` | Keyed in-place list rendering (never loses D-pad focus) |
| View | `components/SplitScreen` | Typed refs for `activity_split.xml` (tvOS layout) |
| View | `components/MessageBar` | Snackbar-style confirmations |
| Tokens | `components/Motion` | Spring-like timings; disabled when system animations are off |

## Data flow

1. **Status:** native event → `UxPlayEngine` → `NativeEventParser` → `EngineEvent` → `TrackSession` → `StatusStore`
   → `HomeViewModel` / `ReceiverService` → `HomeUiState` / notification.
2. **Settings:** `SettingRowView` ◀ ▶ → `RowAction.Adjusted` → `AdvancedViewModel` (draft, debounce) → `UpdateSettings`
   → `SettingsRepository` (+ `ReceiverController.restart()` if `engineProjection()` changed) → `ReceiverService`
   → `StartReceiver` → `BuildReceiverConfig` → `ReceiverEngine.start`.

## Design

- **Layout:** tvOS Settings: artwork, title, status and a description of the focused item on the left; a focusable list on the right.
- **Visual language:** Material 3 Expressive. Dark tonal colour tokens (`values/colors.xml`, `m3_*`), emphasized
  type scale (`values/styles.xml`), focus = primary fill + rounded-rect → pill morph + lift.
- **Accessibility:**
  - Status is always shown as text, never colour alone.
  - Every text/background pair is at least 4.5:1.
  - The focused element is always obvious.
  - Live regions announce status, PIN and messages.
  - Motion is off when system animations are off.
