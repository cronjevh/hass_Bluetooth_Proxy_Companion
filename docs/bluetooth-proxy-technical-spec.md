# Bluetooth Proxy Technical Specification

## 1. Purpose

This document specifies the deployed Bluetooth proxy solution used for room-level presence detection and Bluetooth telemetry forwarding into Home Assistant.

It is intended to support:

- implementation understanding
- change management
- automated functionality monitoring
- operational troubleshooting

This spec describes the current forked Android scanner behavior as of 2026-03-10.

## 2. System Context

The solution is a fixed-room Bluetooth Low Energy proxy.

Primary flow:

1. A fixed Android device scans BLE advertisements in one room.
2. The Android app filters scan results to bonded devices only.
3. The Android app uploads the latest cached device records to a Home Assistant webhook.
4. The Home Assistant custom integration `companion_bt_proxy` receives the webhook and injects the data into Home Assistant's Bluetooth subsystem.
5. Downstream Home Assistant entities and automations consume the Bluetooth data.

Originally this solution targeted iBeacon-based presence from a Galaxy Watch 5. The current operational direction is broader: generic bonded-device BLE telemetry, with RSSI used as the primary proximity signal.

## 3. Deployment Topology

### 3.1 Scanner Device

- Device type: Amazon Echo Show 5 Gen 2
- OS: LineageOS
- Role: fixed BLE scanner in a specific room
- Runtime: Android app `org.kvj.habtproxy`

### 3.2 Tracked Devices

- Watch:
  - Friendly name: `Galaxy Watch5 (4DYY)`
  - Bonded address observed by scanner: `F8:5B:6E:9F:1E:CD`
- Phone:
  - Friendly name: `Monica's A15`
  - Bonded address observed by scanner: `A0:7D:9C:68:5C:B7`

Important:

- The Android scanner currently filters to bonded addresses.
- Device identity is therefore currently tied to the scanner's Bluetooth bonded device list.
- If a device later rotates to an address that Android does not resolve back to the bonded address in passive scan results, detection for that device will fail.

### 3.3 Home Assistant Side

- Receives webhook POSTs from the Android app
- Runs a custom component fork of `kvj/hass_Bluetooth_Proxy`
- Current intended outcome:
  - preserve Bluetooth proxy ingestion
  - expose RSSI-oriented entities for the watch and phone

## 4. Components

### 4.1 Android App

Package:

- `org.kvj.habtproxy`

Core files:

- [ScanWorker.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/ScanWorker.kt)
- [DiscoveryResults.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/DiscoveryResults.kt)
- [WorkScheduling.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/WorkScheduling.kt)
- [BootReceiver.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/BootReceiver.kt)
- [SettingsActivity.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/SettingsActivity.kt)
- [Preferences.kt](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/java/org/kvj/habtproxy/Preferences.kt)
- [AndroidManifest.xml](/c:/git/hass_Bluetooth_Proxy_Companion/app/src/main/AndroidManifest.xml)
- [build.gradle.kts](/c:/git/hass_Bluetooth_Proxy_Companion/app/build.gradle.kts)

Responsibilities:

- request runtime permissions
- schedule scan work on launch and boot
- perform bounded BLE scan cycles
- restrict telemetry to bonded devices
- cache latest device records
- upload current cached records to Home Assistant webhook
- emit diagnostic log lines for monitoring

### 4.2 Home Assistant Custom Integration

External repository:

- `kvj/hass_Bluetooth_Proxy`

Responsibilities:

- accept webhook payloads from Android proxy
- inject Bluetooth data into Home Assistant Bluetooth stack
- expose integration entities
- in the planned fork, expose hardcoded RSSI entities for watch and phone

### 4.3 Home Assistant Automations / Monitoring

Expected responsibilities:

- detect stale scanner uploads
- detect missing watch/phone telemetry
- validate RSSI sensor freshness
- alert when bonded-device detection degrades

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

### 5.2 Default Runtime Settings

Current build-time defaults from [build.gradle.kts](/c:/git/hass_Bluetooth_Proxy_Companion/app/build.gradle.kts):

