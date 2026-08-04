# WatchVibe — Vibration Control Apps

Two companion apps that control a watch's vibration motor from your phone.

---

## Architecture (v3 — Amplitude API + Renewable Lease)

```
┌──────────────────────────────────────────┐     ┌────────────────────────────────────┐
│  Phone App (WatchVibeControl)            │     │  Wear OS App (WatchVibe)           │
│  ┌────────────────────────────────────┐  │     │  ┌──────────────────────────────┐  │
│  │ MainActivity                       │  │     │  │ VibrationForegroundService   │  │
│  │  6 tiles (3×2 grid)                │  │     │  │  ★ VibratorEngine (amplitude) │  │
│  │  WaveformView per tile             │  │     │  │  ★ Data/Message/Cap listeners │  │
│  │  Speed +/- (60dp)                  │  │     │  │  ★ Lease monitor (3s renew)   │  │
│  │  STOP (red when active)            │  │     │  │  ★ Dead-man's switch          │  │
│  │  Active tile: rotating dots        │  │     │  │  ★ WAKE_LOCK + notification    │  │
│  │  + waveform-tracing pulse          │  │     │  │  ★ Status from lease (unified) │  │
│  └──────────┬─────────────────────────┘  │     │  └──────────────┬───────────────┘  │
│             │                            │     │                 │ broadcasts       │
│  ┌──────────▼─────────────────────────┐  │     │  ┌──────────────▼───────────────┐  │
│  │ MainViewModel                      │  │     │  │ MainActivity (KIOSK MODE)    │  │
│  │  mode, level                       │  │     │  │  Mode name as primary status  │  │
│  │  Heartbeat (1s, dual-transport)    │  │     │  │  Speed label below            │  │
│  │  CapabilityClient.addListener      │  │     │  │  All touch/back blocked       │  │
│  └──────────┬─────────────────────────┘  │     │  │  Crown long-press to exit     │  │
│             │                            │     │  └──────────────────────────────┘  │
│  ┌──────────▼─────────────────────────┐  │     │                                    │
│  │ WearDataLayer                      │  │     │  ┌──────────────────────────────┐  │
│  │  DataClient + MessageClient        │  │     │  │ VibrationDataLayerService    │  │
│  │  CapabilityClient (watch det.)     │  │     │  │  Wake-up only → starts FGS   │  │
│  └────────────────────────────────────┘  │     │  └──────────────────────────────┘  │
└──────────────────────────────────────────┘     │  ┌──────────────────────────────┐  │
                  │                              │  │ BootReceiver                 │  │
         Bluetooth/WiFi                           │  │  Auto-start on reboot        │  │
         Wear OS Data Layer                       │  └──────────────────────────────┘  │
         paths: /control, /ping                   └────────────────────────────────────┘
         keys: wear_mode, wear_level,
                wear_intensity
```

## Critical Design Decisions

### 1. Both apps share the same applicationId

| App | applicationId |
|-----|---------------|
| Phone (WatchVibeControl) | `com.example.vibecontrol` |
| Watch (WatchVibe) | `com.example.vibecontrol` |

Play Services routes data layer items by package name. Different IDs = data silently dropped.

### 2. Amplitude-based vibration engine

`VibrationEffect.createWaveform(timings, amplitudes, repeat)` with per-step amplitude
values (0-255). The motor transitions smoothly between amplitude levels rather than
binary on/off clicking. Burst mode uses a 50ms minimum tap duration to prevent
motor artifacts at very short timings, keeping its base cycle at 1000ms.

### 3. No command ordering — dedup guard only

Every command is processed immediately. `VibratorEngine.setModeVibration()` dedup guard:
```kotlin
if (mode == currentMode && level == currentLevel && intensity == currentIntensity && isActive) return
```

### 4. Dual-lease model (dead-man's switch)

Two independent renewable leases replace a single timeout:

| Lease | Extended by | Zeroed by | Drives |
|-------|------------|-----------|--------|
| **connectionLease** | pings, any control command, capability reconnect | disconnect, natural expiry | UI status, notification text |
| **vibrationLease** | pings, non-STOP commands | STOP/PAUSE commands, disconnect, connection expiry | vibration cancellation, auto-resume |

Phone pings `/ping` every 1s via both DataClient and MessageClient (dual-transport
redundancy). Each ping extends both leases by `VIBRATION_LEASE_MS` (3s).

Key property: **STOP zeroes vibrationLease but NOT connectionLease**. The UI
shows "Ready" (connected, not vibrating) instead of "Waiting..." (disconnected).

**Two-layer disconnect detection:**
1. **CapabilityClient listener** → fast-path: zeroes both leases and cancels
   vibration immediately when the phone node disappears. Sub-second reaction.
2. **Connection lease expiry** → safety net: if the capability listener doesn't
   fire (silent disconnect), the lease expires in 3s, both leases zeroed,
   vibration cancelled. This is **fails-safe by design** — if the heartbeat
   mechanism breaks entirely, vibration stops within 3 seconds.

**Auto-resume**: when the vibration lease revives from expired (pings resume),
the watch restarts the last active mode — but only if the disconnect was
shorter than `COMMAND_TTL_MS` (30s). Longer disconnects suppress auto-resume.

The UI, notification, and vibration control all derive from the same lease
values — display and function can never diverge.

### 5. Command TTL and deduplication

Every control command carries a `System.currentTimeMillis()` timestamp. The
watch discards any command older than `COMMAND_TTL_MS` (30s) or older than
the last processed command (out-of-order delivery).

This prevents two problems:
- **Stale re-delivery**: a cached DataItem from a long-ago tap doesn't
  suddenly restart vibration on reconnect.
- **Rapid cycling**: if the user mashed buttons while disconnected, only
  the freshest command survives (DataItem overwrites at the same path;
  Messages aren't queued). Out-of-order arrival is caught by timestamp
  comparison.

DataItem queue depth is naturally 1 per path (`putDataItem` overwrites).
Messages have no queue — they fail fast if the node is unreachable.

### 6. Auto-start on watch

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
| Burst | 3 | Amplitude | 3 taps + pause, 50ms floor | 5×150+250=1000ms |
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
│  WatchVibe                   │  ← app title
│  Connected                   │  ← connection status
│  Constant — Slow             │  ← mode + speed
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
| CapabilityClient listener | Detects phone presence → immediate cancel | Detects watch capability |
| Heartbeat ping (dual) | Phone → watch every 1s via DataClient + MessageClient | Phone sends |
| Connection lease expiry (safety net) | 3s after last ping → both leases zeroed, cancel | N/A |
| Vibration lease | Extended by pings; zeroed by STOP; drives auto-resume | — |
| UI connection status | Derived from connectionLease (`connectionLeaseExpiry > now`) | — |
| Command TTL | Discards commands older than 30s or out-of-order | Commands carry timestamps |
| Wake-up | DataListenerService starts FGS | Phone sends /launch on open |
| Boot | BootReceiver starts FGS | — |

---

## Project Structure

### vibe-control/ (WatchVibeControl — Phone)

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

### wear-vibe/ (WatchVibe — Watch)

```
app/src/main/
├── AndroidManifest.xml       # VIBRATE + WAKE_LOCK + FOREGROUND_*
├── java/com/example/vibecontrol/
│   ├── AppConstants.kt            # Shared constants (identical in both projects)
│   ├── VibratorEngine.kt          # 6 modes, amplitude API, 4-stage cancel
│   ├── VibrationForegroundService.kt # Listeners + lease monitor + dead-man's switch
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
