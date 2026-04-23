# Experiment Data Schema (Probe + Ground Truth)

This document defines the current exported data structures for:
- Probe app (`CellIDTracker`)
- Ground-truth app (`LocationData`)

It is intended for offline analysis and dataset merging.

## 1) Probe Side (`CellIDTracker`)

### 1.1 Primary analysis export (session-scoped)

- File name: `<sessionId>.json`
- Export folder: `Android/data/com.example.cellidtracker/files/experiment_sessions/`
- Generated when pressing `Stop & export` in the Probe app's experiment session UI.
- Session ID format: local date-time string `yyyyMMdd_HHmmss_SSS` (example: `20260412_143210_127`)

Top-level JSON object:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | int | Currently `1` |
| `appType` | string | `"probe"` |
| `appName` | string | App label |
| `appPackage` | string | Package name |
| `appVersionName` | string | App version name |
| `deviceIdentifier` | string or null | `manufacturer_model_device` |
| `sessionId` | string | Probe session UUID |
| `startedAtMillis` | long | Unix epoch milliseconds |
| `endedAtMillis` | long or null | Unix epoch milliseconds |
| `exportedAtMillis` | long | Unix epoch milliseconds |
| `samples` | array | Per-probe sample records |

Each item in `samples`:

| Field | Type | Notes |
|---|---|---|
| `recordedAtMillis` | long | Time sample was recorded in app |
| `victim` | string | Victim key/number snapshot |
| `mcc` | int | Parsed cell MCC |
| `mnc` | int | Parsed cell MNC |
| `lac` | int | Parsed cell LAC |
| `cid` | int | Parsed cell CID |
| `estimatedLat` | double or null | Google Geolocation result |
| `estimatedLon` | double or null | Google Geolocation result |
| `estimatedAccuracyM` | double or null | Google Geolocation accuracy |
| `geolocationStatus` | string | `"success"` or `"failure"` |
| `geolocationError` | string or null | Error message when failed |
| `towersCount` | int | Number of towers sent to geolocation |
| `towersJson` | string | JSON string (not array type) containing towers |
| `deltaMs` | long or null | Parsed from native structured probe event (`[probe_event] ... delta_ms=...`) |
| `moving` | boolean | Manual UI toggle snapshot (not GPS-derived) |

`towersJson` string decodes to array of objects:
- `mcc` (int)
- `mnc` (int)
- `lac` (int)
- `cid` (int)
- `radio` (string, usually `"lte"`)

### 1.2 Secondary export (history dump)

- File name: `probe_history.json`
- Export folder: `Android/data/com.example.cellidtracker/files/`
- Format: top-level JSON array (not session grouped)

Each item includes:
`victim, mcc, mnc, lac, cid, lat, lon, accuracy, timestampMillis, towersCount, towers, moving, deltaMs`

Notes:
- `towers` here is a real JSON array (unlike `towersJson` above).
- Use this for quick inspection; use session export for experiment analysis.

### 1.3 Probe-side semantics important for analysis

- `moving` is operator input from app switch, not measured movement.
- `deltaMs` may be `null` when no native `[probe_event]` line is available for a probe cycle.
- The app uses native `call_id` from `[probe_event]` as the primary dedupe key to prevent duplicate history entries from repeated `183` retransmissions.

---

## 2) Ground-Truth Side (`LocationData`)

### 2.1 Primary export

- File name: `<sessionId>.json`
- Export folder: `Android/data/com.example.locationdata/files/ground_truth_sessions/`
- Generated when pressing `Stop & export` in ground-truth app.
- Session ID format: local date-time string `yyyyMMdd_HHmmss_SSS` generated automatically at capture start.

Top-level JSON object:

| Field | Type | Notes |
|---|---|---|
| `schemaVersion` | int | Currently `1` |
| `appType` | string | `"ground_truth"` |
| `sessionId` | string | User-specified or generated |
| `startedAtMillis` | long | Unix epoch milliseconds |
| `endedAtMillis` | long | Unix epoch milliseconds |
| `appName` | string | App label |
| `packageName` | string | Package name |
| `appVersionName` | string | App version name |
| `appVersionCode` | long | Version code |
| `device` | object | Device metadata |
| `samples` | array | 1 Hz-ish location samples |

`device` object fields:
- `manufacturer` (string)
- `model` (string)
- `device` (string)
- `sdkInt` (int)

Each item in `samples`:

| Field | Type | Notes |
|---|---|---|
| `recordedAtMillis` | long | Sample wall-clock time |
| `elapsedRealtimeNanos` | long | Monotonic timestamp from Android location |
| `lat` | double | Latitude |
| `lon` | double | Longitude |
| `accuracyM` | double | Horizontal accuracy (meters) |
| `provider` | string | Location provider |
| `speedMps` | double or null | Speed in meters/second |
| `speedAccuracyMps` | double or null | Speed accuracy |
| `altitudeM` | double or null | Altitude |
| `bearingDeg` | double or null | Bearing |
| `movementClass` | string | One of classes below |
| `movementSource` | string | Currently `"auto"` |

`movementClass` mapping from `speedMps`:
- `stationary`: `< 0.5`
- `slow_move`: `0.5 .. < 1.8`
- `medium_move`: `1.8 .. < 4.5`
- `fast_move`: `>= 4.5`
- `unknown`: speed unavailable

### 2.2 Runtime persistence file (not final export)

- File: `files/ground_truth_active_session.json`
- Purpose: restore ongoing capture if app is killed/restarted.
- Not intended as final analysis artifact.

---

## 3) Cross-App Time Alignment Rules

### 3.1 Shared timestamp format

Probe and ground-truth exports both use Unix epoch milliseconds for:
- `recordedAtMillis`
- `startedAtMillis`
- `endedAtMillis`

These fields are directly comparable across files.

### 3.2 Recommended merge strategy

1. Treat probe sample `recordedAtMillis` as anchor timestamps.
2. Join nearest ground-truth sample(s) by time window around each probe sample.
3. Common windows:
   - strict nearest: `±2s`
   - robust summary: `±15s` to `±30s` (for slower probe cadence)

### 3.3 Session ID caution

- Probe `sessionId` and ground-truth `sessionId` are generated independently (both timestamp-based in each app).
- Do not assume they match by default; rely on time alignment unless IDs were coordinated.

---

## 4) Quick Field Crosswalk

| Analysis concept | Probe field | Ground-truth field |
|---|---|---|
| Event time | `recordedAtMillis` | `recordedAtMillis` |
| Session start/end | `startedAtMillis` / `endedAtMillis` | `startedAtMillis` / `endedAtMillis` |
| Cell identity | `mcc,mnc,lac,cid` | N/A |
| Probe-estimated location | `estimatedLat,estimatedLon,estimatedAccuracyM` | N/A |
| Ground-truth location | N/A | `lat,lon,accuracyM` |
| Inter-carrier timing | `deltaMs` | N/A |
| Movement label | `moving` (manual toggle) | `movementClass` (speed-derived) |
| Speed | N/A | `speedMps` |