- proxy enabled: `true`
- optimize background: `false`
- scan duration: `5` seconds
- scan interval: `60` seconds
- upload interval: `60` seconds
- webhook: injected from local build property `btproxy.defaultWebhook`

### 5.3 Scan Behavior

Current scan characteristics:

- scan mode: `SCAN_MODE_LOW_POWER`
- callback type: `CALLBACK_TYPE_ALL_MATCHES`
- scan window: `5` seconds
- tracked set: bonded devices only

Filtering behavior:

- bonded addresses are read from `BluetoothAdapter.bondedDevices`
- any cached non-bonded records are removed before the scan cycle
- any non-bonded scan result is ignored

### 5.4 Upload Behavior

Each eligible upload sends the full current bonded-device cache.

Upload gating:

- if webhook is empty, upload is skipped
- if `now - lastUploadTimestamp < uploadInterval`, upload is skipped
- if cache is empty, upload is skipped

Upload transport:

- HTTP POST
- content type: `application/json`
- body: JSON array of device records

## 6. Device Record Data Contract

### 6.1 Payload Shape

The Android app uploads a JSON array. Each item has this shape:

```json
{
  "address": "F8:5B:6E:9F:1E:CD",
  "name": "Galaxy Watch5 (4DYY)",
  "rssi": -69,
  "tx_power": 127,
  "timestamp": 1772985709034,
  "service_uuids": [],
  "service_data": {},
  "manufacturer_data": {
    "117": "AQACA...base64..."
  }
}
```

Notes:

- `service_uuids` is omitted if absent
- `service_data` is omitted if absent
- `manufacturer_data` is omitted if absent
- `manufacturer_data` values are base64 in the webhook payload
- Android logs print `service_data` and `manufacturer_data` in hex for diagnostics

### 6.2 Semantic Meanings

- `address`: BLE device address observed by Android scanner
- `name`: advertised local name if present
- `rssi`: received signal strength indicator in dBm
- `tx_power`: Android `ScanResult.txPower`
- `timestamp`: local Android timestamp in epoch milliseconds when device was last seen
- `service_uuids`: advertised service UUID list
- `service_data`: advertised service data keyed by UUID
- `manufacturer_data`: advertised manufacturer data keyed by manufacturer ID

## 7. Current Known Device Telemetry Signatures

Observed bonded-device signatures from current logs:

### 7.1 Watch

- address: `F8:5B:6E:9F:1E:CD`
- name: `Galaxy Watch5 (4DYY)`
- manufacturer data key: `117`
- example manufacturer data hex:
  - `010002000103FF000043140024`

### 7.2 Phone

- address: `A0:7D:9C:68:5C:B7`
- name in BLE adverts:
  - often absent in passive scan results
- manufacturer data key: `117`
- example manufacturer data hex:
  - `187950C7B9`
  - values may vary between scan samples

These signatures are observational, not protocol guarantees.

## 8. Logs and Observability

### 8.1 Key Android Log Signatures

The Android app emits stable log lines that can be used for machine monitoring.

Successful scheduling and runtime:

- `BootReceiver: onReceive(): scheduling scan work after boot`
- `ScanWorker: doWork(): Next scan: true / true / false`
- `ScanWorker: doWork(): Scheduling next scan in 60 s`

Scan lifecycle:

- `ScanWorker: executeScan(): Bonded device filter=[...]`
- `ScanWorker: Scan has started for 5 s, mode=0`
- `ScanWorker: Scan has finished, devicesFound=2, cachedDevices=2`

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
- `ScanWorker: uploadData(): Skip upload, no cached devices`

### 8.2 Android Health Conditions

Healthy Android proxy behavior means all are true:

- scans continue on the configured interval
- `devicesFound >= 1` when tracked devices are nearby
- `cachedDevices` reflects the bonded set currently seen
- upload succeeds at expected cadence
- no recurring JobScheduler timeout pattern

### 8.3 Home Assistant Health Conditions

Healthy Home Assistant side behavior means all are true:

- webhook receives payloads on schedule
- integration processes the payload without exceptions
- downstream RSSI or Bluetooth entities update within expected freshness bounds

