# WatchVibe — Vibration Control Apps

Two companion apps that control a watch's vibration motor from your phone.

---

## Architecture (v6 — Isolated Runtime, Statistics, and Authentication)

```
┌──────────────────────────────────────────┐     ┌────────────────────────────────────┐
│  Phone App (WatchVibeControl)            │     │  Wear OS App (WatchVibe)           │
│  ┌────────────────────────────────────┐  │     │  ┌──────────────────────────────┐  │
│  │ MainActivity                       │  │     │  │ MainActivity (KIOSK MODE)    │  │
│  │  6 tiles (3×2 grid)                │  │     │  │  ★ VibratorEngine (amplitude) │  │
│  │  WaveformView per tile             │  │     │  │  ★ Data/Message/Cap listeners │  │
│  │  Speed +/- (60dp)                  │  │     │  │  ★ Lease monitor (3s renew)   │  │
│  │  STOP (red when active)            │  │     │  │  ★ Dead-man's switch          │  │
│  │  Active tile: rotating dots        │  │     │  │  ★ Battery monitor            │  │
│  │  + waveform-tracing pulse          │  │     │  │  All touch/back blocked       │  │
│  └──────────┬─────────────────────────┘  │     │  │  onUserLeaveHint → emergency   │  │
│             │                            │     │  │  No foreground service         │  │
│  ┌──────────▼─────────────────────────┐  │     │  └──────────────────────────────┘  │
│  │ MainViewModel                      │  │     │                                    │
│  │  mode, level (SavedStateHandle)    │  │     │  ┌──────────────────────────────┐  │
│  │  Heartbeat (idle foreground only) │  │     │  │ VibrationDataLayerService    │  │
│  │  CapabilityClient.addListener      │  │     │  │  Wake-up only → launches Act. │  │
│  └──────────┬─────────────────────────┘  │     │  └──────────────────────────────┘  │
│             │                            │     │                                    │
│  ┌──────────▼─────────────────────────┐  │     │                                    │
│  │ WearDataLayer                      │  │     │                                    │
│  │  DataClient + MessageClient        │  │     │                                    │
│  │  CapabilityClient (watch det.)     │  │     │                                    │
│  │  ★ Incoming message listener       │  │     │                                    │
│  │  ★ Battery request on connect       │  │     │                                    │
│  └────────────────────────────────────┘  │     │                                    │
└──────────────────────────────────────────┘     └────────────────────────────────────┘
                  │
         Bluetooth/WiFi
         Wear OS Data Layer
         paths: /control, /ping,
                /launch, /minimize
         ← /crown_exit (watch→phone)
         → /battery_request (phone→watch)
         ← /battery (watch→phone)
         ← /alive (watch→phone, periodic)
         keys: wear_mode, wear_level,
                wear_intensity, battery_level
```

## Critical Design Decisions

### 1. Both apps share the same applicationId

| App | applicationId |
|-----|---------------|
| Phone (WatchVibeControl) | `com.yieldinghartebeest13.watchvibe` |
| Watch (WatchVibe) | `com.yieldinghartebeest13.watchvibe` |

Play Services requires matching package names and signing certificates on the two
apps. Build/sign both together; different identities prevent Data Layer delivery.

### 2. Amplitude-based vibration engine

`VibrationEffect.createWaveform(timings, amplitudes, repeat)` with per-step amplitude
values (0-255). The motor transitions smoothly between amplitude levels rather than
binary on/off clicking. Burst mode uses a 50ms minimum tap duration to prevent
motor artifacts at very short timings, keeping its base cycle at 1000ms.

### 3. Ordered controls — dedup plus forced reassert

The phone stamps controls before IO dispatch, with a process-wide monotonically
increasing timestamp even for same-millisecond taps. The watch rejects stale or
out-of-order controls before applying them; active commands also need true
foreground visibility. `VibratorEngine.setModeVibration()` then uses a dedup
guard for ordinary repeated callbacks:
```kotlin
if (mode == currentMode && level == currentLevel && intensity == currentIntensity && isActive) return
```

