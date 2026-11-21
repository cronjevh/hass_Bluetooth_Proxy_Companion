# Bluetooth Proxy Technical Specification

## 1. Purpose

This document specifies the deployed Bluetooth proxy solution used for room-level presence detection and Bluetooth telemetry forwarding into Home Assistant.

It is intended to support:

- implementation understanding
- change management
- automated functionality monitoring
- operational troubleshooting

This spec describes the current forked Android scanner behavior as of 2026-11-21.

## 2. System Context

The solution is a fixed-room Bluetooth Low Energy proxy.

Primary flow:

1. A fixed Android device scans BLE advertisements in one room.
2. The Android app filters scan results to bonded devices, the hardcoded tracked devices, and the canary.
3. The Android app uploads the current cached device records to a Home Assistant webhook, together with scan metadata.
4. The Home Assistant custom integration `companion_bt_proxy` receives the webhook and injects the data into Home Assistant's Bluetooth subsystem.
5. Downstream Home Assistant entities and automations consume the Bluetooth data; one RSSI sensor exists per tracked device.

Originally this solution targeted iBeacon-based presence from a Galaxy Watch 5. The current operational direction is generic bonded-device BLE telemetry, with RSSI as the primary proximity signal and a fixed canary beacon used to prove scanner health.

## 3. Deployment Topology

### 3.1 Scanner Device

- Device type: Amazon Echo Show 5 Gen 2
- OS: LineageOS (Android 11), rooted, ADB over TCP on `192.168.0.133:5555`
- Role: fixed BLE scanner in a specific room
- Runtime: Android app `org.kvj.habtproxy` (debug build, no application-id suffix)
- Network: LAN only — the device has no default route, so it cannot reach the internet and does not NTP-sync. Its clock drifts; payload timestamps are therefore unreliable, while Home Assistant receipt time is not.

### 3.2 Tracked Devices

| Slot | Device | Bonded address | Entity |
|---|---|---|---|
| `cronje_phone` | iPhone 17 (Cronje) | `C4:16:8F:A0:0A:14` | `sensor.cronje_phone_rssi` |
| `monica_phone` | BezosMax-KC88 (Monica) | `29:17:5D:AB:4D:28` | `sensor.monica_phone_rssi` |
| canary | `CHR68437443` beacon | `FC:E2:EC:43:74:43` | none (health signal) |
| — | Monica's hearing aids | `D4:87:CC:9A:31:44` | not trackable: bond record is `[BR/EDR]` only, no BLE advertisements |

Removed 2026-11-21:

- Galaxy Watch5 (`4DYY`, `F8:5B:6E:9F:1E:CD`) — watch failed, removed from both sides
- Monica's A15 (`A0:7D:9C:68:5C:B7`) — Bluetooth kept off by default, removed from both sides

Important:

- Tracked devices are hardcoded in two places: `TRACKED_DEVICES` in `ScanWorker.kt` and `TRACKED_DEVICES` in the integration's `constants.py`.
- The scanner still uploads every bonded device, so the tracked table only controls which dedicated RSSI entities exist.
- A tracked device that is not bonded is still matched, because its address is added to the scan filter.
- If a tracked device rotates to an address Android does not resolve back to the bonded address in passive scan results, detection fails; add a `nameFragment` fallback if that happens.

### 3.3 Home Assistant Side

- Receives webhook POSTs from the Android app
- Runs a custom component fork of `kvj/hass_Bluetooth_Proxy`
- Exposes one RSSI sensor per tracked device plus a diagnostic `Last Update` sensor

## 4. Components

### 4.1 Android App

Package:

- `org.kvj.habtproxy`

Version:

- versionCode 1002, versionName 0.1.2 (deployed build)

Core files:

- `app/src/main/java/org/kvj/habtproxy/ScanWorker.kt`
- `app/src/main/java/org/kvj/habtproxy/DiscoveryResults.kt`
- `app/src/main/java/org/kvj/habtproxy/WorkScheduling.kt`
- `app/src/main/java/org/kvj/habtproxy/BootReceiver.kt`
- `app/src/main/java/org/kvj/habtproxy/SettingsActivity.kt`
- `app/src/main/java/org/kvj/habtproxy/Preferences.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle.kts`

