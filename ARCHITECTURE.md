# Happy Rumble — Vibration Control Apps

Two companion apps that replicate the vibration mechanics of the Happy Rumble app,
with a phone controller and a Wear OS receiver.

---

## Architecture (v3 — Amplitude API + Waveform Animation)

```
┌─────────────────────────────────────┐     ┌────────────────────────────────────┐
│  Phone App (vibe-control)           │     │  Wear OS App (wear-vibe)           │
│  ┌────────────────────────────────┐ │     │  ┌──────────────────────────────┐  │
│  │ MainActivity                   │ │     │  │ VibrationForegroundService   │  │
│  │  6 tiles (2×3 grid)            │ │     │  │  ★ VibratorEngine (amplitude) │  │
│  │  WaveformView per tile         │ │     │  │  ★ Data/Message/Cap listeners │  │
│  │  Speed +/- (60dp)              │ │     │  │  ★ Heartbeat monitor (1s/2s)  │  │
│  │  STOP (red when active)        │ │     │  │  ★ Ping counter (anti-stale)   │  │
│  │  Active tile: rotating dots    │ │     │  │  ★ Auto-resume on reconnect    │  │
│  │  + waveform-tracing pulse      │ │     │  │  ★ WAKE_LOCK + notification    │  │
│  └──────────┬─────────────────────┘ │     │  └──────────────┬───────────────┘  │
│             │                       │     │                 │ broadcasts       │
│  ┌──────────▼─────────────────────┐ │     │  ┌──────────────▼───────────────┐  │
│  │ MainViewModel                  │ │     │  │ MainActivity (KIOSK MODE)    │  │
│  │  mode, level                   │ │     │  │  Mode name as primary status  │  │
│  │  Heartbeat (1s)               │ │     │  │  Speed label below            │  │
│  │  CapabilityClient.addListener  │ │     │  │  All touch/back blocked       │  │
│  └──────────┬─────────────────────┘ │     │  │  Crown long-press to exit     │  │
│             │                       │     │  └──────────────────────────────┘  │
│  ┌──────────▼─────────────────────┐ │     │                                    │
│  │ WearDataLayer                  │ │     │  ┌──────────────────────────────┐  │
│  │  DataClient + MessageClient    │ │     │  │ VibrationDataLayerService    │  │
│  │  CapabilityClient (watch det.) │ │     │  │  Wake-up only → starts FGS   │  │
│  └────────────────────────────────┘ │     │  └──────────────────────────────┘  │
└─────────────────────────────────────┘     │  ┌──────────────────────────────┐  │
                  │                         │  │ BootReceiver                 │  │
         Bluetooth/WiFi                      │  │  Auto-start on reboot        │  │
         Wear OS Data Layer                  │  └──────────────────────────────┘  │
         paths: /control, /ping              └────────────────────────────────────┘
         keys: wear_mode, wear_level,
                wear_intensity
```

## Critical Design Decisions

### 1. Both apps share the same applicationId

| App | applicationId |
|-----|---------------|
| Phone (vibe-control) | `com.example.vibecontrol` |
| Watch (wear-vibe) | `com.example.vibecontrol` |

Play Services routes data layer items by package name. Different IDs = data silently dropped.

### 2. Amplitude-based vibration engine

`VibrationEffect.createWaveform(timings, amplitudes, repeat)` with per-step amplitude
values (0-255). The motor transitions smoothly between amplitude levels rather than
binary on/off clicking. Burst mode uses legacy `createWaveform(timings, repeat)` without
amplitudes because very short taps (35-140ms) create artifacts with the amplitude API.

### 3. No command ordering — dedup guard only

Every command is processed immediately. `VibratorEngine.setModeVibration()` dedup guard:
```kotlin
if (mode == currentMode && level == currentLevel && intensity == currentIntensity && isActive) return
```

### 4. Heartbeat + ping counter for disconnect detection

Phone pings `/ping` every 1s with incrementing counter. Watch timeout 2s.
On disconnect → cancel vibration + "Waiting..." status.
On reconnect → auto-restore last mode/level.

### 5. Auto-start on watch

Three layers:
- **BootReceiver**: starts foreground service after reboot
- **VibrationDataLayerService**: Play Services wakes app on any incoming data → starts FGS
- **Phone sends `/launch`** on open → triggers wake-up

---

## Vibration Modes (6 total)

All modes normalized to ~1000ms cycle at slow speed (level 0), scaling proportionally.

| Mode | Value | API | Pattern | 1000ms @ L0 |
|------|-------|-----|---------|-------------|
| Constant | 0 | Amplitude | `[5000ms at 255]` looped | Continuous |
| Intermittent | 1 | Amplitude | 70/30 on/off duty | 700+300=1000ms |
| Ramp | 2 | Amplitude | 5 ascending amplitude steps | 5×200=1000ms |
| Burst | 3 | **Legacy** | 3 taps + pause, 70/30 duty | 5×140+300=1000ms |
| Wave | 4 | Amplitude | 20-step sine, starts at trough | 20×50=1000ms |
| Random | 5 | Amplitude | 30 random segments | ~1000ms avg |