However, silent vibrator stoppage on Wear OS/HAL can leave the app believing the
current mode is still active. To recover from that, `VibratorEngine` now also
tracks the timestamp of the last actuator command and exposes a forced
same-mode restart path:
- `reassertActiveVibration()` — resend the current mode/level/intensity even if unchanged
- `shouldReassertActiveVibration(...)` — rate-limit reassert attempts

This keeps normal command handling deduped while still allowing recovery from a
likely actuator stop.

Reassertion is **cycle-aware**:
- for fixed patterns, the engine replays immediately only when already near a
  loop boundary; otherwise it schedules the restart for the next boundary so it
  does not splice a fresh waveform into the middle of the current cycle
- `shouldReassertActiveVibration(...)` only returns true when the current
  actuator command looks stale **and** the next safe restart boundary is near
- RANDOM mode carries continuity across reasserts by seeding the new section
  from the prior tail cadence/amplitude, so a forced restart does not feel like
  a hard discontinuity

### 4. Dual-lease model (dead-man's switch)

`WatchSessionState` owns desired vibration separately from the actuator state.
Activity/session/UI effects are serialized on `Dispatchers.Main.immediate`;
Play Services Tasks suspend without blocking. Lease/recovery clocks use
`SystemClock.elapsedRealtime()` rather than wall time.

Two independent renewable leases replace a single timeout:

| Lease | Extended by | Zeroed by | Drives |
|-------|------------|-----------|--------|
| **connectionLease** | fresh pings, accepted controls | disconnect, foreground loss, natural expiry | UI status |
| **vibrationLease** | fresh pings while a mode is desired, non-STOP commands | STOP/PAUSE, disconnect, foreground loss, connection expiry | cancellation, bounded recovery |

Phone pings `/ping` every 1s via both DataClient and MessageClient (dual-transport
redundancy). Each ping extends both leases by `VIBRATION_LEASE_MS` (3s).

Key property: **STOP zeroes vibrationLease but NOT connectionLease**. The UI
shows "Ready" (connected, not vibrating) instead of "Waiting..." (disconnected).

**Two-layer disconnect detection:**
1. **CapabilityClient listener** → fast-path: zeroes both leases and cancels
   vibration immediately when the phone node disappears. Sub-second reaction.
2. **Connection lease expiry** → safety net: if the capability listener doesn't
   fire (silent disconnect), the lease expires in 3s and the actuator stops on
   the next one-second monitor tick (or arriving ping). Capability/focus loss
   cancels immediately. This is **fails-safe by design**.

**Auto-resume**: fresh pings can revive the separately stored desired mode if
actual monotonic interruption age is strictly less than `COMMAND_TTL_MS` (30s),
the UI is foreground, and the emergency surface is available. STOP, confirmed
dismissal, a new phone session, or the recovery deadline clears desired mode.

The UI, notification, and vibration control are all bounded by the same lease
model, but actual vibration is additionally constrained by the **foreground
gate** and the availability of the emergency notification surface. If the UI is
not truly foreground, or if the emergency surface cannot be shown, the watch
cancels vibration instead of continuing unattended.

### 5. Command TTL and deduplication

Every control command carries a wall-clock-based timestamp, strictly increasing
within the phone process and assigned before transport scheduling. Cleanup STOP
also enters the sender synchronously before newer UI intents can be issued.
The watch discards controls older than `COMMAND_TTL_MS` (30s), or at/below the
current Activity session's command watermark (duplicate/out-of-order delivery).

This prevents two problems:
- **Stale re-delivery**: a cached DataItem from a long-ago tap doesn't
  suddenly restart vibration on reconnect.