Responsibilities:

- request runtime permissions
- schedule scan work on launch and boot
- perform bounded BLE scan cycles
- restrict telemetry to bonded devices plus tracked addresses and the canary
- cache latest device records
- upload current cached records and scan metadata to the Home Assistant webhook
- emit diagnostic log lines for monitoring

### 4.2 Home Assistant Custom Integration

External repository:

- `kvj/hass_Bluetooth_Proxy` (local fork: `cronjevh/hass_Bluetooth_Proxy`)

Key files:

- `custom_components/companion_bt_proxy/constants.py` — tracked-device table and canary
- `custom_components/companion_bt_proxy/scanner.py` — webhook ingestion and HA Bluetooth injection
- `custom_components/companion_bt_proxy/sensor.py` — Last Update sensor and generated RSSI sensors
- `custom_components/companion_bt_proxy/__init__.py` — webhook registration and entry setup

Responsibilities:

- accept webhook payloads from the Android proxy
- inject Bluetooth data into the Home Assistant Bluetooth stack
- update `sensor.echo_last_update` on every payload
- expose `sensor.<slug>_rssi` for each tracked device

### 4.3 Home Assistant Automations / Monitoring

Expected responsibilities:

- detect stale scanner uploads
- validate RSSI sensor freshness
- alert when tracked-device detection degrades

## 5. Android Runtime Behavior

### 5.1 Scheduling Model

The app uses WorkManager one-time work and reschedules the next run after each completed scan cycle.

Behavior:

- app launch:
  - `SettingsActivity.onResume()` requests permissions and schedules immediate work
- device boot:
  - `BootReceiver` schedules immediate work on `BOOT_COMPLETED`
- steady state:
  - `ScanWorker.doWork()` runs one scan/upload cycle
  - then schedules the next run after the configured scan interval

This replaced an older infinite-loop worker design that caused repeated JobScheduler timeouts.

### 5.2 Runtime Settings

Build-time defaults from `app/build.gradle.kts`:

- proxy enabled: `true`
- optimize background: `false`
- scan duration: `20` seconds
- scan interval: `30` seconds
- upload interval: `60` seconds
- webhook: injected from local build property `btproxy.defaultWebhook` (seeds preferences on first install only)

Deployed preferences on the Echo Show:

- scan duration: `8` seconds
- scan interval: `10` seconds
- upload interval: `60` seconds
- webhook: must equal the live config entry's `webhook` value

### 5.3 Scan Behavior

Current scan characteristics:

- scan mode: `SCAN_MODE_LOW_POWER` (one retry with `SCAN_MODE_LOW_LATENCY` when the first scan returns zero devices)
- callback type: `CALLBACK_TYPE_ALL_MATCHES`
- scan window: configured duration (`8` s deployed)
- tracked set: bonded devices + hardcoded tracked addresses + canary

Filtering behavior:

- bonded addresses are read from `BluetoothAdapter.bondedDevices`
- the hardcoded tracked addresses are always added to the filter, so a tracked device does not need to be bonded
- non-bonded, non-tracked, non-canary results are ignored

No classic-Bluetooth profile probing is performed; the watch-specific `watch_classic_*` telemetry was removed together with the watch in 0.1.2.

### 5.4 Upload Behavior

Each eligible upload sends the full current cache plus scan metadata.

Payload shape:

```json
{
  "scan_meta": {
    "status": "scan_completed",
    "permissions_granted": true,
    "bluetooth_enabled": true,
    "scan_started_timestamp": 1777190686323,
    "scan_finished_timestamp": 1777190695132,
    "total_devices_seen": 8,
    "matched_device_count": 2,
    "bonded_device_count": 5,
    "canary_seen": true,
    "scan_mode": 0
  },
  "devices": [
    {
      "address": "C4:16:8F:A0:0A:14",
      "name": null,
      "rssi": -87,
      "tx_power": 127,
      "timestamp": 1777190689675,
      "manufacturer_data": {"76": "AQAAAAAAAAAAAIAA..."}
    }
  ]
}
```

Upload gating:

- if webhook is empty, upload is skipped
- if `now - lastUploadTimestamp < uploadInterval`, upload is skipped
- if the cache is empty, the upload still proceeds with `devices: []` so Home Assistant can distinguish "scanner alive, nothing in range" from "scanner stopped posting"

Note: the integration accepts both the current object payload and the legacy bare-array payload.

## 6. Device Record Data Contract

Each item in `devices` has this shape:

```json
{
  "address": "C4:16:8F:A0:0A:14",
  "name": "iPhone 17",
  "rssi": -69,
  "tx_power": 127,
  "timestamp": 1772985709034,
  "service_uuids": [],
  "service_data": {},
  "manufacturer_data": {
    "76": "AQACA...base64..."
  }
}
```

Notes:

- `service_uuids` is omitted if absent
- `service_data` is omitted if absent
- `manufacturer_data` is omitted if absent
- `manufacturer_data` values are base64 in the webhook payload

Semantics:

- `address`: BLE device address observed by Android scanner
- `name`: advertised local name if present (often `null` for iOS devices)
- `rssi`: received signal strength indicator in dBm
- `tx_power`: Android `ScanResult.txPower`
- `timestamp`: local Android timestamp in epoch milliseconds (device clock is unreliable — see 3.1)

## 7. Current Known Device Telemetry Signatures

### 7.1 Cronje Phone

- address: `C4:16:8F:A0:0A:14`
- name in BLE adverts: usually absent (`null`)
- manufacturer data key: `76` (Apple)
- example manufacturer data hex: `01000000000000000080000000`

### 7.2 Canary Beacon

- address: `FC:E2:EC:43:74:43`
- name: `CHR68437443`
- no manufacturer data; flags only

These signatures are observational, not protocol guarantees.

## 8. Logs and Observability

### 8.1 Key Android Log Signatures

Successful scheduling and runtime:

- `BootReceiver: onReceive(): scheduling scan work after boot`
- `ScanWorker: doWork(): Next scan: true / true / false`
- `ScanWorker: doWork(): Scheduling next scan in 10 s`

Scan lifecycle:

- `ScanWorker: executeScan(): Bonded device filter=[...]`
- `ScanWorker: Scan has started for 8 s, mode=0`
- `ScanWorker: Scan has finished, devicesFound=8, cachedDevices=2`

Per-device telemetry:

- `ScanWorker: scanResult(): address=..., name=..., rssi=..., ...`

Upload lifecycle:

- `ScanWorker: uploadData(): Evaluating N cached devices, lastUploadTimestamp=..., now=...`
- `ScanWorker: uploadData(): Uploading N cached devices`
- `ScanWorker: Webhook send result: kotlin.Unit`

Failure indicators:

- `ScanWorker: No runtime permissions`
- `ScanWorker: Bluetooth adapter disabled`
- `ScanWorker: uploadData(): No webhook set`
- `ScanWorker: Error sending data via webhook`
- `ScanWorker: uploadData(): Uploading empty payload heartbeat (no cached devices)`

### 8.2 Android Health Conditions

Healthy Android proxy behavior means all are true:

- scans continue on the configured interval
- `devicesFound >= 1` when tracked devices or the canary are nearby
- `cachedDevices` reflects the bonded set currently seen
- upload succeeds at expected cadence
- no recurring JobScheduler timeout pattern

### 8.3 Home Assistant Health Conditions

Healthy Home Assistant side behavior means all are true:

- webhook receives payloads on schedule
- integration processes the payload without exceptions
- `sensor.echo_last_update` stays fresh and `canary_last_seen` advances
- tracked RSSI entities update when their device is present

## 9. Monitoring Specification

### 9.1 Monitoring Objectives

Monitoring should detect:

- Android scanner stopped scheduling
- Android BLE scan returns zero devices unexpectedly
- upload failures or missing webhook posts
- Home Assistant entity staleness
- canary disappearance (transport broken even if heartbeats continue)

### 9.2 Suggested Service-Level Expectations

Recommended baseline expectations:

- scan cycle frequency: every `10` seconds
- scan duration: `8` seconds
- upload cadence: at least one successful upload every `90` seconds under normal conditions
- tracked-device freshness: RSSI newer than `180` seconds while the device is nearby
- canary freshness: seen within `24` hours

### 9.3 Machine-Readable Monitoring Targets

