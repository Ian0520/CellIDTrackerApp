# CellIDTracker Project Overview

## Project Overview
- Project Name: CellIDTracker
- Background/Motivation: The goal is to automate the workflow of extracting Cell ID information from a SIP/Wi‑Fi Calling probe and turning it into a map location, removing manual log parsing and ad‑hoc lookup steps.
- Core Objective: Run a rooted probe, parse cell info (MCC/MNC/LAC/CID), query Google Geolocation, and visualize the result with an accuracy circle; provide history and inter‑carrier detection for validation.

## System Architecture
(Text diagram; replace with a visual diagram if needed.)

```
[Android App (Compose UI)]
   |-- MainViewModel (UI state and actions)
   |-- ProbeProcessCoordinator (process/watchdog/restart)
   |-- ProbeStreamSession (event protocol/reduction/deduplication)
   |-- Repositories (attempt/result/run/session persistence)
   |-- Google Geolocation Client
   |-- Room Database
   |-- Map Viewer (osmdroid)
            | root process
            v
[Native Probe Binary (C++ / SIP)]
   |-- ProbeController (continuous INVITE/CANCEL loop)
   |-- SIP response parser + response state machine
   |-- Session (packet/ESP transport and native timing)
   |-- Cell ID extraction
   |-- Versioned JSONL probe events
```

Module Breakdown:
- UI/UX (Compose): controls, status, map, history, and bounded logs.
- ViewModel: exposes UI state and delegates application work.
- Process coordinator: executes the root process and owns watchdog/restart policy.
- Stream session: parses structured events and suppresses legacy persistence after `stream_ready`.
- Repositories: persist probe attempts, results, runs, and experiment sessions.
- Geolocation client: resolves the latest accepted cell through Google Geolocation.
- Native probe controller: owns the continuous probe transaction loop, watchdog actions, and minimum INVITE interval.
- Native SIP parser/state machine: parses response metadata and makes host-tested state-transition decisions.
- Native session: owns packet decoding/encoding, native monotonic timing, stale-transaction rejection, and event emission.

## Tech Stack

| Category | Technology | Reason |
| --- | --- | --- |
| Frontend | Kotlin + Jetpack Compose | Native Android UI, rapid iteration |
| Native/Low‑level | C++ / NDK / CMake | SIP probe needs low-level control |
| Networking | OkHttp (app), libcurl + mbedtls (native) | Stable HTTP clients |
| Map | osmdroid | Open-source, easy circle overlay |
| Build | Gradle (KTS) | Standard Android tooling |
| Database | Room/SQLite | Durable history, attempts, runs, and experiment sessions |

## Functional Specifications
Main Features:
- Set victim number (written to `victim_list`).
- Run Probe (root) and parse MCC/MNC/LAC/CID live.
- Query Google Geolocation and show lat/lon/accuracy.
- Draw accuracy circle on map.
- History list (time, victim, cell, location) with tap‑to‑restore.
- Inter‑carrier test and status display.
- Log viewer with expandable view.

User Roles:
- Single operator (no authentication/roles).

Flow (simplified):
```
Input victim → Probe → root binary → parse cell info
→ Google Geolocation → map display → store history
```

## Data & API Design
Data:
- `victim_list` file for the probe binary.
- `probe_history`, `probe_attempts`, `probe_runs`, `experiment_sessions`, and `experiment_samples` Room tables.
- Bounded in-memory history and run projections used by the Compose UI.
- JSON experiment-session and history exports; no application server.

API (Google Geolocation):
- Endpoint: `POST https://www.googleapis.com/geolocation/v1/geolocate?key=...`
- Request:
```json
{
  "considerIp": false,
  "cellTowers": [
    {
      "mobileCountryCode": 466,
      "mobileNetworkCode": 92,
      "locationAreaCode": 13700,
      "cellId": 81261592,
      "radioType": "lte"
    }
  ]
}
```
- Response:
```json
{
  "location": { "lat": 25.03, "lng": 121.56 },
  "accuracy": 1200
}
```

## Implementation Details
- Cell ID parsing: real‑time stdout parsing updates UI.
- Experiment geolocation: only the latest accepted cell is sent per probe result.
- Probe latency: measured natively from fresh INVITE transmission to the first matching provisional response (`180/183`) using a monotonic clock.
- Native response handling: pure host tests cover `180`, `181`, `183`, `200`, `401`, `407`, `408`, `481`, `486`, `487`, and `500`, including retry precedence.
- Inter‑carrier detection: classifies the native INVITE-to-provisional `delta_ms`.
  - `<= 525 ms` → inter‑carrier.
  - UI shows a unified “Inter‑carrier:” line.
- Stability: coroutine-based streaming avoids UI blocking.
- Security: API key is not checked in; it’s read from `local.properties` and passed to the native binary via environment variable.

## Installation & Setup
Environment:
- Android Studio + JDK 11
- Android SDK 24+
- Rooted device
- For native rebuild: NDK r25, CMake >= 3.24

Build (App):
```bash
# local.properties (git-ignored)
GOOGLE_API_KEY=your_real_key_here

./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Rebuild probe (armeabi‑v7a example):
```bash
export NDK=$HOME/android-ndk-r25
cd wifi-calling
cmake -S all -B build \
  -D CMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -D ANDROID_PLATFORM=android-29 \
  -D ANDROID_ABI=armeabi-v7a \
  -D CMAKE_BUILD_TYPE=Release
cmake --build build --config Release
cp bin/spoof ../app/src/main/assets/probe/armeabi-v7a/
```

## Demo & Conclusion
Demo:
- Add screenshots/video for Probe → Map → History restore.

Challenges & Solutions:
- Binary updates: force re-copy from assets per run to guarantee latest probe.
- Key leakage: moved API key to local properties + BuildConfig.
- Inter‑carrier detection: implemented timing rules from SIP responses.

Future Work:
- Multi‑target management.
- Stronger error classification when probe fails.
- Deeper analytics on probe results.
- Isolate or remove legacy native server, adaptive-prediction, and native-geolocation modes after confirming they are no longer required.