- **Rapid cycling**: if the user mashed buttons while disconnected, only
  the freshest command survives (DataItem overwrites at the same path;
  Messages aren't queued). Out-of-order arrival is caught by timestamp
  comparison.

DataItem queue depth is naturally 1 per path (`putDataItem` overwrites).
Messages have no queue — they fail fast if the node is unreachable.

### 6. Session ID (cross-session isolation)

Every ping/control carries a `sessionId` created once by the application-scoped
`WearDataLayer`. The ViewModel and service instances share this identity and an
atomic counter. Control Messages append ID as a fifth field; the updated watch
still accepts old two/three/four-field commands. Update both apps together.

A new identified session clears old desired mode/recovery, cancels the actuator,
and resets counter/command watermark. The old ID is retired so late deliveries
cannot switch back. First identification preserves a just-received legacy command.
STOP/emergency exit retain the watermark to suppress cached active redelivery.

This also fixes the phone close/reopen problem: without sessionId, the
resetting `pingCounter` caused all new-session pings to be silently dropped.

### 7. Phone state persistence

`MainViewModel` uses `SavedStateHandle` to persist validated mode, level, and
intensity selections. Construction never starts vibration. A restored active
selection is applied only from authenticated `onForeground()`, after any PIN
gate. Foreground-service start/promotion rejection stops the session rather than
leaving an active UI without a heartbeat.

The watch `MainActivity` uses `launchMode="singleTask"` to avoid stale hidden
instances when Play Services or the phone relaunches the watch UI. The watch
app still has **no launcher entry** in disguise mode; `singleTask` is purely a
task/lifecycle safety measure so wake-ups reuse the existing task instead of
leaving an older hidden instance behind.

### 8. Single-owner heartbeat policy

`PhoneHeartbeat` serializes ownership handoff and cancellation. The service is
the only active-mode owner, even with the phone UI foreground; the ViewModel
only sends idle foreground status pings. DataClient/MessageClient operations
have independent one-second deadlines and propagate parent cancellation.

`PingForegroundService` renews a bounded 60-second partial wake lock every
30 seconds in a separate maintenance coroutine. It is non-sticky and rejects
delayed starts after STOP; Android cannot restart an orphan active heartbeat.

The heartbeat only runs in the background when protecting active vibration:

| App state | Mode active? | Heartbeat | Reason |
|-----------|-------------|-----------|--------|
| Foreground | Yes | ✅ Running | UI needs live status |
| Foreground | No (STOP) | ✅ Running | UI shows "Ready" vs "Waiting..." |
| Background | Yes | ✅ Running | Protects watch from disconnect |
| Background | No (STOP) | ❌ Stopped | Battery — nothing to protect |
| Explicitly closed | Any | ❌ Stopped | Process killed or finishing |

`onBackground()` stops idle status pings but leaves service-owned active pings
running. STOP/PAUSE releases ownership and stops the service. Phone Activity
configuration recreation does not send STOP. ViewModel cleanup sends its final
STOP using a bounded independent operation because its own scope is cancelled.

### 9. Auto-start on watch

Two layers:
- **VibrationDataLayerService**: Play Services wakes the process on `/launch` or
  active `/control` and starts `MainActivity`
- **Phone sends `/launch`** on open → triggers wake-up

Important details in the current design:
- active `/control` commands are forwarded as intent extras and may be **deferred**
  until the watch UI is truly foreground
- `/control` STOP/PAUSE no longer wakes the watch UI
- relaunch uses `NEW_TASK | SINGLE_TOP | CLEAR_TOP` and the Activity is
  `singleTask`, so wake-ups reuse the existing watch task deterministically

No boot receiver or foreground service — vibration is still activity-scoped,
but now additionally hard-gated on true foreground visibility.

### 10. Auto-minimize on background

When the phone app goes to the background and no vibration is active, it sends a
`/minimize` message to the watch, which then returns to the watch face. This way
the user doesn't have to manually long-press the crown to dismiss the watch UI.

**Two trigger paths:**

| Trigger | Delay | Cancel condition |
|---------|-------|------------------|
| Phone `onBackground()` + mode is STOP/PAUSE | Immediate | Foreground return or a new active command cancels an in-flight send |
| Connection lease expiry + not vibrating | 30 seconds | Ping arrives or vibration command received |

The 30s grace period on lease expiry prevents brief Bluetooth dropouts from
needlessly closing the watch UI, while the immediate minimize on explicit
backgrounding lets the user dismiss the watch instantly by switching apps.

When vibration is active, the watch NEVER minimizes — the user explicitly
started vibration and may want to monitor status on their wrist.

### 11. Emergency stop + foreground gate (watch → phone)

There are now **two** stop mechanisms on the watch:

1. **Foreground gate** — if the UI is not truly foreground, vibration is stopped
   immediately.
2. **Emergency stop** — explicit dismissal or notification STOP action also sends
   `/crown_exit` to the phone.

#### Foreground gate
`MainActivity` computes true foreground as:
- started
- resumed
- window-focused
- not finishing/destroyed

If that condition becomes false:
- both leases are cleared
- vibration is cancelled
- `/alive` stops
- ping-based lease extension is ignored
- control listeners remain registered until destruction so STOP/PAUSE can
  clear recovery even during a short stopped interval
- desired mode remains recoverable briefly, but `/crown_exit` is not sent yet
- sustained loss is confirmed after two seconds; returning focus still needs a
  renewed lease before vibration can resume

This closes the previous bug where a hidden Activity could keep working after
`onStop()` because its runtime had been started in `onCreate()` and only mostly
stopped in `onDestroy()`.

#### Explicit emergency stop
`onUserLeaveHint()` still detects likely user dismissal, but it no longer
cancels vibration immediately. Instead:
1. `onUserLeaveHint()` marks exit pending
2. `scheduleEmergencyStop()` waits `EMERGENCY_STOP_GRACE_MS` (2s)
3. `confirmEmergencyStop()` checks `isUiForeground()`
   - foreground restored → transient cover/keyguard, abort
   - still not foreground → `performEmergencyStop(...)`
4. `performEmergencyStop(...)` clears desired recovery/leases, cancels vibration,
   updates UI, and sends `/crown_exit` at most once per ended session

Foreground loss uses this same confirmation instead of bypassing it. A bounded
application-context sender survives immediate Activity destruction.

#### Notification emergency surface
While vibration is active **and** the UI is truly foreground, the watch shows an
ongoing low-priority notification with:
- tap → reopen watch UI
- STOP action → explicit emergency stop

The emergency surface is also a **start gate** for active commands:
- if `POST_NOTIFICATIONS` is missing, the watch keeps the pending wake command,
  requests permission, and only starts vibration after the grant callback
- transient focus loss caused by that permission prompt is ignored so the watch
  does not silently self-stop before the pending command can be retried
- if permission is denied, or notifications/channel delivery is disabled, the
  watch does not start vibration and instead shows:
  - `Notification access required`
  - `Enable notifications to start vibration.`

If the notification becomes unavailable mid-session (permission revoked,
notifications/channel disabled, or post failure), the watch performs an
emergency stop first and then prompts for permission or notification settings as
needed. This guarantees there is always an emergency exit surface during an
active session.

**Phone message listener:**
`WearDataLayer` registers a `MessageClient.OnMessageReceivedListener` that
watches for `/crown_exit` messages from the watch. Started in
`MainViewModel.startConnectionMonitor()`, stopped in `stopConnectionMonitor()`.

**`moveTaskToBack(true)` re-entrancy (fixed):**
When the phone sends `/minimize`, the watch's `MinimizeReceiver` calls
`moveTaskToBack(true)`, which itself triggers `onUserLeaveHint()`. A
`minimizeInProgress` guard flag suppresses the redundant emergency stop.
Without it this would fire a wasteful `/crown_exit` round-trip to an
already-minimized phone.

### 12. Watch battery monitoring (watch → phone)

The watch monitors its battery level and sends it to the phone. The phone
displays a battery icon with percentage next to the connection status.

**Push (watch-initiated):**
- On service start: synchronous read via `BatteryManager.getIntProperty()`
- On level change: `BroadcastReceiver` for `ACTION_BATTERY_CHANGED`
- On phone reconnect: capability listener triggers re-send
- Sent via `MessageClient` on path `/battery`

**Pull (phone-initiated):**
- Phone calls `requestBattery()` on every `startConnectionMonitor()` (app open)
- Sends `/battery_request` message to watch
- Watch replies with current `lastBatteryLevel` on `/battery`
- Eliminates race conditions: phone always gets data regardless of startup order

**Phone UI:**
- Battery row always visible (never hidden)
- `watchBatteryPending` flag: `true` until first reply received
- Pending state: dim gray icon + "--%" placeholder
- Live state: colored icon + "N%" (red ≤15%, orange ≤30%, neutral >30%)
- `MainViewModel.watchBatteryLevel: StateFlow<Int>` exposed to UI

### 13. Session stats and history

The phone app records every vibration run and provides aggregated stats
on a dedicated stats screen.

**Recording:**
- `SessionRecorder` closes a segment on each mode/speed change, STOP, or cleanup
- Duration uses monotonic elapsed time; `started_at` stores the actual wall-clock start
- Identical commands do not split segments; runs shorter than 500ms are discarded
- Finite independent IO writes survive ViewModel cleanup, using scoped DB handles
- `StatsDb` explicitly implements `Closeable` for older supported Android versions
- Schema v2 converts v1's recorded end timestamps to approximate starts in place;
  IDs, modes, durations, and history rows are retained (no Room dependency)

**Session merging (StatsDb.mergedQuery):**
- Consecutive runs ≤ 15 minutes apart are merged into one session
- Pauses ≤ 5 minutes within a session count toward total duration
- Each merged session tracks its dominant mode (most active time)
- Mode breakdown aggregates raw run durations, not dominant-per-session
  (preserves detail when user switches modes mid-session)

**Stats screen (StatsActivity):**
- Accessed via bar-chart icon (ic_stats.xml) in the main screen header
- Week / Month / Year tabs with three summary cards (sessions, time, avg)
- Colored mode breakdown bars proportional to usage
- Recent sessions list with mode-colored dots, run counts, and durations
- Owns a read-only `StatsViewModel`, not the transport-owning `MainViewModel`;
  closing this screen cannot issue STOP, replace listeners, or alter heartbeat ownership
- Refreshes a single week/month/year snapshot on resume; queries run on IO and
  the helper closes on the querying thread
- Uses `suppressMinimize` to avoid watch dismissal on this internal transition
- UI collectors are lifecycle-gated; stealth statistics also use `FLAG_SECURE`

### 14. No foreground service — vibration is activity-scoped and foreground-gated

`VibrationForegroundService` has been removed. All vibration control,
listeners, lease management, silent-stop recovery, emergency-surface checks,
and battery monitoring now live directly in `MainActivity` / `VibratorEngine`.

Vibration is allowed only when **all** of these are true:
1. the watch UI is truly foreground (`started + resumed + window-focused`)
2. the vibration lease is current
3. the emergency notification surface is available

If any of those fail, the watch cancels vibration.

**Rationale:**
1. Wear OS can background an Activity without destroying it; `onUserLeaveHint()`
   alone is not a sufficient safety boundary.
2. The foreground gate makes hidden-state vibration impossible.
3. The ongoing notification provides a last-resort STOP path while vibration is active.
4. Eliminating the foreground service still keeps the runtime simple: one
   Activity owns the session, and it becomes inert immediately when not truly foreground.

### 15. Reversed heartbeat — /alive (watch → phone)

The watch sends an `/alive` message to the phone every 2 seconds only while
`MainActivity` is truly foreground and ready for commands. The phone tracks the
timestamp of the last received `/alive` and considers the watch "connected" only
if a signal arrived within the last 5 seconds (`ALIVE_TIMEOUT_MS`).

A periodic checker in `MainViewModel.startConnectionMonitor()` flips
`watchConnected` to `false` if no `/alive` arrives within the timeout.

This now means:
- **First launch**: watch foregrounds → phone sees `/alive` within 2s
- **Minimize/reopen**: watch stops `/alive` while hidden and resumes when visible again
- **Watch dismissed / focus lost**: `/alive` stops and phone detects it within 5s
- **Connection drop**: signals stop arriving → phone detects within 5s

The `CapabilityClient` listener is still used for **fast disconnect
detection** — when the phone node disappears from the capability set,
`watchConnected` flips to `false` immediately (sub-second), faster than
the 5-second alive timeout.

### 16. Wake-up command forwarding

`VibrationDataLayerService` extracts control command data (mode, level,
intensity, timestamp) from incoming `/control` DataItems and Messages and
passes them as Intent extras to `MainActivity`.

Current behavior:
- active `/control` commands may wake the watch UI
- duplicate timestamped active-control wake deliveries are deduped for a short
  window so the same command does not relaunch the watch twice via DataItem +
  Message delivery
- if `MainActivity` is already truly foreground, the service skips the wake-up
  relaunch entirely and lets the existing Activity handle the live command
- forwarded commands are **deferred until true foreground** if the Activity
  instance exists but is not yet safe to vibrate
- STOP/PAUSE commands do **not** wake the UI anymore
- relaunch uses `CLEAR_TOP` so an existing watch task is reused instead of
  leaving a hidden stale instance behind

Additionally, when the phone receives the first `/alive` after being
disconnected, it re-sends the active vibration command — recovering from
any race where the initial command was lost before the watch was ready.

### 17. Stealth-mode lock guard

When the phone app is disguised (stealth mode with PIN), `MainActivity`
launches `LockActivity` from `onResume()`. Without a guard, the subsequent
`onPause()` would call `viewModel.onBackground()`, which sends a spurious
`/minimize` to the watch and performs unnecessary background cleanup —
all before the user has even unlocked.

`LockActivity` returns `RESULT_OK` only after a matching PIN. MainActivity uses
Activity Result APIs; resumption, cancellation, or a singleTask relaunch never
counts as authentication. Control UI construction/foreground activation waits
for authorization, and the decor stays hidden while locking. An already
running authorized vibration retains the existing immediate-access STOP policy.

`onPause()` skips background handling during the initial lock transition and
safely handles an uninitialized ViewModel. UI collectors are registered once in
`onCreate`, wait for UI readiness, and run only while resumed; tile timers stop
on pause. Stealth aliases/preferences are committed only after PIN confirmation,
not during destruction or after a cancelled PIN dialog.

---

## Vibration Modes (6 total)

Most modes target an approximately 1000ms slow-speed cycle; RANDOM instead uses
a 30-step level-dependent section with no fixed average.

| Mode | Value | API | Pattern | Slow-speed shape |
|------|-------|-----|---------|------------------|
| Constant | 0 | Amplitude | `[1000ms at 255]` looped | Continuous |
| Intermittent | 1 | Amplitude | 70/30 on/off duty | 700+300=1000ms |
| Ramp | 2 | Amplitude | 5 ascending amplitude steps | 5×200=1000ms |
| Burst | 3 | Amplitude | 3 taps + pause, 50ms floor | 5×150+250=1000ms |
| Wave | 4 | Amplitude | 20-step sine, starts at trough | 20×50=1000ms |
| Random | 5 | Amplitude | 30 random segments, continuity-seeded on reassert | 100-500ms segment range |

Speed levels (0-3) use per-mode timing tables; RANDOM narrows its duration
range as speed increases instead of trying to preserve a fixed cycle length.

---

## Actuator cancellation

1. `VibratorManager.cancel()` — cancel all vibrators (API 31+)
2. `Vibrator.cancel()` — cancel the default vibrator
3. Silent non-repeating waveform with `USAGE_ALARM` — best-effort replacement
4. Silent non-repeating waveform in the default pipeline — best-effort replacement

The silent waveform is `[1ms at amplitude 0]`; a zero-amplitude OneShot is
invalid and previously threw a swallowed exception. Hardware flush behavior
still requires device validation. STOP DataItems are separately removed after
handling; command/recovery watermarks are Activity-scoped, not durable tombstones.

---

## Phone UI

```
┌──────────────────────────────┐
│  WatchVibe              [📊] │  ← title + stats icon
│  Connected                   │  ← connection status
│  🔋 85%                      │  ← watch battery (red/orange/gray)
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

Active tile: rotating white dots + a pulsing center that illustrates the mode's
envelope (3 pulses for Burst, sine for Wave, rising Ramp, etc.), not HAL telemetry.
Tap active tile again = stop. UI collectors and pulse/dot animation callbacks stop
when the screen pauses; the foreground service continues an authorized active session.

Charts share amplitude bounds (10% to 80% of height), so Constant reaches the same
peak as Wave. A shared 16dp resource clips only chart bottom corners, matching the
tile radius at each density while keeping the chart's top edge square.

---

## Watch UI (Kiosk Mode)

```
┌──────────────────────────────┐
│  Wave                        │  ← mode name as primary status
│  Slow                        │  ← speed indicator
│                              │
│       [vibration icon]       │
│                              │
│  Press crown to dismiss /    │
│  stop vibration              │
└──────────────────────────────┘
```

Status line shows: mode name when active, `Notification access required` /
`Enable notifications to start vibration.` when start is blocked by notification
gating, `"Ready"` when idle, and `"Waiting..."` when disconnected. All
touch/back/swipe blocked (kiosk mode).

Exit paths:
- dismissing the Activity triggers the deferred `onUserLeaveHint()` emergency-stop path
- while vibrating, an ongoing notification also exposes a STOP action

The Activity still renders above the keyguard via `setShowWhenLocked(true)`.

---

## Connection Detection

| Layer | Watch | Phone |
|-------|-------|-------|
| CapabilityClient listener | Detects phone presence → immediate cancel | Detects watch capability (fast disconnect only) |
| Reversed heartbeat `/alive` | Watch → phone every 2s while Activity is truly foreground | Gates `watchConnected` (true when /alive within 5s) |
| Heartbeat ping (dual) | Phone → watch every 1s via DataClient + MessageClient; hidden watch ignores lease renewal | Phone sends |
| Command recovery | Intent extras forwarded by VibrationDataLayerService; commands deferred until foreground-safe | Re-sends active command on first /alive after connect |
| Silent-stop recovery | Watch periodically reasserts active mode while lease is current and UI is foreground | — |
| Connection lease expiry (safety net) | 3s after last ping → both leases zeroed, cancel | N/A |
| Vibration lease | Extended by pings; zeroed by STOP; drives auto-resume | — |
| UI connection status | Derived from connectionLease (`connectionLeaseExpiry > now`) | — |
| Command TTL | Discards commands older than 30s or out-of-order | Commands carry timestamps |
| Session ID | Resets counter baseline, rejects retired IDs within the Activity | Shared once per phone process |
| State persistence | Desired vibration is not persisted | SavedStateHandle restores selections; activation waits for authenticated foreground |
| Background heartbeat | Hidden watch ignores lease renewal | Service owns active heartbeats; idle foreground pings belong to ViewModel |
| Wake-up | DataLayerService launches Activity | Phone sends /launch on open |
| Auto-minimize | Lease-expiry (30s delay) + phone-background (immediate) when idle | Phone sends /minimize on background |
| Emergency stop | Watch sends /crown_exit on dismiss; phone minimizes | Phone listens for /crown_exit, resets UI |
| Battery monitor | Push: sync read + broadcast on change; Pull: phone requests on connect | Phone displays battery icon + percentage |

---

## Project Structure

### watch-vibe-control/ (WatchVibeControl — Phone)

```
app/src/main/
├── AndroidManifest.xml
├── java/com/yieldinghartebeest13/watchvibe/
│   ├── AppConstants.kt       # Shared constants (identical in both projects)
│   ├── WearDataLayer.kt      # Shared transport/session + bounded independent channels
│   ├── PhoneHeartbeat.kt     # Ownership, atomic counter, wake-lock renewal
│   ├── PingForegroundService.kt # Sole active heartbeat owner
│   ├── MainViewModel.kt      # Control state, ownership, validated saved selections
│   ├── MainActivity.kt       # Authenticated 6-tile UI + lifecycle-bound animations
│   ├── SessionRecorder.kt    # Monotonic duration, mode/speed segments
│   ├── StatsActivity.kt      # Read-only history screen
│   ├── StatsViewModel.kt     # IO-backed statistics snapshots, no transport ownership
│   ├── StatsDb.kt            # Versioned SQLite storage with session merging
│   ├── LockActivity.kt       # Calculator with explicit authenticated result
│   ├── SettingsActivity.kt   # Confirmed PIN and launcher configuration
│   └── WaveformView.kt       # Mini waveform chart per tile (bitmap-cached)
└── res/
    ├── layout/activity_main.xml
    ├── layout/activity_stats.xml
    ├── drawable/ic_stats.xml
    ├── drawable/ic_battery.xml
    ├── drawable/ic_dots_circle.xml
    ├── drawable/tile_bg.xml
    └── drawable/tile_bg_pause_btn.xml
```

### watch-vibe/ (WatchVibe — Watch)

```
app/src/main/
├── AndroidManifest.xml       # VIBRATE + POST_NOTIFICATIONS permissions
├── java/com/yieldinghartebeest13/watchvibe/
│   ├── AppConstants.kt            # Shared constants (identical in both projects)
│   ├── VibratorEngine.kt          # 6 modes, amplitude API, cycle-aware reassert
│   ├── WatchSessionState.kt       # Monotonic leases + desired-mode recovery
│   ├── ControlMessage.kt          # Shared legacy/new message decoder
│   ├── VibrationDataLayerService.kt  # Wake dedupe + foreground-aware MainActivity launch
│   └── MainActivity.kt               # Kiosk mode + foreground gate + notification safety
└── res/
    ├── layout/activity_main.xml
    └── drawable/ic_vibration.xml
```

---

## Building & Running

### Local verification setup
```bash
export JAVA_HOME=/home/v/.sdkman/candidates/java/21.0.9-tem
export ANDROID_HOME=/home/v/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
```

Do **not** source `.env`: it is Make configuration and may contain shell-sensitive
credential values. For local verification, invoke Gradle directly in each app
root with `--no-daemon --max-workers=2 testDebugUnitTest lintDebug lintRelease
assembleDebug assembleRelease`. Release APKs from these tasks are unsigned; signing and device
installation are separate operations.

See [project review](docs/project-review.md) and
[stop investigation](docs/unexpected-stop-investigation.md) for evidence and limits.

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
make debug-wear       # VibeAct|VibeWake
make debug-phone      # VibeWearDL
make debug-wear-all   # All buffers, broad filter
make debug-phone-all
```

### Real-device helpers
```bash
make focus-wear                      # Show current watch foreground activity
make focus-phone                     # Show current phone foreground activity
make watch-home                      # Send HOME to the watch
make cancel-watch-vibration          # Force-cancel watch vibrator from adb shell
make watch-notification-status       # Inspect active watch notification state
make watch-notification-stop-action  # Fire the watch STOP notification intent
```

### Real-device safety checks
```bash
make test-real-foreground-stop    # Active vibration must stop on HOME/background
make test-real-silent-recovery    # Manual shell cancel should be reasserted
make test-real-notification-stop  # Notification STOP must end session on both sides
```

### Log tags
| Tag | App | Component |
|-----|-----|-----------|
| `VibeWearDL` | Phone | WearDataLayer |
| `VibeAct` | Watch | MainActivity (all vibration control) |
| `VibeWake` | Watch | VibrationDataLayerService |
