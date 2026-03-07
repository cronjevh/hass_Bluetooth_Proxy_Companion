# Coding Assistant Prompt — hass_Bluetooth_Proxy_Companion Fork

## Background

This Android app (`hass_Bluetooth_Proxy_Companion`) is being used as a **Bluetooth LE presence detection proxy** for Home Assistant room-level presence. The deployment is:

- **Scanner device**: A repurposed Amazon Echo Show 5 Gen 2 running LineageOS (rooted), acting as a fixed BLE scanner in a specific room.
- **Beacon device**: A **Samsung Galaxy Watch 5** worn by the primary user. The Watch runs the Home Assistant Wear OS companion app which broadcasts a BLE iBeacon advertisement (`UUID: b25beee9-da0c-486e-aa76-b3a72d576205`, major: 100, minor: 40004, TX power: −59 dBm, advertise mode: lowPower).
- **Home Assistant side**: The `companion_bt_proxy` custom component (separate repo: `kvj/hass_Bluetooth_Proxy`) receives the webhook POSTs and injects scan results into HA's Bluetooth scanner framework. HA's built-in iBeacon Tracker integration then auto-creates:
  - `device_tracker.b25beee9_..._estimated_distance` — estimated distance in metres
  - `sensor.b25beee9_..._estimated_distance` — distance sensor

The goal is **room-level presence detection**: when the user (wearing the watch) enters the room where the Echo Show lives, HA detects the drop in estimated distance and can route voice interactions, trigger automations, etc.

## The Problem

The app currently only POSTs a device to the HA webhook **when it is first discovered or when its data changes significantly**. The relevant logic is in two places:

### `DiscoveryResults.kt` — `updateMaybe()`
- Updates `device.timestamp` only when a meaningful change is detected.
- The RSSI change threshold before an update is triggered is **10 dB** — far too coarse for proximity tracking (a person moving from 1 m to 3 m away changes RSSI by roughly 5–9 dB depending on environment).

### `ScanWorker.kt` — `uploadData()`
- Filters devices for upload using: `timestamp >= discoveryResults.lastUploadTimestamp`
- If a device's timestamp was not refreshed (because nothing "changed" per `updateMaybe()`), it is silently skipped.
- Log output confirming the problem: `D ScanWorker: uploadData(): Skip upload, no new devices`

**Result**: After the Galaxy Watch 5 is discovered on the first scan, subsequent scans see it at a stable or slowly-changing RSSI and never re-upload it. HA's iBeacon estimated distance sensor goes stale. Room presence detection does not work.

## Required Changes

### 1. `DiscoveryResults.kt` — Always refresh timestamp, lower RSSI threshold

In `updateMaybe()`:

- **Move `device.timestamp = Date().time` (or equivalent) outside the `if (changed)` block** so it always executes when the device is seen, regardless of whether data changed. This ensures every device visible in the current scan window always gets an updated timestamp and is included in the next upload.
- **Lower the RSSI change threshold from 10 dB to 2 dB** (or make it configurable). A 2 dB threshold is sufficient to capture meaningful movement while still avoiding noise-driven re-uploads on a completely stationary device.

### 2. `ScanWorker.kt` — No functional change needed

The `uploadData()` timestamp filter (`timestamp >= lastUploadTimestamp`) is correct — once `updateMaybe()` always refreshes the timestamp, devices seen in the current scan window will always be included.

However, review whether `uploadData()` should log something more informative when it uploads existing (non-new) devices, to distinguish from the "skip upload, no new devices" case. A log line like `D ScanWorker: uploadData(): Uploading N devices (M new, K updated)` would aid debugging.

## What NOT to Change

- The upload interval logic (`upload_inteval` preference, currently set to 30 s) — this is correct throttling behaviour.
- The scan duration (5 s) and scan interval (30 s) — these are appropriate for the use case.
- The webhook POST format — the `companion_bt_proxy` HA component expects the current JSON schema exactly.
- The `updateMaybe()` return value semantics — it can still return `true`/`false` to indicate whether a meaningful data change occurred (useful for logging); the timestamp update should just no longer be gated on that return value.

## Acceptance Criteria

After the fix, with the Galaxy Watch 5 in the same room as the Echo Show:

1. Every scan cycle (~30 s) the app POSTs the watch's entry to the webhook, even if its RSSI has not changed significantly.
2. The logcat no longer shows `Skip upload, no new devices` when the watch is in range.
3. HA's `sensor.b25beee9_..._estimated_distance` updates approximately every 30 s with a fresh distance estimate.
4. When the user leaves the room (watch RSSI drops below the iBeacon integration's "not home" threshold), HA eventually marks the `device_tracker` as `not_home`.

## Additional Context — Known Bug Already Fixed

A pre-existing bug caused the `ScanWorker` to crash with:
```
java.lang.NumberFormatException: For input string: "30\n"
at org.kvj.habtproxy.PreferencesKt.getInt(Preferences.kt:7)
```
The `upload_inteval` SharedPreference was stored with a trailing newline. The fix is in `Preferences.kt` line 7 — the `getInt` helper should call `.trim()` before `.toInt()`:

```kotlin
// Before
return getString(context.getString(key), context.getString(def))?.toInt() ?: 0

// After
return getString(context.getString(key), context.getString(def))?.trim()?.toInt() ?: 0
```

This should be included in the same PR as it will affect any user who enters a numeric value and presses the keyboard return key.