## 9. Monitoring Specification

### 9.1 Monitoring Objectives

Monitoring should detect:

- Android scanner stopped scheduling
- Android BLE scan returns zero bonded devices unexpectedly
- upload failures or missing webhook posts
- Home Assistant entity staleness
- watch/phone disappearance from bonded telemetry

### 9.2 Suggested Service-Level Expectations

Recommended baseline expectations:

- scan cycle frequency:
  - every `60` seconds
- scan duration:
  - `5` seconds
- upload cadence:
  - at least one successful upload every `90` seconds under normal conditions
- tracked-device freshness:
  - watch RSSI data newer than `180` seconds when watch is nearby
  - phone RSSI data newer than `180` seconds when phone is nearby

### 9.3 Machine-Readable Monitoring Targets

```yaml
system:
  name: bluetooth_proxy_solution
  scanner_app:
    package: org.kvj.habtproxy
    platform: android
    schedule:
      scan_interval_seconds: 60
      scan_duration_seconds: 5
      upload_interval_seconds: 60
    scan_mode: LOW_POWER
    bonded_only: true
  tracked_devices:
    watch:
      expected_name_contains: "4DYY"
      bonded_address: "F8:5B:6E:9F:1E:CD"
      expected_entity: sensor.monica_watch_rssi
    phone:
      expected_name: "Monica's A15"
      bonded_address: "A0:7D:9C:68:5C:B7"
      expected_entity: sensor.monica_phone_rssi
monitoring:
  android:
    stale_scan_threshold_seconds: 120
    stale_upload_threshold_seconds: 120
    zero_devices_alert_threshold_cycles: 3
  home_assistant:
    stale_entity_threshold_seconds: 180
    missing_entity_threshold_cycles: 3
```

### 9.4 Recommended Automated Checks

#### Android Checks

1. Check latest log contains `doWork(): Scheduling next scan in`.
2. Check latest log contains `executeScan(): Bonded device filter=`.
3. Check latest successful run contains `uploadData(): Uploading`.
4. Alert if `Error sending data via webhook` occurs.
5. Alert if `devicesFound=0` for 3 consecutive scan cycles while the room is expected occupied.

#### Home Assistant Checks

1. Check integration webhook endpoint receives data at least once every 90 seconds.
2. Check `sensor.monica_watch_rssi` is not stale.
3. Check `sensor.monica_phone_rssi` is not stale when phone is expected at home.
4. Alert if entity state becomes `unavailable` unexpectedly.

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

  - id: watch_entity_fresh
    type: ha_entity_last_changed
    entity_id: sensor.monica_watch_rssi
    max_age_seconds: 180

  - id: phone_entity_fresh
    type: ha_entity_last_changed
    entity_id: sensor.monica_phone_rssi
    max_age_seconds: 180
```

## 10. Failure Modes

Known or likely failure classes:

- runtime permissions revoked
- Bluetooth adapter disabled
- bonded addresses no longer matching passive adverts
- webhook misconfiguration
- Android scheduler disruption after reboot
- Home Assistant integration receiving data but not exposing/updating entities
- phone not advertising enough BLE data for reliable room inference

## 11. Security and Privacy Notes

- Webhook endpoint is locally injected at build time and should not be committed
- BLE payloads may contain stable device identifiers
- monitoring pipelines should treat RSSI and BLE identity data as private telemetry

## 12. Change History Relevant To Monitoring

This fork includes these important behavioral changes:

- one-shot WorkManager execution with explicit rescheduling
- boot-time scan scheduling via `BootReceiver`
- default scan and upload interval changed to 60 seconds
- scan mode changed from balanced to low power
- scan logging expanded to include raw BLE payload fields
- bonded-device-only filtering added
- numeric preference parsing fixed by trimming input

## 13. Open Follow-Up Work

- finalize Home Assistant fork exposing:
  - `sensor.monica_watch_rssi`
  - `sensor.monica_phone_rssi`
- define Home Assistant-side availability and stale-data handling
- decide fallback strategy for phone location if BLE identity becomes unreliable
- optionally replace Android log-based monitoring with explicit health endpoint or heartbeat entity