```yaml
system:
  name: bluetooth_proxy_solution
  scanner_app:
    package: org.kvj.habtproxy
    platform: android
    schedule:
      scan_interval_seconds: 10
      scan_duration_seconds: 8
      upload_interval_seconds: 60
    scan_mode: LOW_POWER
    tracked_filter: bonded_plus_tracked
  tracked_devices:
    cronje_phone:
      bonded_address: "C4:16:8F:A0:0A:14"
      expected_entity: sensor.cronje_phone_rssi
  canary:
    address: "FC:E2:EC:43:74:43"
    expected_attribute: sensor.echo_last_update.attributes.canary_last_seen
monitoring:
  android:
    stale_scan_threshold_seconds: 120
    stale_upload_threshold_seconds: 120
    zero_devices_alert_threshold_cycles: 3
  home_assistant:
    stale_heartbeat_threshold_minutes: 5
    canary_threshold_hours: 24
```

### 9.4 Recommended Automated Checks

Android:

1. Latest log contains `doWork(): Scheduling next scan in`.
2. Latest log contains `executeScan(): Bonded device filter=`.
3. Latest successful run contains `uploadData(): Uploading`.
4. Alert if `Error sending data via webhook` occurs.
5. Alert if `devicesFound=0` for 3 consecutive scan cycles while the room is expected occupied.

Home Assistant:

1. `sensor.echo_last_update` state is fresh (≤ 5 min).
2. `sensor.echo_last_update.attributes.canary_last_seen` is within 24 h.
3. `sensor.cronje_phone_rssi` is not stale when the phone is expected at home.
4. Alert if a tracked entity becomes `unavailable` unexpectedly for longer than the agreed threshold.

### 9.5 Example Monitoring Assertions

```yaml
assertions:
  - id: android_scan_scheduler_alive
    type: log_pattern_age
    source: android_logcat
    pattern: "doWork(): Scheduling next scan in"
    max_age_seconds: 120

  - id: android_upload_alive
    type: log_pattern_age
    source: android_logcat
    pattern: "uploadData(): Uploading"
    max_age_seconds: 120

  - id: cronje_phone_entity_fresh
    type: ha_entity_last_changed
    entity_id: sensor.cronje_phone_rssi
    max_age_seconds: 180
```

## 10. Failure Modes

Known or likely failure classes:

- runtime permissions revoked
- Bluetooth adapter disabled
- bonded address changes / device rotates to an address Android does not resolve
- webhook misconfiguration (any webhook id returns HTTP 200, so verify the id against the config entry, not the response code)
- Android scheduler disruption after reboot
- Home Assistant integration receiving data but not exposing/updating entities
- device clock drift (no NTP) making payload timestamps misleading

## 11. Security and Privacy Notes

- The webhook endpoint is a capability URL; treat the webhook id as a secret
- The webhook id is seeded at build time from `local.properties` and must be kept in sync with the live config entry
- BLE payloads may contain stable device identifiers
- monitoring pipelines should treat RSSI and BLE identity data as private telemetry

## 12. Change History Relevant To Monitoring

This fork includes these important behavioral changes:

- 0.1.1 (2026-03-19): per-device scan metadata, canary device, empty-payload heartbeat, bounded scans with zero-result retry, classic-profile watch telemetry
- 0.1.2 (2026-11-21): watch and Monica phone removed; tracked devices reduced to a single `TRACKED_DEVICES` table; classic-Bluetooth watch probing and `watch_classic_*` telemetry removed; build-time default webhook corrected to the live id

Earlier fork changes:

- one-shot WorkManager execution with explicit rescheduling
- boot-time scan scheduling via `BootReceiver`
- scan and upload interval tuning
- scan mode changed from balanced to low power
- scan logging expanded to include raw BLE payload fields
- numeric preference parsing fixed by trimming input

## 13. Open Follow-Up Work

- add the next tracked devices (one `TRACKED_DEVICES` entry per side, then rebuild and restart core)
- decide fallback strategy for phone location if BLE identity becomes unreliable
- optionally replace Android log-based monitoring with an explicit health endpoint
- fix the Echo Show clock (restore a default route or set time manually) so payload timestamps stop misleading diagnostics
