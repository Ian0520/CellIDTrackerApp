# CellIDTracker Project Overview

## Project Overview
- Project Name: CellIDTracker
- Background/Motivation: The goal is to automate the workflow of extracting Cell ID information from a SIP/Wi‑Fi Calling probe and turning it into a map location, removing manual log parsing and ad‑hoc lookup steps.
- Core Objective: Run a rooted probe, parse cell info (MCC/MNC/LAC/CID), query Google Geolocation, and visualize the result with an accuracy circle; provide history and inter‑carrier detection for validation.

## System Architecture
(Text diagram; replace with a visual diagram if needed.)

```
[Android App (Compose UI)]
   |-- Probe Runner (RootShell, assets)
   |-- Log Parser (MCC/MNC/LAC/CID)
   |-- Inter-carrier Detector (delta_ms)
   |-- Geolocation Client (Google API)
   |-- Map Viewer (osmdroid)
   |-- History Manager (in-memory)
            |
            v
[Native Probe Binary (C++ / SIP)]
   |-- SIP INVITE / CANCEL
   |-- Cell ID Extraction
   |-- Inter-carrier timing log
```

Module Breakdown:
- UI/UX (Compose): controls, status, map, history, logs.
- Probe Runner: copies binary/config from assets and executes as root.
- Parser: streams stdout and extracts cell tower info.
- Geolocation Client: calls Google Geolocation API (supports multi-tower).
- Inter‑carrier Detector: uses SIP timing delta to classify.
- Native Probe: SIP handling + cell extraction.

## Tech Stack

| Category | Technology | Reason |
| --- | --- | --- |
| Frontend | Kotlin + Jetpack Compose | Native Android UI, rapid iteration |
| Native/Low‑level | C++ / NDK / CMake | SIP probe needs low-level control |
| Networking | OkHttp (app), libcurl + mbedtls (native) | Stable HTTP clients |
| Map | osmdroid | Open-source, easy circle overlay |
| Build | Gradle (KTS) | Standard Android tooling |
| Database | None | History stored in memory |

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
- `recentTowers` (in‑memory, up to 5).
- `history` (in‑memory).
- No database/server.

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
- Multi‑tower geolocation: last 5 towers are sent to improve stability.
- Inter‑carrier detection: measure delta between `100 Trying` and first provisional response (`180/183`).
  - `<= 600 ms` → inter‑carrier.
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
- Persist history (Room/SQLite).
- Multi‑target management.
- Stronger error classification when probe fails.
- Deeper analytics on probe results.