Speed levels (0-3) scale all timings proportionally.

---

## Cancel Mechanism (4-stage)

1. `VibratorManager.cancel()` — cancel ALL vibrators (API 31+)
2. `Vibrator.cancel()` — cancel default vibrator
3. Zero-amplitude one-shot with `USAGE_ALARM` — flush alarm pipeline
4. Zero-amplitude one-shot default — flush default pipeline
5. Delete STOP DataItems from Data Layer to prevent stale re-delivery

---

## Phone UI

```
┌──────────────────────────────┐
│  Connected                   │
│  Constant — Slow             │
├──────────┬──────────┬─────────┤
│ CONSTANT │INTERMITT.│  RAMP   │
│ ──────── │ ────┬─── │ ─────── │  ← waveform charts
├──────────┼──────────┼─────────┤
│  WAVE    │  BURST   │ RANDOM* │  (*no chart)
├──────────┴──────────┴─────────┤
│   [-]    Speed: Slow    [+]   │
├──────────────────────────────┤
│           [ STOP ]           │  ← red when active
└──────────────────────────────┘
```

Tiles are arranged in a 3-column × 2-row grid: Constant, Intermittent, Ramp on the
first row, Wave, Burst, Random on the second. Random mode has no waveform chart
(because random patterns look like noise in a static chart).

Active tile: rotating white dots + pulsing center button that traces the actual
vibration waveform shape (3 pulses for Burst, sine for Wave, staircase for Ramp, etc.).
Tap active tile again = stop.

---

## Watch UI (Kiosk Mode)

```
┌──────────────────────────────┐
│  Wave                        │  ← mode name as primary status
│  Slow                        │  ← speed indicator
│                              │
│       [vibration icon]       │
│                              │
│  Long-press crown to exit    │
└──────────────────────────────┘
```

Status line shows: mode name when active, "Ready" when idle, "Waiting..." when disconnected.
All touch/back/swipe blocked. Only physical crown long-press (~2s) exits.

---

## Connection Detection

| Layer | Watch | Phone |
|-------|-------|-------|
| CapabilityClient listener | Detects phone presence | Detects watch capability |
| Heartbeat ping | Phone → watch every 1s with counter | Phone sends |
| Heartbeat timeout | 2s → disconnect + cancel | N/A |
| Wake-up | DataListenerService starts FGS | Phone sends /launch on open |
| Boot | BootReceiver starts FGS | — |

---

## Project Structure

### vibe-control/ (Phone)

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/vibecontrol/
│   ├── AppConstants.kt       # Shared constants (identical in both projects)
│   ├── WearDataLayer.kt      # DataClient + MessageClient + CapabilityClient
│   ├── MainViewModel.kt      # State + heartbeat + CapabilityClient listener
│   ├── MainActivity.kt       # 6-tile UI + waveform animations + controls
│   └── WaveformView.kt       # Mini waveform chart per tile (bitmap-cached)
└── res/
    ├── layout/activity_main.xml
    ├── drawable/ic_dots_circle.xml
    ├── drawable/tile_bg.xml
    └── drawable/tile_bg_pause_btn.xml
```

### wear-vibe/ (Watch)

```
app/src/main/
├── AndroidManifest.xml       # VIBRATE + WAKE_LOCK + FOREGROUND_*
├── java/com/example/vibecontrol/
│   ├── AppConstants.kt            # Shared constants (identical in both projects)
│   ├── VibratorEngine.kt          # 6 modes, amplitude API, 4-stage cancel
│   ├── VibrationForegroundService.kt # Listeners + heartbeat + auto-resume
│   ├── VibrationDataLayerService.kt  # Wake-up only (static flag check)
│   ├── BootReceiver.kt               # Auto-start on reboot
│   └── MainActivity.kt               # Kiosk mode display
└── res/
    ├── layout/activity_main.xml
    └── drawable/ic_vibration.xml
```

---

## Building & Running

### Setup
```bash
source .env   # ANDROID_HOME, JAVA_HOME, WEAR_SERIAL, PHONE_SERIAL
```

### Build
```bash
make build        # Both
make build-wear   # Watch only
make build-phone  # Phone only
```

### Install
```bash
make clear        # Uninstall → install → launch both
make clear-wear
make clear-phone
```

### Debug
```bash
make debug-wear       # VibeSvc|VibeAct|VibeWake
make debug-phone      # VibeWearDL
make debug-wear-all   # All buffers, broad filter
make debug-phone-all
```

### Log tags
| Tag | App | Component |
|-----|-----|-----------|
| `VibeWearDL` | Phone | WearDataLayer |
| `VibeSvc` | Watch | VibrationForegroundService |
| `VibeAct` | Watch | MainActivity |
| `VibeWake` | Watch | VibrationDataLayerService |
| `VibeBoot` | Watch | BootReceiver |
