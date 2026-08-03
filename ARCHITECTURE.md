# Happy Rumble — Vibration Control App

Single APK that adapts to phone or Wear OS at runtime. On a phone it shows two tabs
(remote watch control + local phone vibration). On a watch it shows a snap-scroll
tile UI with direct local control and a fixed overlay for speed/stop.

---

## Architecture (v3 — Merged Single App)

```
┌─────────────────────────────────────────────────────────────────────┐
│  Single APK (com.example.vibecontrol)                               │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ MainActivity (router)                                         │   │
│  │   DeviceType.init() → isWatch?                                │   │
│  │     phone → PhoneActivity      watch → WatchActivity          │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────┐    ┌──────────────────────────────────┐    │
│  │ PhoneActivity        │    │ WatchActivity                     │    │
│  │  ViewPager2 + tabs   │    │  RecyclerView + LinearSnapHelper  │    │
│  │  ┌─────────────────┐ │    │  ┌────────────────────────────┐  │    │
│  │  │ Tab "Watch"      │ │    │  │ [Constant tile]  fullscr   │  │    │
│  │  │  TilesGridFrag.  │ │    │  │ [Intermittent]   fullscr   │  │    │
│  │  │  → REMOTE target │ │    │  │ [Ramp]           fullscr   │  │    │
│  │  ├─────────────────┤ │    │  │ [Burst]          fullscr   │  │    │
│  │  │ Tab "Phone"      │ │    │  │ [Wave]           fullscr   │  │    │
│  │  │  TilesGridFrag.  │ │    │  │ [Random]         fullscr   │  │    │
│  │  │  → LOCAL target  │ │    │  └────────────────────────────┘  │    │
│  │  └─────────────────┘ │    │  ┌────────────────────────────┐  │    │
│  └──────────────────────┘    │  │ [- Speed +] [STOP]  overlay │  │    │
│                              │  └────────────────────────────┘  │    │
│  ┌──────────────────────┐    └──────────────────────────────────┘    │
│  │ MainViewModel         │                                           │
│  │  targetDevice enum:   │    ┌──────────────────────────────────┐    │
│  │    REMOTE → WearDL    │    │ VibrationForegroundService        │    │
│  │    LOCAL  → VibEngine │    │  DataClient.addListener()         │    │
│  └──────────────────────┘    │  MessageClient.addListener()       │    │
│                              │  CapabilityClient (disconnect)     │    │
│  ┌──────────────────────┐    │  Partial WAKE_LOCK                │    │
│  │ WearDataLayer          │    │  Persistent notification          │    │
│  │  DataClient.putData   │    └──────────────────────────────────┘    │
│  │  MessageClient.send   │                                           │
│  │  + commandId seq.     │    ┌──────────────────────────────────┐    │
│  └──────────────────────┘    │ VibratorEngine                     │    │
│                              │  6 modes + intensity scaling       │    │
│  ┌──────────────────────┐    │  USAGE_ALARM bypasses ambient      │    │
│  │ TileAnimator (shared) │    │  Multi-layer cancel() flush       │    │
│  │  dot rotation         │    └──────────────────────────────────┘    │
│  │  pulse backdrop       │                                           │
│  │  center scale         │                                           │
│  └──────────────────────┘                                           │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Runtime Device Detection

`DeviceType.init(context)` checks at startup:
- Primary: `UiModeManager.currentModeType == UI_MODE_TYPE_WATCH`
- Fallback: `PackageManager.hasSystemFeature(FEATURE_WATCH)`

`MainActivity` routes to `PhoneActivity` or `WatchActivity` based on the result, then finishes.

---

## Phone UI

`PhoneActivity` is a `ViewPager2` with two tabs backed by the **same** `TilesGridFragment`:

| Tab | TargetDevice | Execution |
|-----|-------------|-----------|
| "Watch" (default) | `REMOTE` | Commands sent to watch via `WearDataLayer` |
| "Phone" | `LOCAL` | Phone's own vibrator motor via `VibratorEngine` |

The `PhoneActivity.onPageSelected` callback updates `MainViewModel.targetDevice`.
Both tabs share one `MainViewModel` instance; tab switching stops the current
vibration and re-routes subsequent taps.

The tile grid is a 2×3 layout (Constant, Intermittent, Ramp, Burst, Wave, Random)
with speed +/- and STOP controls below — extracted into `fragment_tile_grid.xml`.

---

## Watch UI

`WatchActivity` is a `RecyclerView` with `LinearSnapHelper` — vertical paginated scrolling.
Each tile (`item_tile_watch_full.xml`) fills nearly the full screen.

- **Snap scroll**: one tile at a time, always aligned
- **Overlay**: speed controls (+/-) and STOP button are a fixed `LinearLayout` at the bottom
- **Normal touch**: no kiosk mode — tiles are clickable, scroll is enabled
- **Local control**: tile taps go directly to `VibratorEngine` via `MainViewModel(targetDevice=LOCAL)`
- **Remote capability**: `VibrationForegroundService` still runs in the background to receive commands from a paired phone

---

## Shared Components

### MainViewModel
Central state for both phone and watch. Key addition over v2: `TargetDevice` enum
(`REMOTE` / `LOCAL`) that controls whether `applyVibration()` routes to `WearDataLayer`
or `VibratorEngine`. All StateFlows (`mode`, `level`, `intensity`, `isVibrating`,
`wearConnected`, `statusText`, `targetDevice`) are shared.

### TileAnimator
Extracted animation logic used by both `TilesGridFragment` (phone) and
`TileScrollerAdapter` (watch):
- Dot rotation: 2s infinite `ObjectAnimator` with `LinearInterpolator`
- Pulse backdrop + center: `ValueAnimator` (0.3→1.0→0.3) with `AccelerateDecelerateInterpolator`
- `TileViews` data class standardizes the view references both sides need
- `cancelAll()` for safe cleanup

### VibratorEngine
Unchanged from v2 wear-vibe — the complete 6-mode engine with:
- All modes: CONSTANT, INTERMITTENT, RAMP, BURST, WAVE, RANDOM
- Intensity scaling via `scalePower()`
- Multi-layer cancel (VibratorManager, Vibrator, ALARM flush, default flush)
- Thread-safe (`@Synchronized`) dedup guard
- `vibratePulse()` for custom one-shot patterns

### WearDataLayer
Unchanged from v2 vibe-control — sends commands via both `DataClient.putDataItem()`
and `MessageClient.sendMessage()` with monotonic `commandId` sequencing.

### VibrationForegroundService
Copied from v2 wear-vibe — runs on the watch to listen for remote phone commands.
Owns its own `VibratorEngine` instance independent from the one in `MainViewModel`.
Handles disconnect detection via `CapabilityClient`.

---

## Vibration Patterns

| Mode | Value | Pattern | Feel |
|------|-------|---------|------|
| STOP | -2 | cancel() | Silent |
| PAUSE | -3 | cancel() | Silent |
| CONSTANT | 0 | [1ms, 5000ms] looped | Continuous |
| INTERMITTENT | 1 | Level-dependent pulse | Pulsed |
| RAMP | 2 | Escalating steps (6 steps, gap varies) | Building |
| BURST | 3 | Triple-tap throb [30,30,30,30,30,pause] | Throbbing |
| WAVE | 4 | Rising-falling envelope | Wavy |
| RANDOM | 5 | Random on/gap durations | Chaotic |

Levels (0-3): Slow, Medium, Fast, Very Fast — controls gap/pause timing.

---

## Critical Design Decisions (preserved from v2)

1. **Same applicationId** (`com.example.vibecontrol`) — required for Wear Data Layer routing
2. **Foreground Service** on watch — only way to keep listeners alive on Wear OS 5
3. **Dual transport**: DataItem (persistent) + Message (real-time) with dedup
4. **Monotonic commandId** — prevents stale delivery races
5. **Thread-safe VibratorEngine** — `@Synchronized` on key methods
6. **USAGE_ALARM** — bypasses ambient-mode restrictions on Wear OS
7. **Multi-layer cancel** — VibratorManager + Vibrator + ALARM flush + default flush
8. **FOREGROUND_SERVICE_TYPE_SPECIAL_USE** + `<property>` — required on API 34+
9. **RECEIVER_NOT_EXPORTED** — required on API 34+

---

## Data Flow

### Phone → Watch (REMOTE)
1. User taps tile on phone "Watch" tab
2. `MainViewModel.applyVibration()` → `WearDataLayer.sendControl(mode, level, intensity)`
3. Increments `commandCounter`, sends via DataItem + Message
4. Google Play Services transports over Bluetooth
5. `VibrationForegroundService` listeners receive → acceptCommand(cmdId) → vibrate

### Phone local (LOCAL)
1. User taps tile on phone "Phone" tab
2. `MainViewModel.applyVibration()` → `VibratorEngine.setModeVibration(mode, level, intensity)`
3. Phone's vibrator motor runs the pattern

### Watch local
1. User taps tile in snap-scroll RecyclerView
2. `MainViewModel.applyVibration()` → `VibratorEngine.setModeVibration(mode, level, intensity)`
3. Watch vibrator motor runs the pattern
4. Foreground service also handles incoming remote commands from phone

---

## Project Structure

```
happy-rumble/
├── settings.gradle.kts          # include(":app")
├── build.gradle.kts             # AGP 8.5.0, Kotlin 1.9.24
├── gradle.properties
├── gradle/
├── gradlew / gradlew.bat
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/com/example/vibecontrol/
            ├── DeviceType.kt
            ├── VibratorEngine.kt
            ├── WearDataLayer.kt
            ├── MainActivity.kt            # Router
            ├── MainViewModel.kt
            ├── VibrationForegroundService.kt
            ├── phone/
            │   ├── PhoneActivity.kt
            │   └── TilesGridFragment.kt
            ├── watch/
            │   ├── WatchActivity.kt
            │   └── TileScrollerAdapter.kt
            └── ui/
                └── TileAnimator.kt
```

---

## Building & Running

```bash
# Build
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk

# The same APK works on both phone and watch.
# DeviceType detection routes to the correct UI at startup.
```
